package com.apprefer.sdk.engagement

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.apprefer.sdk.AppRefer
import com.apprefer.sdk.AppReferContext
import com.apprefer.sdk.internal.AppReferStorage
import com.apprefer.sdk.support.SupportStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.KeyStore
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where lifecycle engagement stands on this install. */
sealed class AppReferEngagementStatus {
    /** [AppReferEngagement.start] hasn't run in this process. */
    object NotStarted : AppReferEngagementStatus() { override fun toString() = "NotStarted" }
    /** Registered with AppRefer and verified. */
    data class Active(val installId: String) : AppReferEngagementStatus()
    /** Offline, Keystore unavailable or a server error: retried on the next start or resume. */
    object Pending : AppReferEngagementStatus() { override fun toString() = "Pending" }
    /** Engagement is off for this app on the server; checked again after six hours. */
    object Disabled : AppReferEngagementStatus() { override fun toString() = "Disabled" }
}

/**
 * Options for [AppReferEngagement.start].
 * @property trackedEvents product events that carry the engagement credential (null = every event; `_` names never).
 * @property allowedImageHosts exact host names notification images may come from (empty = text only).
 * @property smallIcon a monochrome notification icon resource (0 = the app icon).
 * @property tapActivity the activity notification taps open (default: the launch activity).
 */
data class AppReferEngagementOptions(
    val trackedEvents: Set<String>? = null,
    val allowedImageHosts: Set<String> = emptySet(),
    val smallIcon: Int = 0,
    val tapActivity: Class<out android.app.Activity>? = null,
)

/** The person's own choices per purpose; separate from the notification permission and from tracking consent. */
data class AppReferEngagementPreferences(val marketing: Boolean, val reminders: Boolean, val service: Boolean)

/** Something that happened to an AppRefer notification, delivered once. */
data class AppReferEngagementNotificationEvent(
    val kind: Kind,
    val messageId: String,
    val purpose: String,
    /** In-app path for [Kind.OPENED] and [Kind.ACTION]; your app maps it to a screen (null = default screen). */
    val destination: String?,
    val actionId: String?,
    val actionKind: String?,
) {
    enum class Kind { RECEIVED, OPENED, ACTION, SNOOZED, DISMISSED }
    /** Navigation events wait for [AppReferEngagement.setNavigationReady] and are dropped after an account change. */
    val isNavigation: Boolean get() = kind == Kind.OPENED || kind == Kind.ACTION
}

class AppReferEngagementException(val reason: Reason, val status: Int? = null, val code: String? = null) : Exception(reason.name) {
    enum class Reason { NOT_STARTED, DISABLED, IDENTITY_CHANGED, INVALID_ASSERTION, UNAVAILABLE, SERVER }
}

/**
 * AppRefer lifecycle engagement on Android: registers this install (only when your app calls
 * [start]), keeps its push token, time zone, language and permission current, records the
 * person's own choices per purpose, binds the install to an account only with an assertion from
 * your backend, and renders AppRefer's notifications itself (purpose channels, image, buttons,
 * snooze) from your FirebaseMessagingService. Separate from Support: Support tokens and
 * `support_reply` messages never enroll anyone. Nothing here asks for a permission.
 *
 * ```kotlin
 * AppRefer.engagement.start(context, options = AppReferEngagementOptions(allowedImageHosts = setOf("assets.example.com"), smallIcon = R.drawable.ic_stat))
 * // FirebaseMessagingService:
 * override fun onMessageReceived(m: RemoteMessage) { if (AppRefer.engagement.handleMessage(this, m.data)) return; /* yours */ }
 * override fun onNewToken(token: String) { scope.launch { AppRefer.engagement.setPushToken(token) } }
 * // Launch activity onCreate/onNewIntent:
 * AppRefer.engagement.handleIntent(this, intent)
 * ```
 */
object AppReferEngagement {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    @Volatile internal var client: EngagementClient? = null
    @Volatile private var context: Context? = null
    @Volatile private var options = AppReferEngagementOptions()
    private val pending = mutableListOf<Pair<AppReferEngagementNotificationEvent, JSONObject>>()
    @Volatile private var ready = false

    /** Image downloads in [handleMessage]: at most this long (FCM allows about 20 s). */
    @JvmStatic var imageTimeoutMs: Int = 8_000
    /** Largest image accepted, in bytes. */
    @JvmStatic var maxImageBytes: Int = 300_000

    /** Opened notifications, buttons, snoozes, dismissals and receipts, each once, on the main thread. */
    @JvmStatic var onNotificationEvent: ((AppReferEngagementNotificationEvent) -> Unit)? = null
        set(value) { field = value; deliver() }

    @Synchronized internal fun client(context: Context): EngagementClient {
        client?.let { return it }
        val app = context.applicationContext
        this.context = app
        val prefs = app.getSharedPreferences("apprefer_engagement", Context.MODE_PRIVATE)
        return EngagementClient(
            credentials = KeystoreCredentialStore(app),
            prefs = object : EngagementPrefs {
                override fun getString(key: String) = prefs.getString(key, null)
                override fun putString(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit() }
            },
            deviceState = { deviceState(app) },
            deviceId = { AppRefer.supportDeviceId() },
            identitySink = { iid, epoch -> EngagementRenderer.setIdentity(app, iid, epoch) },
        ).also { client = it }
    }

    private fun deviceState(context: Context) = EngagementDeviceState(
        timezone = TimeZone.getDefault().id,
        locale = Locale.getDefault().toLanguageTag().takeIf { it.matches(Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$")) },
        collectionEnabled = runCatching { AppReferStorage(context).isCollectionEnabled() }.getOrDefault(true),
        permission = EngagementRenderer.permission(context),
    )

    /**
     * Registers this install for lifecycle engagement (once; the credential is encrypted with an
     * Android Keystore key) and verifies it, with the key passed to `AppRefer.configure` unless
     * you pass one. Creates the purpose channels with names in the app's language. Never prompts.
     */
    @JvmStatic @JvmOverloads
    suspend fun start(context: Context, apiKey: String? = null, options: AppReferEngagementOptions = AppReferEngagementOptions()): AppReferEngagementStatus {
        val key = apiKey ?: AppRefer.currentApiKey() ?: return AppReferEngagementStatus.NotStarted
        this.options = options
        val app = context.applicationContext
        val labels = EngagementRenderer.KINDS.associateWith { SupportStrings.text("engagement.action.$it") }
        val channels = EngagementRenderer.PURPOSES.associateWith { SupportStrings.text("engagement.channel.$it") }
        EngagementRenderer.configure(app, options.smallIcon, options.allowedImageHosts, labels, channels, options.tapActivity?.name)
        val status = client(app).start(key, apiKey != null, options)
        drain()
        return status
    }

    @JvmStatic fun status(): AppReferEngagementStatus = client?.status ?: AppReferEngagementStatus.NotStarted

    /** The FCM registration token from `onNewToken` / `FirebaseMessaging.getToken()`. */
    @JvmStatic suspend fun setPushToken(token: String?) { client?.setPushToken(token) }

    /** Re-reads permission, time zone and language (call after your own permission request). */
    @JvmStatic suspend fun refreshDeviceState() { client?.refreshDeviceState() }

    /** granted, denied or unknown (POST_NOTIFICATIONS on Android 13+). Never prompts. */
    @JvmStatic fun notificationPermission(context: Context): String = EngagementRenderer.permission(context)

    /** Whether the purpose's channel is on (Android 8+), null when unknown. */
    @JvmStatic fun channelEnabled(context: Context, purpose: String): Boolean? = EngagementRenderer.channelEnabled(context, purpose)

    @JvmStatic suspend fun preferences(): AppReferEngagementPreferences =
        (client ?: throw AppReferEngagementException(AppReferEngagementException.Reason.NOT_STARTED)).preferences()

    /** Saves the person's own choices (only the ones you pass) for the identity in use now. */
    @JvmStatic @JvmOverloads
    suspend fun setPreferences(marketing: Boolean? = null, reminders: Boolean? = null, service: Boolean? = null): AppReferEngagementPreferences {
        val c = client ?: throw AppReferEngagementException(AppReferEngagementException.Reason.NOT_STARTED)
        return c.setPreferences(buildMap { marketing?.let { put("marketing", it) }; reminders?.let { put("reminders", it) }; service?.let { put("service", it) } })
    }

    /** Binds this install to the signed-in account with an assertion from your backend; a user id alone is never accepted. */
    @JvmStatic suspend fun identify(assertion: String) {
        resetForAccountChange()
        (client ?: throw AppReferEngagementException(AppReferEngagementException.Reason.NOT_STARTED)).identify(assertion)
    }

    /** Sign-out (also done by `AppRefer.reset()`): messages planned for the account stop. Sent later if offline. */
    @JvmStatic suspend fun logout(context: Context? = this.context ?: AppReferContext.context) {
        resetForAccountChange()
        (client ?: context?.let { client(it) })?.logout()
    }

    /** Forgets this install's engagement registration here and on the server. */
    @JvmStatic suspend fun deleteInstall() { resetForAccountChange(); client?.deleteInstall() }

    private fun resetForAccountChange() {
        synchronized(pending) { pending.clear() }
        context?.let { EngagementRenderer.clearForAccountChange(it) }
    }

    /**
     * From your FirebaseMessagingService.onMessageReceived (a background thread): shows AppRefer's
     * notification and returns true; false for anything else (Support replies, your own). A
     * notification for another install or account, an expired one, or one whose purpose channel
     * is off is claimed but not shown. FCM gives the service about 20 s; the image download is
     * bounded by [imageTimeoutMs].
     */
    @JvmStatic fun handleMessage(context: Context, data: Map<String, String>): Boolean {
        val result = EngagementRenderer.handleMessage(context.applicationContext, data, System.currentTimeMillis(), imageTimeoutMs, maxImageBytes)
        if (result == EngagementRenderer.Result.NOT_ENGAGEMENT) return false
        drain()
        return true
    }

    /** From the launch activity's onCreate and onNewIntent: true when it was an AppRefer notification tap. */
    @JvmStatic fun handleIntent(context: Context, intent: Intent?): Boolean {
        val handled = EngagementRenderer.handleIntent(context.applicationContext, intent, System.currentTimeMillis())
        if (handled) drain()
        return handled
    }

    /** Call with true once your app knows who is signed in and can show screens (false while it can't). */
    @JvmStatic fun setNavigationReady(ready: Boolean) { this.ready = ready; deliver() }

    /** Moves the renderer's recorded outcomes into receipts and events. */
    internal fun drain() {
        val app = context ?: return
        val c = client ?: return
        val events = EngagementRenderer.takeEvents(app)
        if (events.isEmpty()) return
        for (e in events) {
            val kind = when (e.optString("kind")) {
                "received" -> AppReferEngagementNotificationEvent.Kind.RECEIVED
                "opened" -> AppReferEngagementNotificationEvent.Kind.OPENED
                "action" -> AppReferEngagementNotificationEvent.Kind.ACTION
                "snoozed" -> AppReferEngagementNotificationEvent.Kind.SNOOZED
                "dismissed" -> AppReferEngagementNotificationEvent.Kind.DISMISSED
                else -> continue
            }
            val event = AppReferEngagementNotificationEvent(kind, e.optString("messageId"), e.optString("purpose"),
                e.optString("destination").takeIf { e.has("destination") && EngagementRenderer.isDestination(it) },
                e.optString("actionId").takeIf { e.has("actionId") }, e.optString("actionKind").takeIf { e.has("actionKind") })
            val receiptKind = if (kind == AppReferEngagementNotificationEvent.Kind.SNOOZED) "snooze" else e.optString("kind")
            scope.launch {
                c.queueReceipt(receiptKind, e.optString("messageId"), e.optString("receiptToken"), e.optString("installId"),
                    e.optInt("accountEpoch", -1), event.actionId, e.optLong("at"))
            }
            synchronized(pending) { pending.add(event to e); while (pending.size > 20) pending.removeAt(0) }
        }
        deliver()
    }

    private fun deliver() {
        main.post {
            val handler = onNotificationEvent
            // A Support testimonial invitation belongs to the SDK: it opens even with no app handler.
            fun sdkOwned(e: AppReferEngagementNotificationEvent) = e.isNavigation && testimonialRequestId(e.destination) != null
            val now = synchronized(pending) {
                val (go, keep) = pending.partition { (handler != null || sdkOwned(it.first)) && (!it.first.isNavigation || ready) }
                pending.clear(); pending.addAll(keep); go
            }
            for ((event, raw) in now) {
                if (!stillDeliverable(event, raw, client?.snapshot?.get(), System.currentTimeMillis())) continue
                val requestId = if (event.isNavigation) testimonialRequestId(event.destination) else null
                if (requestId != null) openTestimonial(requestId) else handler?.invoke(event)
            }
        }
    }

    private val testimonialPath = Regex("^/apprefer/testimonials/(tsr_[0-9a-f]{32})$")

    /**
     * The request id when [destination] is a Support testimonial invitation
     * (`/apprefer/testimonials/<requestId>`): the SDK opens its own capture screen for it and never
     * passes it to the app's router.
     */
    internal fun testimonialRequestId(destination: String?): String? = destination?.let { testimonialPath.matchEntire(it)?.groupValues?.get(1) }

    /** Opens the capture screen for a tapped invitation (tests replace it). */
    internal var openTestimonial: (String) -> Unit = { requestId ->
        (context ?: AppReferContext.context)?.let { com.apprefer.sdk.support.AppReferTestimonials.present(it, requestId) }
    }

    /**
     * Re-checked at delivery, with the real clock: an event whose notification expired while it
     * waited (navigation not ready, no handler yet) is dropped, and navigation also needs the
     * same install and account it was tapped under.
     */
    internal fun stillDeliverable(event: AppReferEngagementNotificationEvent, raw: JSONObject, snap: EngagementSnapshot?, nowMs: Long): Boolean {
        if (!raw.has("expiresAt") || nowMs >= raw.optLong("expiresAt")) return false
        if (!event.isNavigation) return true
        return snap != null && snap.installId == raw.optString("installId") && snap.accountEpoch == raw.optInt("accountEpoch", -1)
    }

    /** The stamp a new product event carries (never the credential). */
    internal fun eventStamp(eventName: String, apiKey: String): String? {
        val snap = client?.snapshot?.get() ?: return null
        if (eventName.startsWith("_") || snap.accountEpoch == null || snap.slot != EngagementClient.slotFor(apiKey)) return null
        if (snap.trackedEvents != null && eventName !in snap.trackedEvents) return null
        return snap.stamp
    }

    /** The headers for a queued event, only while its stamp is current: the credential and the epoch it was queued under. */
    internal fun headersFor(stamp: String?): Map<String, String>? {
        val snap = client?.snapshot?.get() ?: return null
        val epoch = snap.accountEpoch ?: return null
        return if (stamp != null && snap.stamp == stamp) mapOf("X-AppRefer-Install" to snap.header, "X-AppRefer-Install-Epoch" to epoch.toString()) else null
    }

    internal fun configurationChanged(apiKey: String) { client?.let { c -> scope.launch { c.configurationChanged(apiKey) } } }
    internal fun becameActive() { client?.let { c -> scope.launch { c.becameActive(); drain() } } }
    internal fun collectionChanged() { client?.let { c -> scope.launch { c.refreshDeviceState() } } }
}

/** API 23+: AES-GCM with a Keystore key; API 21–22: an RSA Keystore key wraps the value in chunks. No-backup storage. */
internal class KeystoreCredentialStore(private val context: Context) : EngagementCredentialStore {
    private fun file(slot: String) = java.io.File(context.noBackupFilesDir, "apprefer-engagement-$slot")

    @android.annotation.TargetApi(23)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Suppress("DEPRECATION")
    private fun legacy(): KeyStore {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(LEGACY)) {
            val spec = android.security.KeyPairGeneratorSpec.Builder(context).setAlias(LEGACY)
                .setSubject(javax.security.auth.x500.X500Principal("CN=AppRefer Engagement")).setSerialNumber(java.math.BigInteger.ONE)
                .setStartDate(java.util.Date(0)).setEndDate(java.util.Date(4102444800000L)).build()
            java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        return ks
    }

    override fun read(slot: String): EngagementCredentialStore.Read {
        val f = file(slot)
        if (!f.exists()) return EngagementCredentialStore.Read.Missing
        val saved = try { f.readText() } catch (_: Exception) { return EngagementCredentialStore.Read.Unavailable }
        return try {
            val value = if (saved.startsWith("rsa-chunks:")) {
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, legacy().getKey(LEGACY, null)) }
                val out = java.io.ByteArrayOutputStream()
                saved.removePrefix("rsa-chunks:").split(".").forEach { out.write(cipher.doFinal(Base64.decode(it, Base64.NO_WRAP))) }
                String(out.toByteArray(), Charsets.UTF_8)
            } else {
                if (Build.VERSION.SDK_INT < 23) return EngagementCredentialStore.Read.Unavailable
                val bytes = Base64.decode(saved, Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8) }
            }
            EngagementCredentialStore.Read.Found(value)
        } catch (_: java.security.GeneralSecurityException) {
            // The key is gone (restored to another device, Keystore reset): this credential can never be read again.
            f.delete(); EngagementCredentialStore.Read.Missing
        } catch (_: Exception) {
            EngagementCredentialStore.Read.Unavailable
        }
    }

    override fun write(slot: String, value: String): Boolean = try {
        val text = if (Build.VERSION.SDK_INT < 23) {
            val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, legacy().getCertificate(LEGACY).publicKey) }
            "rsa-chunks:" + value.toByteArray(Charsets.UTF_8).toList().chunked(100).joinToString(".") { Base64.encodeToString(rsa.doFinal(it.toByteArray()), Base64.NO_WRAP) }
        } else {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            Base64.encodeToString(c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        val atomic = android.util.AtomicFile(file(slot))
        val out = atomic.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out); true } catch (e: Exception) { atomic.failWrite(out); false }
    } catch (_: Exception) { false }

    override fun delete(slot: String) { file(slot).delete() }

    private companion object { const val ALIAS = "apprefer_engagement"; const val LEGACY = "apprefer_engagement_legacy" }
}
