package com.apprefer.sdk

import android.content.Context
import com.apprefer.sdk.internal.AppReferStorage
import com.apprefer.sdk.internal.DeviceIdManager
import com.apprefer.sdk.internal.DeviceInfo
import com.apprefer.sdk.internal.GaidCollector
import com.apprefer.sdk.internal.HttpClient
import com.apprefer.sdk.internal.InstallReferrerCollector
import com.apprefer.sdk.internal.JsonCodec
import com.apprefer.sdk.internal.Logger
import com.apprefer.sdk.internal.SafeRunner.safely
import com.apprefer.sdk.internal.SafeRunner.safelyRun
import com.apprefer.sdk.models.Attribution
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

    // One global singleton-ish state, protected for concurrent access.
    @Volatile private var state: SdkState? = null
    private val initMutex = Mutex()
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
    )

    // region public API

    /**
     * Configure the SDK and resolve attribution.
     *
     * On first launch: sends device signals + Install Referrer to backend,
     * resolves attribution, caches locally, returns result.
     * On subsequent launches: returns cached attribution (no network call).
     */
    suspend fun configure(
        context: Context,
        apiKey: String,
        userId: String? = null,
        debug: Boolean = false,
        logLevel: Int = 1,
    ): Attribution? = safely<Attribution?>(null) {
        val s = ensureState(context, apiKey, debug, logLevel)

        s.logger.info("AppReferSDK initialized v$APPREFER_SDK_VERSION")

        if (userId != null) safelyRun { s.storage.setUserId(userId) }

        // Record first run date
        safelyRun { s.storage.getFirstRunDate() }

        if (!s.storage.isSdkEnabled()) {
            s.logger.info("SDK disabled by kill switch")
            return@safely cachedAttribution(s)
        }

        if (s.storage.isMatchRequestAttempted()) {
            s.logger.info("Skipping match request: existing install detected.")
            return@safely cachedAttribution(s)
        }

        return@safely resolveAttribution(s)
    }

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
     * Track a non-purchase event. Purchases go through RevenueCat webhooks.
     *
     * PHASE 4 STUB — wiring exists, body is a no-op until event endpoint is wired.
     * Safe to call pre-configure (logs + returns).
     */
    suspend fun trackEvent(
        eventName: String,
        properties: Map<String, Any?>? = null,
        revenue: Double? = null,
        currency: String? = null,
    ) {
        safelyRun {
            val s = state
            if (s == null) {
                android.util.Log.i("AppRefer", "trackEvent($eventName) called before configure() — ignoring")
                return@safelyRun
            }
            if (!s.storage.isSdkEnabled()) return@safelyRun
            s.logger.info("trackEvent($eventName) [phase-4 stub — not yet wired]")
            // Phase 4 will POST to /api/track/event. Keeping the method on the public
            // surface in 0.4.x so sample apps don't need to change.
        }
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

    /** PHASE 4 stub — see trackEvent. */
    suspend fun setAdvancedMatching(
        email: String? = null,
        phone: String? = null,
        firstName: String? = null,
        lastName: String? = null,
        dateOfBirth: String? = null,
    ) {
        safelyRun {
            val s = state ?: return@safelyRun
            s.logger.info("setAdvancedMatching [phase-4 stub]")
        }
    }

    /** PHASE 4 stub — see trackEvent. */
    suspend fun setUserId(userId: String) {
        safelyRun {
            val s = state ?: return@safelyRun
            s.storage.setUserId(userId)
            s.logger.info("User ID set")
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
        val http = HttpClient(apiKey = apiKey, logger = logger)
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
        state = s
        s
    }

    private fun cachedAttribution(s: SdkState): Attribution? = safely(null) {
        val raw = s.storage.getAttributionCache() ?: return@safely null
        JsonCodec.parseAttribution(JsonCodec.decodeObject(raw))
    }

    private suspend fun resolveAttribution(s: SdkState): Attribution? = coroutineScope {
        // Fan out the slow calls in parallel. Matches iOS/Flutter behavior.
        val deviceIdJob = async { safely(s.deviceIdManager.getOrCreate()) { s.deviceIdManager.getOrCreate() } }
        val deviceInfoJob = async { safely(mapOf<String, Any?>("platform" to "android")) { s.deviceInfo.collect() } }
        val referrerJob = async { safely(null) { s.installReferrer.collect() } }
        val gaidJob = async { safely(null) { GaidCollector.getGaid(s.appContext, s.logger) } }

        val results = awaitAll(deviceIdJob, deviceInfoJob, referrerJob, gaidJob)
        val deviceId = results[0] as String
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
            put("gaid", gaid)
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
        val response = withContext(Dispatchers.IO) { s.http.post("/api/track/configure", body) }

        if (response == null) {
            // Do NOT set matchRequestAttempted so next launch retries — matches Flutter
            // (and is stricter than iOS which marks attempted even on fail).
            s.logger.error("Configure request failed — will retry on next launch")
            return@coroutineScope null
        }

        val sdkEnabled = (response["sdkEnabled"] as? Boolean) ?: true
        s.storage.setSdkEnabled(sdkEnabled)
        if (!sdkEnabled) {
            s.logger.info("SDK disabled by server")
            s.storage.setMatchRequestAttempted(true)
            return@coroutineScope null
        }

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

        attribution
    }

    // endregion
}
