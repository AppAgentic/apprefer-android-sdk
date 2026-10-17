package com.apprefer.sdk.engagement;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AppRefer lifecycle notifications on Android (wire schema 1), self-contained so the Kotlin SDK,
 * the Flutter plugin and the React Native module share one implementation (only the package
 * line differs). Platform APIs only: no AndroidX, no Firebase dependency.
 *
 * <ul>
 * <li>{@link #handleMessage} validates an FCM data map ({@code type=engagement}), checks it
 * against the install and account epoch the app last set ({@link #setIdentity}) and its expiry,
 * and posts it: purpose channel, title/body (BigText) or a BigPicture image from an allowlisted
 * host (bounded download, no redirects, PNG/JPEG decode and pixel checks, text when anything
 * fails), up to two buttons with explicit immutable PendingIntents, a time-out at expiry.
 * Anything else (Support's {@code support_reply}, the app's own messages) returns false.</li>
 * <li>Taps and buttons open the app's launch activity (or the configured one) with a random
 * per-notification nonce; {@link #handleIntent} accepts only a nonce this renderer recorded,
 * so an intent forged by another app opens nothing. Snooze and dismiss go to the receiver.</li>
 * <li>Every outcome (received, opened, action, snoozed, dismissed) is recorded once in a small
 * persisted outbox that the SDK layer drains with {@link #takeEvents}, re-checks against the
 * current identity, turns into receipts and delivers to the app (navigation only when the app
 * is ready). Nothing here talks to AppRefer's servers.</li>
 * </ul>
 */
public final class EngagementRenderer {
    private EngagementRenderer() {}

    static final String PREFS = "apprefer_engagement_notifications";
    public static final String EXTRA_NONCE = "com.apprefer.engagement.NONCE";
    public static final String EXTRA_ACTION = "com.apprefer.engagement.ACTION";
    static final String ACTION_SNOOZE = "com.apprefer.engagement.SNOOZE";
    static final String ACTION_DISMISS = "com.apprefer.engagement.DISMISS";
    static final String ACTION_SHOW = "com.apprefer.engagement.SHOW";
    public static final String[] PURPOSES = {"marketing", "reminders", "service"};
    public static final String[] KINDS = {"open", "view", "start", "snooze"};
    static final int MAX_RECORDS = 50;
    static final int MAX_EVENTS = 50;
    static final long EIGHT_DAYS = 8L * 86400000L;

    private static final Pattern MESSAGE_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,400}$");
    private static final Pattern INSTALL_ID = Pattern.compile("^ei_[a-z2-7]{26}$");
    private static final Pattern DESTINATION = Pattern.compile("^/[A-Za-z0-9/_\\-.~%?=&]*$");
    private static final Pattern COLLAPSE = Pattern.compile("^[a-z0-9_-]{1,32}$");
    private static final Pattern ACTION_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    private static final Pattern TOKEN = Pattern.compile("^er1\\.[A-Za-z0-9_-]{43}$");
    private static final Pattern LOCALE = Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$");
    private static final Pattern IMAGE_URL = Pattern.compile("^https://([a-z0-9.-]+)(/[A-Za-z0-9_\\-.~%]+)*/[A-Za-z0-9_\\-.~%]+\\.(png|jpe?g)$");
    private static final Pattern HOST = Pattern.compile("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final Pattern ENCODED_SEPARATOR = Pattern.compile("(?i)%2e|%2f|%5c");

    // ------------------------------------------------------------------------------------
    // Payload

    /** A parsed, validated notification. */
    public static final class Payload {
        public final String messageId, purpose, installId, destination, collapseKey, locale, title, subtitle, body, imageUrl, receiptToken;
        public final long expiresAtMs;
        public final int accountEpoch;
        public final List<Action> actions;
        final JSONObject raw;

        Payload(JSONObject raw, String messageId, String purpose, long exp, int epoch, String installId, String destination, String collapseKey,
                String locale, String title, String subtitle, String body, String imageUrl, List<Action> actions, String receiptToken) {
            this.raw = raw; this.messageId = messageId; this.purpose = purpose; this.expiresAtMs = exp; this.accountEpoch = epoch;
            this.installId = installId; this.destination = destination; this.collapseKey = collapseKey; this.locale = locale; this.title = title;
            this.subtitle = subtitle; this.body = body; this.imageUrl = imageUrl; this.actions = actions; this.receiptToken = receiptToken;
        }

        public boolean isExpired(long nowMs) { return nowMs >= expiresAtMs; }
    }

    public static final class Action {
        public final String id, kind, destination;
        public final int snoozeMinutes;
        Action(String id, String kind, String destination, int snoozeMinutes) { this.id = id; this.kind = kind; this.destination = destination; this.snoozeMinutes = snoozeMinutes; }
    }

    /** Thrown for a payload AppRefer owns but can't use; the reason is a short code. */
    public static final class InvalidPayload extends Exception {
        public InvalidPayload(String reason) { super(reason); }
    }

    /** True when the FCM data map is an AppRefer engagement notification (valid or not). */
    public static boolean isEngagement(Map<String, String> data) {
        return data != null && "engagement".equals(data.get("type"));
    }

    /** Parses an FCM data map; null when it is not an engagement notification. */
    public static Payload parse(Map<String, String> data) throws InvalidPayload {
        if (!isEngagement(data)) return null;
        String text = data.get("engagement");
        if (text == null || text.length() > 8192) throw new InvalidPayload("malformed");
        try { return parse(new JSONObject(text)); } catch (JSONException e) { throw new InvalidPayload("malformed"); }
    }

    public static Payload parse(JSONObject o) throws InvalidPayload {
        if (!"engagement".equals(o.optString("type", null))) throw new InvalidPayload("type");
        if (integer(o.opt("v")) == null || integer(o.opt("v")) != 1) throw new InvalidPayload("unsupported_version");
        String mid = string(o, "mid");
        if (mid == null || !MESSAGE_ID.matcher(mid).matches()) throw new InvalidPayload("message_id");
        String purpose = string(o, "purpose");
        if (purpose == null || !contains(PURPOSES, purpose)) throw new InvalidPayload("purpose");
        Double exp = number(o.opt("exp"));
        if (exp == null || exp <= 0) throw new InvalidPayload("expiry");
        Integer epoch = integer(o.opt("epoch"));
        if (epoch == null || epoch < 0) throw new InvalidPayload("epoch");
        String iid = string(o, "iid");
        if (iid == null || !INSTALL_ID.matcher(iid).matches()) throw new InvalidPayload("install");
        String dest = nullableString(o, "dest");
        if (dest != null && !isDestination(dest)) throw new InvalidPayload("destination");
        if (o.has("dest") && !o.isNull("dest") && dest == null) throw new InvalidPayload("destination");
        String ck = nullableString(o, "ck");
        if (ck != null && !COLLAPSE.matcher(ck).matches()) throw new InvalidPayload("collapse_key");
        String title = string(o, "title"), body = string(o, "body"), subtitle = nullableString(o, "subtitle");
        if (title == null || title.isEmpty() || title.length() > 128 || body == null || body.isEmpty() || body.length() > 512) throw new InvalidPayload("copy");
        if (subtitle != null && subtitle.length() > 128) throw new InvalidPayload("copy");
        String img = nullableString(o, "img");
        if (o.has("img") && !o.isNull("img") && (img == null || imageHost(img) == null)) throw new InvalidPayload("image");
        List<Action> actions = new ArrayList<>();
        if (o.has("actions") && !o.isNull("actions")) {
            JSONArray list = o.optJSONArray("actions");
            if (list == null || list.length() > 2) throw new InvalidPayload("actions");
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                if (a == null) throw new InvalidPayload("actions");
                String id = string(a, "id"), kind = string(a, "kind");
                if (id == null || !ACTION_ID.matcher(id).matches() || kind == null || !contains(KINDS, kind)) throw new InvalidPayload("actions");
                for (Action other : actions) if (other.id.equals(id) || other.kind.equals(kind)) throw new InvalidPayload("actions");
                if ("snooze".equals(kind)) {
                    Integer min = integer(a.opt("min"));
                    if (min == null || min < 5 || min > 1440 || a.has("dest")) throw new InvalidPayload("actions");
                    actions.add(new Action(id, kind, null, min));
                } else {
                    String ad = nullableString(a, "dest");
                    if (a.has("dest") && !a.isNull("dest") && (ad == null || !isDestination(ad))) throw new InvalidPayload("actions");
                    actions.add(new Action(id, kind, ad, 0));
                }
            }
        }
        String rt = string(o, "rt");
        if (rt == null || !TOKEN.matcher(rt).matches()) throw new InvalidPayload("receipt_token");
        String locale = nullableString(o, "locale");
        if (locale != null && !LOCALE.matcher(locale).matches()) locale = null;
        return new Payload(o, mid, purpose, exp.longValue(), epoch, iid, dest, ck, locale, title, subtitle, body, img, Collections.unmodifiableList(actions), rt);
    }

    public static boolean isDestination(String value) {
        return value != null && value.length() <= 256 && !value.startsWith("//") && DESTINATION.matcher(value).matches();
    }

    /** The URL's host when it is an allowed image URL shape, else null. */
    public static String imageHost(String url) {
        if (url == null || url.length() > 512) return null;
        java.util.regex.Matcher m = IMAGE_URL.matcher(url);
        if (!m.matches()) return null;
        String host = m.group(1);
        if (!isHost(host)) return null;
        String path = url.substring(8 + host.length());
        if (path.contains("//")) return null;
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..") || ENCODED_SEPARATOR.matcher(segment).find()) return null;
        }
        return host;
    }

    public static boolean isHost(String host) {
        return host != null && host.length() >= 4 && host.length() <= 253 && HOST.matcher(host).matches();
    }

    private static boolean contains(String[] values, String v) { for (String s : values) if (s.equals(v)) return true; return false; }
    private static String string(JSONObject o, String key) { Object v = o.opt(key); return v instanceof String ? (String) v : null; }
    private static String nullableString(JSONObject o, String key) { return o.isNull(key) ? null : string(o, key); }
    private static Double number(Object v) {
        if (v instanceof Boolean || !(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }
    private static Integer integer(Object v) {
        Double d = number(v);
        if (d == null || d != Math.rint(d) || Math.abs(d) > 1e9) return null;
        return d.intValue();
    }

    // ------------------------------------------------------------------------------------
    // Configuration and identity (set by the SDK layer; kept in preferences for receivers)

    /**
     * Host settings. Labels are the SDK's localized button texts by kind and the channel names by
     * purpose, both in the app's language. The small icon should be a monochrome resource.
     */
    public static void configure(Context context, int smallIcon, Set<String> allowedImageHosts, Map<String, String> labels,
                                 Map<String, String> channelNames, String tapActivity) {
        JSONObject c = new JSONObject();
        try {
            c.put("icon", smallIcon);
            JSONArray hosts = new JSONArray();
            if (allowedImageHosts != null) for (String h : allowedImageHosts) if (isHost(h)) hosts.put(h);
            c.put("hosts", hosts);
            c.put("labels", new JSONObject(labels == null ? new HashMap<String, String>() : labels));
            c.put("channels", new JSONObject(channelNames == null ? new HashMap<String, String>() : channelNames));
            if (tapActivity != null) c.put("activity", tapActivity);
        } catch (JSONException ignored) {}
        prefs(context).edit().putString("config", c.toString()).commit();
        ensureChannels(context);
    }

    /** The install and account epoch notifications must match; epoch -1 = mid change (nothing matches). Null clears. */
    public static void setIdentity(Context context, String installId, int accountEpoch) {
        SharedPreferences.Editor e = prefs(context).edit();
        if (installId == null) e.remove("iid").remove("epoch");
        else e.putString("iid", installId).putInt("epoch", accountEpoch);
        e.commit();
    }

    static boolean matchesIdentity(Context context, Payload p) {
        SharedPreferences sp = prefs(context);
        String iid = sp.getString("iid", null);
        int epoch = sp.getInt("epoch", -1);
        return iid != null && epoch >= 0 && iid.equals(p.installId) && epoch == p.accountEpoch;
    }

    private static JSONObject config(Context context) {
        try { return new JSONObject(prefs(context).getString("config", "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    static SharedPreferences prefs(Context context) { return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static String channelId(String purpose) { return "apprefer_" + purpose; }

    /** One channel per purpose, so people can turn offers off and keep account notices. */
    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        JSONObject names = config(context).optJSONObject("channels");
        for (String purpose : PURPOSES) {
            String name = names != null ? names.optString(purpose, "") : "";
            if (name.isEmpty()) name = purpose;
            int importance = "marketing".equals(purpose) ? NotificationManager.IMPORTANCE_LOW : NotificationManager.IMPORTANCE_DEFAULT;
            // Creating an existing channel only updates its name: the person's own choices stay.
            nm.createNotificationChannel(new NotificationChannel(channelId(purpose), name, importance));
        }
    }

    // ------------------------------------------------------------------------------------
    // Readback (never asks for anything)

    /** "granted", "denied" or "unknown": the app-wide permission (POST_NOTIFICATIONS on 13+). */
    /** Tests only: the permission answer (revoking it in a running process is not immediate). */
    static volatile String permissionForTesting;

    @SuppressLint("NewApi")
    public static String permission(Context context) {
        if (permissionForTesting != null) return permissionForTesting;
        if (Build.VERSION.SDK_INT >= 33
            && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return "denied";
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return "unknown";
        if (Build.VERSION.SDK_INT >= 24) return nm.areNotificationsEnabled() ? "granted" : "denied";
        return legacyEnabled(context);
    }

    /** API 21–23: the notification app-op, as NotificationManagerCompat reads it. */
    private static String legacyEnabled(Context context) {
        try {
            Object ops = context.getSystemService(Context.APP_OPS_SERVICE);
            Class<?> cls = Class.forName("android.app.AppOpsManager");
            int op = (Integer) cls.getDeclaredField("OP_POST_NOTIFICATION").get(null);
            int mode = (Integer) cls.getMethod("checkOpNoThrow", int.class, int.class, String.class)
                .invoke(ops, op, context.getApplicationInfo().uid, context.getPackageName());
            return mode == 0 ? "granted" : "denied";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Whether the purpose's channel is on (API 26+); null below 26 or when it doesn't exist yet. */
    public static Boolean channelEnabled(Context context, String purpose) {
        if (Build.VERSION.SDK_INT < 26) return null;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = nm == null ? null : nm.getNotificationChannel(channelId(purpose));
        return ch == null ? null : ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    // ------------------------------------------------------------------------------------
    // Posting

    /** Outcome of {@link #handleMessage}. */
    public enum Result { NOT_ENGAGEMENT, IGNORED, SHOWN, SHOWN_TEXT_ONLY }

    /**
     * From the app's FirebaseMessagingService.onMessageReceived (a background thread). Returns
     * {@link Result#NOT_ENGAGEMENT} for anything that isn't AppRefer's. Downloads the image (at
     * most {@code timeoutMs}) only on a background thread; on the main thread the text is posted.
     */
    public static Result handleMessage(Context context, Map<String, String> data, long nowMs, int timeoutMs, int maxBytes) {
        if (!isEngagement(data)) return Result.NOT_ENGAGEMENT;
        Payload p;
        try { p = parse(data); } catch (InvalidPayload e) { return Result.IGNORED; }
        // Another install, another account, a sign-out in progress or expired: never shown.
        if (p == null || p.isExpired(nowMs) || !matchesIdentity(context, p)) return Result.IGNORED;
        // Not allowed to post: the system would drop it silently, so nothing is shown or reported as received.
        if ("denied".equals(permission(context))) return Result.IGNORED;
        // The person turned this purpose's channel off: respect it (the system would drop it too).
        if (Boolean.FALSE.equals(channelEnabled(context, p.purpose))) return Result.IGNORED;
        Bitmap image = null;
        JSONObject cfg = config(context);
        if (p.imageUrl != null && Looper.myLooper() != Looper.getMainLooper() && allowed(cfg, p.imageUrl)) {
            image = download(p.imageUrl, timeoutMs, maxBytes, 2048);
        }
        post(context, p, image, false, nowMs);
        recordEvent(context, "received", p, null, nowMs);
        return image != null ? Result.SHOWN : Result.SHOWN_TEXT_ONLY;
    }

    static boolean allowed(JSONObject cfg, String url) {
        String host = imageHost(url);
        JSONArray hosts = cfg.optJSONArray("hosts");
        if (host == null || hosts == null) return false;
        for (int i = 0; i < hosts.length(); i++) if (host.equals(hosts.optString(i))) return true;
        return false;
    }

    /** Bounded image fetch: HTTPS, no redirects, PNG/JPEG only, size and pixel limits; null on any failure. */
    public static Bitmap download(String url, int timeoutMs, int maxBytes, int maxPixels) {
        byte[] bytes = fetch(url, timeoutMs, maxBytes);
        if (bytes == null) return null;
        return decode(bytes, maxPixels);
    }

    static byte[] fetch(String url, int timeoutMs, int maxBytes) {
        HttpURLConnection c = null;
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "image/png, image/jpeg");
            if (c.getResponseCode() != 200) return null;
            String type = c.getContentType();
            String mime = type == null ? "" : type.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!mime.equals("image/png") && !mime.equals("image/jpeg")) return null;
            if (c.getContentLength() > maxBytes) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > maxBytes || System.currentTimeMillis() > deadline) return null;
            }
            byte[] bytes = out.toByteArray();
            boolean png = bytes.length > 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
            boolean jpeg = bytes.length > 3 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xD8;
            // The bytes must be the type the server declared.
            if ((mime.equals("image/png") && !png) || (mime.equals("image/jpeg") && !jpeg)) return null;
            return bytes;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static Bitmap decode(byte[] bytes, int maxPixels) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > maxPixels || bounds.outHeight > maxPixels) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        // Notifications show at most ~450dp wide: decode down to about 1024 px.
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2;
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    static int notificationId(String messageId) { return ("apprefer.eng:" + messageId).hashCode(); }

    @SuppressWarnings("deprecation")
    static void post(Context context, Payload p, Bitmap image, boolean snoozed, long nowMs) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        JSONObject cfg = config(context);
        ensureChannels(context);
        String nonce = newNonce();
        saveRecord(context, nonce, p, nowMs);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, channelId(p.purpose)) : new Notification.Builder(context);
        int icon = cfg.optInt("icon", 0);
        if (icon == 0) icon = context.getApplicationInfo().icon;
        b.setSmallIcon(icon).setContentTitle(p.title).setContentText(p.body).setAutoCancel(true).setOnlyAlertOnce(true);
        if (p.subtitle != null) b.setSubText(p.subtitle);
        if (Build.VERSION.SDK_INT < 26) b.setPriority("marketing".equals(p.purpose) ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT);
        b.setCategory("service".equals(p.purpose) ? Notification.CATEGORY_STATUS : ("marketing".equals(p.purpose) ? Notification.CATEGORY_PROMO : Notification.CATEGORY_REMINDER));
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(Math.max(1000L, p.expiresAtMs - nowMs));
        if (image != null) {
            b.setLargeIcon(image).setStyle(new Notification.BigPictureStyle().bigPicture(image).setSummaryText(p.body));
        } else {
            b.setStyle(new Notification.BigTextStyle().bigText(p.body));
        }
        int code = notificationId(p.messageId);
        PendingIntent tap = activityIntent(context, cfg, nonce, null, code);
        if (tap != null) b.setContentIntent(tap);
        b.setDeleteIntent(receiverIntent(context, ACTION_DISMISS, nonce, null, code + 3));
        JSONObject labels = cfg.optJSONObject("labels");
        int index = 0;
        for (Action a : p.actions) {
            if (snoozed && "snooze".equals(a.kind)) continue;
            String label = labels == null ? "" : labels.optString(a.kind, "");
            if (label.isEmpty()) continue;
            PendingIntent pi = "snooze".equals(a.kind)
                ? receiverIntent(context, ACTION_SNOOZE, nonce, a.id, code + 1 + index)
                : activityIntent(context, cfg, nonce, a.id, code + 1 + index);
            if (pi != null) b.addAction(new Notification.Action.Builder(0, label, pi).build());
            index++;
        }
        nm.notify(p.collapseKey != null ? "apprefer.eng." + p.collapseKey : "apprefer.eng", p.collapseKey != null ? 0 : code, b.build());
    }

    private static int immutable() { return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0; }

    /** Explicit: the app's own launch activity (or the configured one), never an implicit intent. */
    static PendingIntent activityIntent(Context context, JSONObject cfg, String nonce, String actionId, int requestCode) {
        Intent intent;
        String activity = cfg.optString("activity", "");
        if (!activity.isEmpty()) {
            intent = new Intent().setComponent(new ComponentName(context.getPackageName(), activity));
        } else {
            intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (intent == null) return null;
        }
        intent.setPackage(context.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(EXTRA_NONCE, nonce);
        if (actionId != null) intent.putExtra(EXTRA_ACTION, actionId);
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }

    static PendingIntent receiverIntent(Context context, String action, String nonce, String actionId, int requestCode) {
        Intent intent = new Intent(context, Receiver.class).setAction(action).setPackage(context.getPackageName()).putExtra(EXTRA_NONCE, nonce);
        if (actionId != null) intent.putExtra(EXTRA_ACTION, actionId);
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }

    // ------------------------------------------------------------------------------------
    // Records (which notifications this renderer posted) and the event outbox

    private static String newNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    static synchronized void saveRecord(Context context, String nonce, Payload p, long nowMs) {
        JSONObject records = records(context);
        try {
            JSONObject r = new JSONObject();
            r.put("payload", p.raw);
            r.put("at", nowMs);
            records.put(nonce, r);
        } catch (JSONException ignored) {}
        // Bounded: a week past posting, newest 50.
        List<String> keys = new ArrayList<>();
        Iterator<String> it = records.keys();
        while (it.hasNext()) keys.add(it.next());
        final JSONObject all = records;
        Collections.sort(keys, (a, b) -> Long.compare(all.optJSONObject(b).optLong("at"), all.optJSONObject(a).optLong("at")));
        for (int i = 0; i < keys.size(); i++) {
            JSONObject r = records.optJSONObject(keys.get(i));
            if (i >= MAX_RECORDS || r == null || nowMs - r.optLong("at") > EIGHT_DAYS) records.remove(keys.get(i));
        }
        prefs(context).edit().putString("records", records.toString()).commit();
    }

    private static JSONObject records(Context context) {
        try { return new JSONObject(prefs(context).getString("records", "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    static Payload record(Context context, String nonce) {
        if (nonce == null || !nonce.matches("^[0-9a-f]{32}$")) return null;
        JSONObject r = records(context).optJSONObject(nonce);
        if (r == null) return null;
        try { return parse(r.getJSONObject("payload")); } catch (Exception e) { return null; }
    }

    /**
     * From the launch activity's onCreate/onNewIntent. True when the intent came from an AppRefer
     * notification this renderer posted; the tap (or button) is then recorded once. The extras are
     * removed, so a recreated activity doesn't report it again. A forged intent returns false.
     */
    public static boolean handleIntent(Context context, Intent intent, long nowMs) {
        if (intent == null || !intent.hasExtra(EXTRA_NONCE)) return false;
        String nonce = intent.getStringExtra(EXTRA_NONCE);
        String actionId = intent.getStringExtra(EXTRA_ACTION);
        intent.removeExtra(EXTRA_NONCE);
        intent.removeExtra(EXTRA_ACTION);
        Payload p = record(context, nonce);
        if (p == null) return false;
        Action action = null;
        if (actionId != null) {
            for (Action a : p.actions) if (a.id.equals(actionId) && !"snooze".equals(a.kind)) action = a;
            if (action == null) return true;
        }
        recordEvent(context, action == null ? "opened" : "action", p, action, nowMs);
        return true;
    }

    /** Records an outcome once per message (and button). False for a repeat. */
    static synchronized boolean recordEvent(Context context, String kind, Payload p, Action action, long nowMs) {
        SharedPreferences sp = prefs(context);
        String key = kind + ":" + p.messageId + ("action".equals(kind) && action != null ? ":" + action.id : "");
        JSONObject handled;
        try { handled = new JSONObject(sp.getString("handled", "{}")); } catch (JSONException e) { handled = new JSONObject(); }
        if (handled.has(key)) return false;
        try { handled.put(key, nowMs); } catch (JSONException ignored) {}
        List<String> keys = new ArrayList<>();
        Iterator<String> it = handled.keys();
        while (it.hasNext()) keys.add(it.next());
        final JSONObject all = handled;
        Collections.sort(keys, (a, b) -> Long.compare(all.optLong(b), all.optLong(a)));
        for (int i = 0; i < keys.size(); i++) if (i >= 300 || nowMs - handled.optLong(keys.get(i)) > EIGHT_DAYS) handled.remove(keys.get(i));

        JSONArray events = events(sp);
        JSONObject e = new JSONObject();
        try {
            e.put("kind", kind);
            e.put("messageId", p.messageId);
            e.put("purpose", p.purpose);
            e.put("installId", p.installId);
            e.put("accountEpoch", p.accountEpoch);
            e.put("receiptToken", p.receiptToken);
            e.put("expiresAt", p.expiresAtMs);
            e.put("at", nowMs);
            String dest = "opened".equals(kind) ? p.destination : ("action".equals(kind) && action != null ? (action.destination != null ? action.destination : p.destination) : null);
            if (dest != null) e.put("destination", dest);
            if (action != null) { e.put("actionId", action.id); e.put("actionKind", action.kind); }
        } catch (JSONException ignored) {}
        events.put(e);
        JSONArray bounded = new JSONArray();
        for (int i = Math.max(0, events.length() - MAX_EVENTS); i < events.length(); i++) bounded.put(events.opt(i));
        sp.edit().putString("handled", handled.toString()).putString("events", bounded.toString()).commit();
        return true;
    }

    private static JSONArray events(SharedPreferences sp) {
        try { return new JSONArray(sp.getString("events", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    /** Takes (and clears) the recorded outcomes, oldest first, as JSON objects. */
    public static synchronized List<JSONObject> takeEvents(Context context) {
        SharedPreferences sp = prefs(context);
        JSONArray events = events(sp);
        sp.edit().remove("events").commit();
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < events.length(); i++) { JSONObject o = events.optJSONObject(i); if (o != null) out.add(o); }
        return out;
    }

    /** Account changed or reset: what waits for the previous identity is dropped. */
    public static synchronized void clearForAccountChange(Context context) {
        prefs(context).edit().remove("events").remove("records").commit();
    }

    // ------------------------------------------------------------------------------------
    // Snooze and dismiss

    /** Handles the receiver's intents; also callable directly in tests. */
    public static void onReceive(Context context, Intent intent, long nowMs) {
        if (intent == null) return;
        String nonce = intent.getStringExtra(EXTRA_NONCE);
        Payload p = record(context, nonce);
        if (p == null) return;
        String action = intent.getAction();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (ACTION_DISMISS.equals(action)) {
            recordEvent(context, "dismissed", p, null, nowMs);
        } else if (ACTION_SNOOZE.equals(action)) {
            Action snooze = null;
            for (Action a : p.actions) if ("snooze".equals(a.kind) && a.id.equals(intent.getStringExtra(EXTRA_ACTION))) snooze = a;
            if (snooze == null || !recordEvent(context, "snoozed", p, snooze, nowMs)) return;
            if (nm != null) {
                if (p.collapseKey != null) nm.cancel("apprefer.eng." + p.collapseKey, 0);
                else nm.cancel("apprefer.eng", notificationId(p.messageId));
            }
            long at = Math.min(nowMs + snooze.snoozeMinutes * 60000L, p.expiresAtMs - 60000L);
            if (at - nowMs < 60000L) return; // would only reappear expired
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            // Inexact (no exact-alarm permission needed); the notification is re-shown once.
            if (am != null) am.set(AlarmManager.RTC, at, receiverIntent(context, ACTION_SHOW, nonce, null, notificationId(p.messageId) + 7));
        } else if (ACTION_SHOW.equals(action)) {
            if (!p.isExpired(nowMs) && matchesIdentity(context, p)) post(context, p, null, true, nowMs);
        }
    }

    /** Declared in the library manifest (not exported); only the SDK's own PendingIntents reach it. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            EngagementRenderer.onReceive(context, intent, System.currentTimeMillis());
        }
    }

    /** For tests: posted-notification nonces. */
    static Set<String> recordNonces(Context context) {
        Set<String> out = new HashSet<>();
        Iterator<String> it = records(context).keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }
}
