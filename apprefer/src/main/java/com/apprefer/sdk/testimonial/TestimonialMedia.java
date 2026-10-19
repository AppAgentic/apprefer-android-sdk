package com.apprefer.sdk.testimonial;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

/**
 * Photo/video capture for Support testimonials, shared by copy by the Android SDK (canonical), the
 * Flutter plugin and the React Native module (only the package line differs; keep them identical).
 *
 * Pickers: the system photo picker (Android 13+, no permission) or the document picker, and the
 * camera app for a video with a length limit. Photos are re-encoded as JPEG (at most 2048 px on the
 * long side, orientation applied, no EXIF/GPS). Videos are re-muxed into MP4 without the source's
 * metadata (no location) and refused when longer than the request allows. The server checks the
 * uploaded bytes again; it never trusts the duration sent from here.
 */
public final class TestimonialMedia {
    private TestimonialMedia() {}

    public static final long MAX_IMAGE_BYTES = 15L * 1024 * 1024;
    public static final long MAX_VIDEO_BYTES = 150L * 1024 * 1024;
    public static final int MAX_IMAGE_DIMENSION = 2048;

    /** A photo or video ready to upload (a file in the app's cache the caller deletes when done). */
    public static final class Picked {
        public final String path;
        public final String contentType;
        public final String kind;
        public final long size;
        /** Video length in ms (null for photos). */
        public final Long durationMs;

        public Picked(String path, String contentType, String kind, long size, Long durationMs) {
            this.path = path; this.contentType = contentType; this.kind = kind; this.size = size; this.durationMs = durationMs;
        }
    }

    /** Why a pick can't be used: too_long, too_large, unreadable or cancelled. */
    public static final class MediaException extends Exception {
        public final String code;
        public final int seconds;
        public MediaException(String code, int seconds) { super(code); this.code = code; this.seconds = seconds; }
    }

    /** Pick one photo and/or video: the system photo picker on Android 13+, else the document picker. */
    public static Intent libraryIntent(boolean images, boolean videos) {
        if (Build.VERSION.SDK_INT >= 33) {
            Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            if (images && !videos) intent.setType("image/*");
            else if (videos && !images) intent.setType("video/*");
            return intent;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
        if (images && videos) {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*" });
        } else {
            intent.setType(videos ? "video/*" : "image/*");
        }
        return intent;
    }

    /** Record a video in the camera app, limited to `maxSeconds`. */
    public static Intent videoCaptureIntent(int maxSeconds) {
        return new Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_DURATION_LIMIT, maxSeconds);
    }

    /**
     * The camera can be offered: a camera and a camera app exist, and the app doesn't declare the
     * CAMERA permission without holding it (Android then refuses capture intents).
     */
    public static boolean cameraAvailable(Context context) {
        PackageManager pm = context.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return false;
        if (videoCaptureIntent(30).resolveActivity(pm) == null) return false;
        if (declares(context, Manifest.permission.CAMERA) && Build.VERSION.SDK_INT >= 23
            && context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private static boolean declares(Context context, String permission) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return false;
            for (String p : info.requestedPermissions) if (permission.equals(p)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Normalises what the picker or camera returned into an upload-ready file. */
    public static Picked normalize(Context context, Uri uri, boolean images, boolean videos, int maxSeconds) throws MediaException {
        String type = context.getContentResolver().getType(uri);
        boolean video = type != null ? type.startsWith("video/") : !images;
        if (video && !videos) throw new MediaException("unreadable", 0);
        if (!video && !images) throw new MediaException("unreadable", 0);
        File source = copyToCache(context, uri, video ? "src" : "img");
        try {
            return video ? normalizeVideo(context, source, maxSeconds) : normalizeImage(context, source);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
    }

    static File copyToCache(Context context, Uri uri, String ext) throws MediaException {
        File out = new File(context.getCacheDir(), "apprefer-testimonial-" + UUID.randomUUID() + "." + ext);
        try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new MediaException("unreadable", 0);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                // A file far larger than any upload limit isn't worth copying further.
                if (total > MAX_VIDEO_BYTES * 4) throw new MediaException("too_large", 0);
                os.write(buf, 0, n);
            }
            return out;
        } catch (IOException | SecurityException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new MediaException("unreadable", 0);
        } catch (MediaException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
    }

    /** JPEG, orientation applied, at most 2048 px on the long side, no metadata. */
    public static Picked normalizeImage(Context context, File source) throws MediaException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new MediaException("unreadable", 0);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_DIMENSION) sample *= 2;
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(source.getPath(), opts);
        if (bitmap == null) throw new MediaException("unreadable", 0);
        int rotation = 0;
        try {
            int o = new ExifInterface(source.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rotation = o == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (IOException ignored) {}
        float scale = Math.min(1f, (float) MAX_IMAGE_DIMENSION / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        Matrix m = new Matrix();
        if (scale < 1f) m.postScale(scale, scale);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap drawn = m.isIdentity() ? bitmap : Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        File out = new File(context.getCacheDir(), "apprefer-testimonial-" + UUID.randomUUID() + ".jpg");
        int quality = 85;
        try {
            while (true) {
                try (FileOutputStream os = new FileOutputStream(out)) {
                    if (!drawn.compress(Bitmap.CompressFormat.JPEG, quality, os)) throw new MediaException("unreadable", 0);
                }
                if (out.length() <= MAX_IMAGE_BYTES) break;
                if (quality <= 40) { //noinspection ResultOfMethodCallIgnored
                    out.delete(); throw new MediaException("too_large", 0); }
                quality -= 15;
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new MediaException("unreadable", 0);
        } finally {
            if (drawn != bitmap) drawn.recycle();
            bitmap.recycle();
        }
        return new Picked(out.getPath(), "image/jpeg", "image", out.length(), null);
    }

    /** Checks the length, then re-muxes the audio/video tracks into a fresh MP4 without metadata. */
    public static Picked normalizeVideo(Context context, File source, int maxSeconds) throws MediaException {
        long durationMs;
        int rotation = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(source.getPath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            durationMs = d == null ? -1 : Long.parseLong(d);
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rot != null) rotation = Integer.parseInt(rot);
        } catch (RuntimeException e) {
            throw new MediaException("unreadable", 0);
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        if (durationMs <= 0) throw new MediaException("unreadable", 0);
        if (durationMs > maxSeconds * 1000L + 500) throw new MediaException("too_long", maxSeconds);
        File out = new File(context.getCacheDir(), "apprefer-testimonial-" + UUID.randomUUID() + ".mp4");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(source.getPath());
            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int count = extractor.getTrackCount();
            int[] map = new int[count];
            int maxInput = 1024 * 1024;
            boolean anyVideo = false;
            for (int i = 0; i < count; i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                map[i] = -1;
                if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) continue;
                if (mime.startsWith("video/")) anyVideo = true;
                extractor.selectTrack(i);
                map[i] = muxer.addTrack(f);
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = Math.max(maxInput, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            if (!anyVideo) throw new MediaException("unreadable", 0);
            if (rotation != 0) muxer.setOrientationHint(rotation);
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocate(maxInput);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                int track = map[extractor.getSampleTrackIndex()];
                if (track >= 0) {
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = extractor.getSampleTime();
                    info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    muxer.writeSampleData(track, buffer, info);
                }
                extractor.advance();
            }
            muxer.stop();
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new MediaException("unreadable", 0);
        } finally {
            extractor.release();
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) {}
        }
        if (out.length() <= 0) throw new MediaException("unreadable", 0);
        if (out.length() > MAX_VIDEO_BYTES) { //noinspection ResultOfMethodCallIgnored
            out.delete(); throw new MediaException("too_large", 0); }
        return new Picked(out.getPath(), "video/mp4", "video", out.length(), durationMs);
    }

    /** Upload progress, 0..1. */
    public interface Progress { void onProgress(double fraction); }

    /** PUTs a file to its presigned URL with the signed headers, streaming it; returns the HTTP status. */
    public static int upload(File file, String url, Map<String, String> headers, long size, Progress progress) throws IOException {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol())) throw new IOException("upload address must be https");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setRequestMethod("PUT");
            c.setDoOutput(true);
            c.setConnectTimeout(20_000);
            c.setReadTimeout(300_000);
            c.setFixedLengthStreamingMode(size);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            try (InputStream in = new FileInputStream(file); OutputStream os = c.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                long sent = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    sent += n;
                    if (progress != null && size > 0) progress.onProgress(Math.min(1.0, (double) sent / size));
                }
            }
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    /** Deletes a file this class made (only inside the cache folder). */
    public static void discard(Context context, String path) {
        if (path == null) return;
        File f = new File(path);
        try {
            if (f.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath() + File.separator) && f.getName().startsWith("apprefer-testimonial-")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (IOException ignored) {}
    }
}
