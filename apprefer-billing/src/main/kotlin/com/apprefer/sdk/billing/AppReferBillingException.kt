package com.apprefer.sdk.billing

/** Errors thrown by [AppReferBilling]. */
sealed class AppReferBillingException(message: String) : Exception(message) {

    /** `AppReferBilling.configure()` has not been called. */
    class NotConfigured : AppReferBillingException("AppReferBilling.configure() has not been called")

    /** No SDK key: pass `apiKey` to `AppReferBilling.configure` or call `AppRefer.configure` first. */
    class MissingApiKey : AppReferBillingException(
        "AppRefer SDK key missing: call AppRefer.configure(context, apiKey) or pass apiKey to AppReferBilling.configure",
    )

    /** AppRefer could not be reached. */
    class Network(val detail: String) : AppReferBillingException("AppRefer unreachable: $detail")

    /** AppRefer answered with an error status. */
    class Server(val status: Int, val serverMessage: String?) :
        AppReferBillingException("AppRefer error $status${serverMessage?.let { ": $it" } ?: ""}")

    /** AppRefer answered 2xx with a body the SDK could not read. */
    class InvalidResponse : AppReferBillingException("AppRefer returned an unreadable response")

    /** App user ids must be 1-256 characters with no whitespace. */
    class InvalidAppUserId : AppReferBillingException("Invalid app user id")

    /**
     * Google Play Billing returned an error. [responseCode] is a
     * `BillingClient.BillingResponseCode` (e.g. `ITEM_ALREADY_OWNED`, `BILLING_UNAVAILABLE`).
     */
    class Store(val responseCode: Int, val debugMessage: String?) :
        AppReferBillingException("Google Play Billing error $responseCode${debugMessage?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}")

    /** Another `purchase` call is still waiting for Google Play. */
    class PurchaseInProgress : AppReferBillingException("A purchase is already in progress")

    /**
     * Whether the same request may succeed later. Only malformed requests (400/413/422)
     * and invalid ids are final.
     */
    internal val isRetryable: Boolean
        get() = when (this) {
            is NotConfigured, is MissingApiKey, is Network, is InvalidResponse -> true
            is Server -> status !in PERMANENT_STATUSES
            is InvalidAppUserId, is Store, is PurchaseInProgress -> false
        }

    internal val isConnectivity: Boolean
        get() = when (this) {
            is Network, is MissingApiKey, is NotConfigured -> true
            is Server -> status >= 500
            else -> false
        }

    private companion object {
        val PERMANENT_STATUSES = setOf(400, 413, 422)
    }
}
