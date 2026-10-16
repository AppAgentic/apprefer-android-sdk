package com.apprefer.sdk

import com.apprefer.sdk.models.Attribution

enum class AppReferEventResult { SENT, QUEUED, DROPPED }
sealed class AppReferAttributionResult {
    internal companion object {
        fun resolve(attribution: Attribution?, organicConfirmed: Boolean, failed: Boolean): AppReferAttributionResult {
            if (failed) return Failed(IllegalStateException("Attribution unavailable"))
            if (attribution != null) return if (attribution.network == "organic") Organic else Attributed(attribution)
            if (organicConfirmed) return Organic
            return Failed(IllegalStateException("Attribution unavailable"))
        }
    }
    data class Attributed(val attribution: Attribution) : AppReferAttributionResult()
    data object Organic : AppReferAttributionResult()
    data class Failed(val error: Throwable) : AppReferAttributionResult()
}
