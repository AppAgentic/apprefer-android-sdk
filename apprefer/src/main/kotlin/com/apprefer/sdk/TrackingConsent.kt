package com.apprefer.sdk

/** Shared with AppRefer Billing. Null retains Android's legacy routing. */
object TrackingConsent {
    fun mapped(granted: Boolean?): String? = granted?.let { if (it) "granted" else "denied" }
    fun allowed(consent: String?): Boolean = consent == null || consent == "granted"
    fun hosts(consent: String?, legacy: List<String> = listOf(AppReferConfig.TRACKING_URL, AppReferConfig.FALLBACK_URL)): List<String> =
        if (consent == null) legacy else listOf(if (consent == "granted") AppReferConfig.TRACKING_URL else AppReferConfig.FALLBACK_URL)
}
