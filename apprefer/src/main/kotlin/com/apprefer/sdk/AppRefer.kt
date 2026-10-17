package com.apprefer.sdk

import android.content.Context
import com.apprefer.sdk.internal.AppReferStorage
import com.apprefer.sdk.internal.BillingResetMarker
import com.apprefer.sdk.internal.DeviceIdManager
import com.apprefer.sdk.internal.DeviceInfo
import com.apprefer.sdk.internal.GaidCollector
import com.apprefer.sdk.internal.Hashing
import com.apprefer.sdk.internal.HttpClient
import com.apprefer.sdk.internal.InstallReferrerCollector
import com.apprefer.sdk.internal.JsonCodec
import com.apprefer.sdk.internal.Logger
import com.apprefer.sdk.internal.SafeRunner.safely
import com.apprefer.sdk.internal.SafeRunner.safelyRun
import com.apprefer.sdk.models.Attribution
import com.apprefer.sdk.internal.EventQueue
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * AppRefer — mobile attribution for Android.
 *
 * Single public entry point. Mirrors iOS `AppRefer.swift` and Flutter
 * `AppReferSDK` behavior. Every public method is wrapped in `runCatching` /
 * `safely { }`; this SDK MUST NEVER crash the host app.
 *
 * Usage:
 * ```
 * val attribution = AppRefer.configure(context, apiKey = "pk_live_...")
 * ```
 */
object AppRefer {
    /** Lifecycle engagement: `AppRefer.engagement.start(context)`. */
    @JvmStatic val engagement: com.apprefer.sdk.engagement.AppReferEngagement get() = com.apprefer.sdk.engagement.AppReferEngagement
    internal const val ENGAGEMENT_STAMP = "_apprefer_engagement_stamp"
    /** iOS-only SKAdNetwork / AdAttributionKit conversion values. No-op on Android. */
    @JvmStatic
    fun updateConversionValue() = Unit

    val support: com.apprefer.sdk.support.AppReferSupport get() = com.apprefer.sdk.support.AppReferSupport

    // One global singleton-ish state, protected for concurrent access.
    @Volatile private var state: SdkState? = null
    private val initMutex = Mutex()
    private val configurations by lazy { com.apprefer.sdk.internal.ConfigureCoordinator(scope) }
    private var eventQueue: EventQueue? = null
    @Volatile private var generation = 0
    @Volatile private var configureFailed = false
    /**
     * Set by reset(): attribution sends nothing (consent re-reports, events, user id, advanced
     * matching) and Support sends no device id until configure runs again for the next account.
     */
    @Volatile internal var stoppedByReset = false
    /** "Off" was chosen in this process but couldn't be saved: the next configure must not resume. */
    @Volatile internal var collectionOffUnsaved = false
    /**
     * Advances on every reset() and nothing else, so AppRefer Billing can tell which account
     * its user belongs to (the install link goes only to the Billing user set after the last reset).
     */
    @Volatile internal var accountEpoch = 0
    private val consentReporter = com.apprefer.sdk.internal.ConsentReporter()
    private var pendingConsentSet = false
    private var pendingConsent: String? = null

    /** Persist the Android app's choice. Null restores legacy behaviour. May precede configure. */
    @JvmStatic
    fun setTrackingConsent(granted: Boolean?) {
        synchronized(this) {
            pendingConsentSet = true
            pendingConsent = TrackingConsent.mapped(granted)
            safelyRun { state?.storage?.setTrackingConsent(pendingConsent) }
        }
        trackingConsentDidChange()
    }

    /** Retry a changed consent independently of first-open attribution. */
    @JvmStatic
    fun trackingConsentDidChange() {
        scope.launch { safelyRun { state?.let { reportConsent(it) } } }
    }

    internal fun currentTrackingConsent(context: Context? = null): String? = synchronized(this) {
        if (state != null) state!!.storage.getTrackingConsent()
        else if (pendingConsentSet) pendingConsent
        else context?.let { AppReferStorage(it).getTrackingConsent() }
    }

    private suspend fun reportConsent(s: SdkState) = safelyRun {
        val epoch = generation
        consentReporter.report(
            eligible = { epoch == generation && !stoppedByReset && s.storage.isSdkEnabled() && s.storage.isMatchRequestAttempted() },
            disabled = { s.storage.setSdkEnabled(false) },
            pending = { cachedAttribution(s)?.let { it.trackingDecisionPending && it.matchType != "adservices" } == true },
            current = { s.storage.getTrackingConsent() },
            reported = { s.storage.getReportedConsent() },
            send = { consent ->
                val deviceId = s.deviceIdManager.getOrCreate()
                if (deviceId == null) null else {
                    val body = linkedMapOf<String, Any?>(
                        "device_id" to deviceId, "tracking_consent" to consent,
                        "device_info" to s.deviceInfo.collect(), "sdk_version" to APPREFER_SDK_VERSION,
                    )
                    if (TrackingConsent.allowed(consent)) {
                        body["gaid"] = GaidCollector.getGaid(s.appContext, s.logger)
                    }
                    if (s.storage.getReportedConsent() == null) {
                        val referrer = s.installReferrer.collect()
                        body["install_referrer"] = referrer?.installReferrer
                        body["ar_click_id"] = InstallReferrerCollector.extractArClickId(referrer?.installReferrer)
                        body["referrer_click_ts"] = referrer?.referrerClickTimestampSeconds
                        body["referrer_install_ts"] = referrer?.installBeginTimestampSeconds
                        body["install_version"] = referrer?.installVersion
                        body["google_play_instant_param"] = referrer?.googlePlayInstantParam
                    }
                    s.storage.getUserId()?.let { body["customer_user_id"] = it }
                    if (epoch != generation) null else {
                        val response = s.http.post("/api/track/configure", body, consent, true)
                        if (epoch == generation) configureFailed = response == null
                        response
                    }
                }
            },
            refresh = { response ->
                check(epoch == generation) { "Account reset" }
                configureFailed = false
                if (response["organic"] == true || (response.containsKey("attribution") && response["attribution"] == null)) s.storage.organicConfirmed = true
                @Suppress("UNCHECKED_CAST")
                val attr = response["attribution"] as? Map<String, Any?>
                val attribution = attr?.let { JsonCodec.parseAttribution(it) }
                if (attribution != null) s.storage.setAttributionCache(JsonCodec.encodeAttribution(attribution))
                else s.storage.clearAttributionCache()
            },
            save = { s.storage.setReportedConsent(it) },
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Internal bundle of everything wired up during `configure()`. */
    private class SdkState(
        val apiKey: String,
        val debug: Boolean,
        val logLevel: Int,
        val storage: AppReferStorage,
        val logger: Logger,
        val http: HttpClient,
        val deviceIdManager: DeviceIdManager,
        val deviceInfo: DeviceInfo,
        val installReferrer: InstallReferrerCollector,
        val appContext: Context,
    ) {
        lateinit var events: EventQueue
    }

    @JvmOverloads suspend fun reset(context: Context? = AppReferContext.context) = withContext(Dispatchers.IO) {
        safelyRun {
            // Billing logIn/logOut from here on win over this reset's marker write.
            val billingIdentity = BillingResetMarker.generation()
            synchronized(this@AppRefer) { generation++; accountEpoch++; stoppedByReset = true }
            scope.coroutineContext.cancelChildren()
            state?.http?.cancel()
            val c = context ?: state?.appContext
            if (eventQueue == null && c != null) {
                val storage = AppReferStorage(c)
                eventQueue = EventQueue({ storage.pendingEvents }, { storage.pendingEvents = it }, { true }, { null }, scope, held = { stoppedByReset })
            }
            eventQueue?.clear()
            c?.let { AppReferStorage(it).apply { clearUser(); clearEventQuarantine() } }
            // Persisted so Billing keeps the previous account unlinked across a relaunch too.
            c?.let { BillingResetMarker.mark(it, billingIdentity) }
            support.reset(c)
            // Lifecycle engagement: the account's queued messages stop, nothing waits to navigate.
            com.apprefer.sdk.engagement.AppReferEngagement.logout(c)
        }
    }

    /** Persistent collection control. Enabling resumes on the next configure. Support/Billing are unaffected. */
    @JvmStatic
    @JvmOverloads
    suspend fun setCollectionEnabled(enabled: Boolean, context: Context? = AppReferContext.context) = safelyRun {
        val storage = state?.storage ?: context?.let { AppReferStorage(it) }
        // Already on: a consent tool reporting "granted" on every launch changes nothing.
        if (enabled && !collectionOffUnsaved && storage?.isCollectionEnabled() != false) return@safelyRun
        // Saved and paused in one step, so a configure can't start in between and read the old value.
        val saved = synchronized(this) {
            val saved = storage?.setCollectionEnabled(enabled) ?: false
            generation++
            if (!enabled) { storage?.pauseCollection(); state?.http?.cancel() }
            collectionOffUnsaved = !enabled && !saved
            saved
        }
        if (!saved) android.util.Log.w("AppRefer", "Collection setting could not be saved; applied for this session")
        if (!enabled) {
            // Even when saving failed: stop and clear now, erring on the side of not collecting.
            scope.coroutineContext.cancelChildren()
            clearPendingEvents(context)
        }
        // Lifecycle engagement keeps sending its own state; the server stops SDK-event journeys.
        com.apprefer.sdk.engagement.AppReferEngagement.collectionChanged()
    }

    @JvmOverloads suspend fun clearPendingEvents(context: Context? = AppReferContext.context) = withContext(Dispatchers.IO) {
        safelyRun {
            state?.http?.cancelEvents()
            if (eventQueue != null) eventQueue?.clear()
            else (context ?: state?.appContext)?.let { AppReferStorage(it).pendingEvents = null }
            (context ?: state?.appContext)?.let { AppReferStorage(it).clearEventQuarantine() }
        }
    }

    suspend fun configureResult(context: Context, apiKey: String, userId: String? = null,
        debug: Boolean = false, logLevel: Int = 1): AppReferAttributionResult {
        val attribution = configure(context, apiKey, userId, debug, logLevel)
        return AppReferAttributionResult.resolve(attribution, state?.storage?.organicConfirmed == true, configureFailed)
    }

    // region public API

    /**
     * Configure the SDK and resolve attribution.
     *
     * On first launch: sends device signals + Install Referrer to backend,
     * resolves attribution, caches locally, returns result.
     * On subsequent launches: reports changed consent, then returns cached attribution.
     */
    suspend fun configure(
        context: Context,
        apiKey: String,
        userId: String? = null,
        debug: Boolean = false,
        logLevel: Int = 1,
    ): Attribution? = safely<Attribution?>(null) {
        val accountEpoch = generation
        configurations.run(listOf(apiKey, userId, debug, logLevel, accountEpoch)) { safely<Attribution?>(null) {
        val s = ensureState(context, apiKey, debug, logLevel)
        // Lifecycle engagement started without its own key follows this one.
        com.apprefer.sdk.engagement.AppReferEngagement.configurationChanged(apiKey)
        synchronized(this) { if (accountEpoch == generation) stoppedByReset = false }
        if (accountEpoch != generation) return@safely null
        // Stored even while collection is off, so Support uses this account, not the last one.
        if (userId != null) synchronized(this) { if (accountEpoch == generation) safelyRun { s.storage.setUserId(userId); support.identityChanged() } }
        val collecting = synchronized(this) {
            if (accountEpoch != generation) return@safely null
            if (collectionOffUnsaved) { s.storage.pauseCollection(); false }
            else { s.storage.resumeCollection(); s.storage.isCollectionEnabled() }
        }
        if (!collecting) return@safely cachedAttribution(s)
        scope.launch { safelyRun { s.events.flush() } }

        s.logger.info("AppReferSDK initialized v$APPREFER_SDK_VERSION")

        // Record first run date
        safelyRun { s.storage.getFirstRunDate() }

        if (s.storage.isMatchRequestAttempted()) reportConsent(s)

        if (!s.storage.isSdkEnabled()) {
            s.logger.info("SDK disabled by kill switch")
            return@safely cachedAttribution(s)
        }

        if (s.storage.isMatchRequestAttempted()) {
            s.logger.info("Skipping match request: existing install detected.")
            return@safely cachedAttribution(s)
        }

        if (accountEpoch != generation) return@safely null
        return@safely resolveAttribution(s)
    } } }

    /** Java-friendly callback variant. */
    @JvmStatic
    @JvmOverloads
    fun configure(
        context: Context,
        apiKey: String,
        userId: String? = null,
        debug: Boolean = false,
        logLevel: Int = 1,
        callback: AppReferCallback<Attribution?>,
    ) {
        scope.launch {
            try {
                val result = configure(context, apiKey, userId, debug, logLevel)
                safelyRun { callback.onResult(result) }
            } catch (t: Throwable) {
                safelyRun { callback.onError(t) }
            }
        }
    }

    /**
     * Track a non-purchase event (signup, tutorial_complete, etc.).
     * Purchases go through RevenueCat webhooks — NOT this method.
     *
     * Respects the kill switch (`sdk_enabled=false` → no-op).
     * Safe to call pre-`configure()` (logs + returns).
     * Never throws — the SDK MUST NEVER crash the host app.
     */
    suspend fun trackEvent(
        eventName: String, properties: Map<String, Any?>? = null,
        revenue: Double? = null, currency: String? = null,
    ) { trackEventResult(eventName, properties, revenue, currency) }

    suspend fun trackEventResult(
        eventName: String, properties: Map<String, Any?>? = null,
        revenue: Double? = null, currency: String? = null,
    ): AppReferEventResult {
        val s = state ?: return AppReferEventResult.DROPPED
        // After reset() nothing is queued until configure runs for the next account: those events
        // come before the new user consents, so they must never be sent later.
        if (stoppedByReset) return AppReferEventResult.DROPPED
        val userId = s.storage.getUserId() // captured before dispatcher hop
        val eventEpoch = s.events.generationSnapshot()
        return withContext(Dispatchers.IO) { safely(AppReferEventResult.DROPPED) {
        val deviceId = s.storage.getDeviceId() ?: return@safely AppReferEventResult.DROPPED
        val body = linkedMapOf<String, Any?>("device_id" to deviceId, "event_name" to eventName)
        if (!eventName.startsWith("_")) userId?.let { body["app_user_id"] = it }
        properties?.let { body["properties"] = it }
        revenue?.let { body["revenue"] = it }
        currency?.let { body["currency"] = it }
        // Only a stamp is queued with the event; the credential is added at send time while it is current.
        com.apprefer.sdk.engagement.AppReferEngagement.eventStamp(eventName, s.apiKey)?.let { body[ENGAGEMENT_STAMP] = it }
        s.events.enqueue(body, eventEpoch)
    } }
    }

    @JvmStatic
    @JvmOverloads
    fun trackEvent(
        eventName: String,
        properties: Map<String, Any?>? = null,
        revenue: Double? = null,
        currency: String? = null,
        callback: AppReferCallback<Unit>? = null,
    ) {
        scope.launch {
            try {
                trackEvent(eventName, properties, revenue, currency)
                safelyRun { callback?.onResult(Unit) }
            } catch (t: Throwable) {
                safelyRun { callback?.onError(t) }
            }
        }
    }

    /**
     * Meta Advanced Matching: send SHA256-hashed user PII to improve CAPI match rates.
     * Call once after signup / login. All values are hashed ON-DEVICE before leaving
     * the phone — see `internal/Hashing.kt`.
     *
     * Respects the kill switch. Safe to call pre-`configure()`.
     */
    suspend fun setAdvancedMatching(
        email: String? = null,
        phone: String? = null,
        firstName: String? = null,
        lastName: String? = null,
        dateOfBirth: String? = null,
    ) {
        safelyRun {
            val s = state
            if (s == null) {
                android.util.Log.i("AppRefer", "setAdvancedMatching called before configure() — ignoring")
                return@safelyRun
            }
            if (!s.storage.isSdkEnabled() || stoppedByReset) return@safelyRun

            if (!TrackingConsent.allowed(s.storage.getTrackingConsent())) return@safelyRun
            val hashed = LinkedHashMap<String, String>()
            if (email != null) safelyRun { hashed["em"] = Hashing.hashEmail(email) }
            if (phone != null) safelyRun { hashed["ph"] = Hashing.hashPhone(phone) }
            if (firstName != null) safelyRun { hashed["fn"] = Hashing.hashName(firstName) }
            if (lastName != null) safelyRun { hashed["ln"] = Hashing.hashName(lastName) }
            if (dateOfBirth != null) safelyRun { hashed["db"] = Hashing.hashDateOfBirth(dateOfBirth) }

            if (hashed.isEmpty()) return@safelyRun

            val deviceId = safely<String?>(null) { s.storage.getDeviceId() } ?: run {
                s.logger.error("setAdvancedMatching: no device_id — did configure() run?")
                return@safelyRun
            }

            val body = LinkedHashMap<String, Any?>().apply {
                put("device_id", deviceId)
                put("event_name", "_advanced_matching")
                put("advanced_matching", hashed)
            }

            s.logger.info("Sending advanced matching data")
            safelyRun {
                withContext(Dispatchers.IO) { s.http.post("/api/track/event", body) }
            }
        }
    }

    /** Java-friendly callback overload for setAdvancedMatching. */
    @JvmStatic
    @JvmOverloads
    fun setAdvancedMatching(
        email: String? = null,
        phone: String? = null,
        firstName: String? = null,
        lastName: String? = null,
        dateOfBirth: String? = null,
        callback: AppReferCallback<Unit>? = null,
    ) {
        scope.launch {
            try {
                setAdvancedMatching(email, phone, firstName, lastName, dateOfBirth)
                safelyRun { callback?.onResult(Unit) }
            } catch (t: Throwable) {
                safelyRun { callback?.onError(t) }
            }
        }
    }

    /**
     * Set the customer user ID (e.g. RevenueCat `app_user_id`) so webhook events can
     * be linked back to this device's attribution.
     *
     * Persists locally AND syncs to server via `_set_user_id` event so the server-side
     * userId fallback lookup can find this attribution by user ID on webhook delivery.
     *
     * Respects the kill switch for the server sync, but ALWAYS writes to local storage
     * (so the next configure() call picks up the userId even if offline).
     */
    suspend fun setUserId(userId: String) {
        safelyRun {
            val s = state
            if (s == null) {
                support.setUserId(userId)
                return@safelyRun
            }

            safelyRun { s.storage.setUserId(userId); support.identityChanged() }
            s.logger.info("User ID set")

            if (!s.storage.isSdkEnabled() || stoppedByReset) return@safelyRun

            val deviceId = safely<String?>(null) { s.storage.getDeviceId() } ?: run {
                s.logger.error("setUserId: no device_id — did configure() run?")
                return@safelyRun
            }

            val body = LinkedHashMap<String, Any?>().apply {
                put("device_id", deviceId)
                put("event_name", "_set_user_id")
                put("properties", mapOf("user_id" to userId))
            }

            safelyRun {
                withContext(Dispatchers.IO) { s.http.post("/api/track/event", body) }
            }
        }
    }

    /** Java-friendly callback overload for setUserId. */
    @JvmStatic
    @JvmOverloads
    fun setUserId(
        userId: String,
        callback: AppReferCallback<Unit>? = null,
    ) {
        scope.launch {
            try {
                setUserId(userId)
                safelyRun { callback?.onResult(Unit) }
            } catch (t: Throwable) {
                safelyRun { callback?.onError(t) }
            }
        }
    }

    /** Get cached attribution (no network). */
    @JvmStatic
    fun getAttribution(): Attribution? = safely<Attribution?>(null) {
        val s = state ?: return@safely null
        cachedAttribution(s)
    }

    /** Get AppRefer device ID (may be null pre-configure). */
    @JvmStatic
    fun getDeviceId(): String? = safely<String?>(null) {
        state?.storage?.getDeviceId()
    }

    // endregion

    // region internals

    /** SDK key passed to `configure` in this process (read by AppRefer Billing). */
    internal fun currentApiKey(): String? = state?.apiKey

    /**
     * The install device id Support and AppRefer Billing may send: only after configure in this process, never after
     * reset() (until configure runs again) or while collection is off. Support-only apps never
     * configure attribution, so they never send it.
     */
    internal fun supportDeviceId(): String? = safely<String?>(null) {
        val s = state ?: return@safely null
        if (installLinkAllowed()) s.storage.getDeviceId() else null
    }

    /** Support and AppRefer Billing may link requests to this install (see [supportDeviceId]). */
    internal fun installLinkAllowed(): Boolean = safely(false) {
        val s = state ?: return@safely false
        !stoppedByReset && !collectionOffUnsaved && s.storage.isCollectionEnabled()
    }

    private suspend fun ensureState(
        context: Context,
        apiKey: String,
        debug: Boolean,
        logLevel: Int,
    ): SdkState = initMutex.withLock {
        val existing = state
        if (existing != null) return@withLock existing

        val appCtx = context.applicationContext
        val logger = Logger(debug = debug, logLevel = logLevel)
        val storage = AppReferStorage(appCtx)
        val http = HttpClient(apiKey = apiKey, logger = logger, consentProvider = { storage.getTrackingConsent() }, collectionAllowed = { storage.isSdkEnabled() && !stoppedByReset && !collectionOffUnsaved })
        val deviceIdManager = DeviceIdManager(appCtx, storage, logger)
        val deviceInfo = DeviceInfo(appCtx, logger)
        val installReferrer = InstallReferrerCollector(appCtx, logger)

        val s = SdkState(
            apiKey = apiKey,
            debug = debug,
            logLevel = logLevel,
            storage = storage,
            logger = logger,
            http = http,
            deviceIdManager = deviceIdManager,
            deviceInfo = deviceInfo,
            installReferrer = installReferrer,
            appContext = appCtx,
        )
        com.apprefer.sdk.support.AppReferSupport.configure(appCtx)
        synchronized(this) {
            if (pendingConsentSet) storage.setTrackingConsent(pendingConsent)
            AppReferContext.context = context.applicationContext
            val read = { s.storage.pendingEvents }
            val write: (String?) -> Unit = { s.storage.pendingEvents = it }
            val enabled = { s.storage.isSdkEnabled() }
            val send: suspend (Map<String, Any?>) -> Any? = s.http::eventResponse
            val discard = { s.storage.shouldDiscardEvents() }
            if (eventQueue == null) eventQueue = EventQueue(read, write, enabled, send, scope, quarantine = s.storage::quarantineEvents, held = { stoppedByReset }, discard = discard)
            else { state?.http?.cancelEvents(); eventQueue!!.replace(read, write, enabled, send, s.storage::quarantineEvents, discard) }
            s.events = eventQueue!!
            state = s
            support.resumeRevocations()
        }
        (appCtx as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                trackingConsentDidChange()
                scope.launch { safelyRun { s.events.flush() } }
                com.apprefer.sdk.engagement.AppReferEngagement.becameActive()
            }
            override fun onActivityCreated(activity: android.app.Activity, bundle: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, bundle: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        s
    }

    private fun cachedAttribution(s: SdkState): Attribution? = safely(null) {
        val raw = s.storage.getAttributionCache() ?: return@safely null
        JsonCodec.parseAttribution(JsonCodec.decodeObject(raw))
    }

    private suspend fun resolveAttribution(s: SdkState): Attribution? = coroutineScope {
        val epoch = generation
        val consent = s.storage.getTrackingConsent()
        // Fan out the slow calls in parallel. Matches iOS/Flutter behavior.
        val deviceIdJob = async { safely<String?>(null) { s.deviceIdManager.getOrCreate() } }
        val deviceInfoJob = async { safely(mapOf<String, Any?>("platform" to "android")) { s.deviceInfo.collect() } }
        val referrerJob = async { safely(null) { s.installReferrer.collect() } }
        val gaidJob = async { if (TrackingConsent.allowed(consent)) safely(null) { GaidCollector.getGaid(s.appContext, s.logger) } else null }

        val results = awaitAll(deviceIdJob, deviceInfoJob, referrerJob, gaidJob)
        val deviceId = results[0] as String? ?: run {
            // Stored id unreadable: a new one would split this device's attribution.
            // Not marked attempted, so the next launch retries.
            s.logger.error("Device ID unavailable — configure request skipped, will retry on next launch")
            return@coroutineScope null
        }
        @Suppress("UNCHECKED_CAST")
        val deviceInfoMap = results[1] as Map<String, Any?>
        val referrer = results[2] as com.apprefer.sdk.internal.ReferrerData?
        val gaid = results[3] as String?

        val arClickId = safely<String?>(null) {
            InstallReferrerCollector.extractArClickId(referrer?.installReferrer)
        }

        val body = LinkedHashMap<String, Any?>().apply {
            put("device_id", deviceId)
            put("device_info", deviceInfoMap)
            put("sdk_version", APPREFER_SDK_VERSION)
            put("is_debug", s.debug)
            // Android-specific fields — match Flutter plugin body shape:
            if (TrackingConsent.allowed(consent)) put("gaid", gaid)
            if (consent != null) put("tracking_consent", consent)
            put("install_referrer", referrer?.installReferrer)
            put("ar_click_id", arClickId)
            put("referrer_click_ts", referrer?.referrerClickTimestampSeconds)
            put("referrer_install_ts", referrer?.installBeginTimestampSeconds)
            // Flutter-parity fields (server currently drops them, but we ship what
            // the production plugin ships — CEO directive: "reuse it all").
            put("install_version", referrer?.installVersion)
            put("google_play_instant_param", referrer?.googlePlayInstantParam)
            // customer_user_id only set if the host previously called setUserId / passed
            // userId to configure(). Null-safe — keep the key out entirely to keep the
            // payload tight (server treats missing and null identically).
            s.storage.getUserId()?.let { put("customer_user_id", it) }
        }

        s.logger.info("Sending configure request...")
        if (epoch != generation) { configureFailed = true; return@coroutineScope null }
        val response = withContext(Dispatchers.IO) { s.http.post("/api/track/configure", body, consent, true) }

        if (epoch != generation) { configureFailed = true; return@coroutineScope null }
        configureFailed = response == null
        if (response == null) {
            // Do NOT set matchRequestAttempted so next launch retries — matches Flutter
            // (and is stricter than iOS which marks attempted even on fail).
            s.logger.error("Configure request failed — will retry on next launch")
            return@coroutineScope null
        }

        if (response["organic"] == true || (response.containsKey("attribution") && response["attribution"] == null)) s.storage.organicConfirmed = true
        val sdkEnabled = (response["sdkEnabled"] as? Boolean) ?: true
        s.storage.setSdkEnabled(sdkEnabled)
        if (!sdkEnabled) {
            s.logger.info("SDK disabled by server")
            s.storage.setMatchRequestAttempted(true)
            return@coroutineScope null
        }

        if (consent != null) s.storage.setReportedConsent(consent)

        @Suppress("UNCHECKED_CAST")
        val attrMap = response["attribution"] as? Map<String, Any?>
        val attribution: Attribution? = attrMap?.let { JsonCodec.parseAttribution(it) }

        if (attribution != null) {
            s.storage.setAttributionCache(JsonCodec.encodeAttribution(attribution))
            s.logger.info("Attribution resolved: ${attribution.network} via ${attribution.matchType}")
        } else {
            s.logger.info("No attribution (organic install)")
        }

        s.storage.setMatchRequestAttempted(true)
        s.storage.setInstallEventSent(true)
        s.storage.setLastConfigFetch(
            java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                java.util.Locale.US,
            ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        )

        reportConsent(s)
        cachedAttribution(s) ?: attribution
    }

    // endregion
}
