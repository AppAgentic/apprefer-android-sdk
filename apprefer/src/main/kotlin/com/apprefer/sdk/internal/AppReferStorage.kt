package com.apprefer.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import com.apprefer.sdk.AppReferConfig
import com.apprefer.sdk.internal.SafeRunner.safely

/**
 * SharedPreferences-backed persistent storage. Plain prefs (not EncryptedSharedPreferences)
 * per plan §9 / §16 — the device ID is a pseudonymous identifier, not a secret, and
 * EncryptedSharedPreferences has known reliability issues on some OEMs.
 *
 * Keys mirror iOS (see `sdk/ios/Sources/AppRefer/Services/Storage.swift`).
 */
internal class AppReferStorage(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(AppReferConfig.PREFS_NAME, Context.MODE_PRIVATE)

    // region device_id

    fun getDeviceId(): String? = safely(null) { prefs.getString(KEY_DEVICE_ID, null) }

    fun setDeviceId(value: String) {
        safely(Unit) { prefs.edit().putString(KEY_DEVICE_ID, value).apply() }
    }

    // endregion

    // region first_run_date (ISO8601 string)

    /**
     * Returns the first-run date. Writes now if missing (same as iOS behavior).
     */
    fun getFirstRunDate(): String = safely(nowIso()) {
        val existing = prefs.getString(KEY_FIRST_RUN_DATE, null)
        if (existing != null) return@safely existing
        val now = nowIso()
        prefs.edit().putString(KEY_FIRST_RUN_DATE, now).apply()
        now
    }

    // endregion

    // region match_request_attempted

    fun isMatchRequestAttempted(): Boolean =
        safely(false) { prefs.getBoolean(KEY_MATCH_REQUEST_ATTEMPTED, false) }

    fun setMatchRequestAttempted(value: Boolean) {
        safely(Unit) { prefs.edit().putBoolean(KEY_MATCH_REQUEST_ATTEMPTED, value).apply() }
    }

    // endregion

    // region install_event_sent

    fun isInstallEventSent(): Boolean =
        safely(false) { prefs.getBoolean(KEY_INSTALL_EVENT_SENT, false) }

    fun setInstallEventSent(value: Boolean) {
        safely(Unit) { prefs.edit().putBoolean(KEY_INSTALL_EVENT_SENT, value).apply() }
    }

    // endregion

    // region attribution_cache (JSON string)

    fun getAttributionCache(): String? =
        safely(null) { prefs.getString(KEY_ATTRIBUTION_CACHE, null) }

    fun setAttributionCache(json: String) {
        safely(Unit) { prefs.edit().putString(KEY_ATTRIBUTION_CACHE, json).apply() }
    }

    // endregion

    // region sdk_enabled (kill switch, default true)

    fun isSdkEnabled(): Boolean =
        safely(true) { prefs.getBoolean(KEY_SDK_ENABLED, true) }

    fun setSdkEnabled(value: Boolean) {
        safely(Unit) { prefs.edit().putBoolean(KEY_SDK_ENABLED, value).apply() }
    }

    // endregion

    // region last_config_fetch

    fun setLastConfigFetch(iso: String) {
        safely(Unit) { prefs.edit().putString(KEY_LAST_CONFIG_FETCH, iso).apply() }
    }

    fun getLastConfigFetch(): String? =
        safely(null) { prefs.getString(KEY_LAST_CONFIG_FETCH, null) }

    // endregion

    // region user_id

    fun getUserId(): String? = safely(null) { prefs.getString(KEY_USER_ID, null) }

    fun setUserId(value: String) {
        safely(Unit) { prefs.edit().putString(KEY_USER_ID, value).apply() }
    }

    // endregion

    private fun nowIso(): String = safely(System.currentTimeMillis().toString()) {
        // ISO 8601 UTC — matches iOS ISO8601DateFormatter default
        val ms = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        date.format(java.util.Date(ms))
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_FIRST_RUN_DATE = "first_run_date"
        private const val KEY_MATCH_REQUEST_ATTEMPTED = "match_request_attempted"
        private const val KEY_INSTALL_EVENT_SENT = "install_event_sent"
        private const val KEY_ATTRIBUTION_CACHE = "attribution_cache"
        private const val KEY_SDK_ENABLED = "sdk_enabled"
        private const val KEY_LAST_CONFIG_FETCH = "last_config_fetch"
        private const val KEY_USER_ID = "user_id"
    }
}
