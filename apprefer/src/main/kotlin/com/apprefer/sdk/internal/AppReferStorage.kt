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
internal class AppReferStorage(context: Context) : DeviceIdStore {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(AppReferConfig.PREFS_NAME, Context.MODE_PRIVATE)

    fun getTrackingConsent(): String? = prefs.getString("apprefer_tracking_consent", null)
    fun setTrackingConsent(value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove("apprefer_tracking_consent")
        else editor.putString("apprefer_tracking_consent", value)
        editor.apply()
    }
    fun getReportedConsent(): String? = prefs.getString("apprefer_reported_tracking_consent", null)
    fun setReportedConsent(value: String) {
        safely(Unit) { prefs.edit().putString("apprefer_reported_tracking_consent", value).commit() }
    }

    fun clearUser() { safely(Unit) { prefs.edit().remove(KEY_USER_ID).commit() } }
    fun clearEventQuarantine() { safely(Unit) { prefs.edit().remove("apprefer_pending_events_corrupt").commit() } }
    fun quarantineEvents(raw: String) { if (!prefs.edit().putString("apprefer_pending_events_corrupt", raw).commit()) throw java.io.IOException("Queue quarantine failed") }
    var pendingEvents: String?
        get() = prefs.getString("apprefer_pending_events", null)
        set(value) { if (!prefs.edit().putString("apprefer_pending_events", value).commit()) throw java.io.IOException("Queue save failed") }
    var organicConfirmed: Boolean
        get() = prefs.getBoolean("apprefer_organic_confirmed", false)
        set(value) { safely(Unit) { prefs.edit().putBoolean("apprefer_organic_confirmed", value).commit() } }

    // region device_id

    /** Null when missing or unreadable; use [readDeviceId] to tell them apart. */
    fun getDeviceId(): String? = safely(null) { prefs.getString(KEY_DEVICE_ID, null) }

    /** Null when missing; throws when the value can't be read. */
    override fun readDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    /** Waits for the disk; false if the id was not saved. */
    override fun writeDeviceId(value: String): Boolean =
        safely(false) { prefs.edit().putString(KEY_DEVICE_ID, value).commit() }

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

    fun clearAttributionCache() { prefs.edit().remove(KEY_ATTRIBUTION_CACHE).apply() }

    fun getAttributionCache(): String? =
        safely(null) { prefs.getString(KEY_ATTRIBUTION_CACHE, null) }

    fun setAttributionCache(json: String) {
        safely(Unit) { prefs.edit().putString(KEY_ATTRIBUTION_CACHE, json).apply() }
    }

    // endregion

    // region sdk_enabled (kill switch, default true)

    @Volatile private var collectionPaused = false
    fun pauseCollection() { collectionPaused = true }
    fun resumeCollection() { collectionPaused = false }
    fun isCollectionEnabled(): Boolean = !collectionPaused && safely(false) { prefs.getBoolean("apprefer_collection_enabled", true) }
    /**
     * Turning off pauses at once; turning back on takes effect only on configure. An unchanged "on"
     * never pauses anything. Returns false when the choice couldn't be saved (never throws).
     */
    fun setCollectionEnabled(enabled: Boolean): Boolean {
        if (!enabled || collectionSavedOff()) collectionPaused = true
        return safely(false) { prefs.edit().putBoolean("apprefer_collection_enabled", enabled).commit() }
    }
    /** The app saved "off". */
    fun collectionSavedOff(): Boolean = safely(false) { !prefs.getBoolean("apprefer_collection_enabled", true) }
    /** Only a saved "off" or the server kill switch clears queued events, never a pause alone. */
    fun shouldDiscardEvents(): Boolean = collectionSavedOff() || !safely(true) { prefs.getBoolean(KEY_SDK_ENABLED, true) }
    fun isSdkEnabled(): Boolean =
        isCollectionEnabled() && safely(true) { prefs.getBoolean(KEY_SDK_ENABLED, true) }

    fun setSdkEnabled(value: Boolean) {
        if (!value) safely(Unit) { pendingEvents = null }
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
