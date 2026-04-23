package com.apprefer.sdk

import android.content.Context
import android.util.Log
import com.apprefer.sdk.models.Attribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AppRefer — mobile attribution for Android.
 *
 * Phase 1 skeleton: all public methods exist with correct signatures but are no-ops.
 * `configure()` returns organic attribution so sample apps compile end-to-end.
 *
 * See docs/plans/android-sdk/overview.md §5 for the full API spec.
 */
object AppRefer {

    private const val TAG = "AppRefer"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun configure(
        context: Context,
        apiKey: String,
        userId: String? = null,
        debug: Boolean = false,
        logLevel: Int = 1,
    ): Attribution? = runCatching {
        Log.i(TAG, "configure() called [skeleton — organic fallback] v$APPREFER_SDK_VERSION")
        Attribution.organic()
    }.getOrElse {
        Log.e(TAG, "configure() failed", it)
        null
    }

    fun configure(
        context: Context,
        apiKey: String,
        userId: String? = null,
        debug: Boolean = false,
        logLevel: Int = 1,
        callback: AppReferCallback<Attribution?>,
    ) {
        scope.launch {
            runCatching { callback.onResult(configure(context, apiKey, userId, debug, logLevel)) }
                .onFailure { runCatching { callback.onError(it) } }
        }
    }

    suspend fun trackEvent(
        eventName: String,
        properties: Map<String, Any?>? = null,
        revenue: Double? = null,
        currency: String? = null,
    ) {
        runCatching {
            Log.i(TAG, "trackEvent($eventName) [skeleton no-op]")
        }
    }

    fun trackEvent(
        eventName: String,
        properties: Map<String, Any?>? = null,
        revenue: Double? = null,
        currency: String? = null,
        callback: AppReferCallback<Unit>? = null,
    ) {
        scope.launch {
            runCatching { trackEvent(eventName, properties, revenue, currency); callback?.onResult(Unit) }
                .onFailure { err -> runCatching { callback?.onError(err) } }
        }
    }

    suspend fun setAdvancedMatching(
        email: String? = null,
        phone: String? = null,
        firstName: String? = null,
        lastName: String? = null,
        dateOfBirth: String? = null,
    ) {
        runCatching { Log.i(TAG, "setAdvancedMatching [skeleton no-op]") }
    }

    suspend fun setUserId(userId: String) {
        runCatching { Log.i(TAG, "setUserId [skeleton no-op]") }
    }

    fun getAttribution(): Attribution? = null

    fun getDeviceId(): String? = null
}
