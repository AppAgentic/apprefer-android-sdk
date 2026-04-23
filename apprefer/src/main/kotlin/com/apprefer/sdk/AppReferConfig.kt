package com.apprefer.sdk

/**
 * Static SDK configuration. Mirrors iOS `AppReferConfig.swift`.
 *
 * URLs and the prefs file name are intentionally constants — there's no runtime
 * override (matches iOS posture). Staging dev workflows use a separate build flavor.
 */
internal object AppReferConfig {
    /** Primary tracking ingress. */
    const val TRACKING_URL: String = "https://trk.apprefer.com"

    /** Fallback when the primary ingress is unreachable. */
    const val FALLBACK_URL: String = "https://apprefer.com"

    /** SharedPreferences file name. */
    const val PREFS_NAME: String = "apprefer_prefs"

    /** HTTP header name carrying the SDK version on every request. */
    const val SDK_VERSION_HEADER: String = "X-SDK-Version"

    /** HTTP header name carrying the app's publishable API key. */
    const val API_KEY_HEADER: String = "X-AppRefer-Key"

    /** Per-request timeout for the HTTP client (milliseconds). */
    const val REQUEST_TIMEOUT_MS: Int = 10_000

    /** Max HTTP retries per base URL. */
    const val MAX_HTTP_RETRIES: Int = 3
}
