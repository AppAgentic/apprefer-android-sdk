package com.apprefer.sdk

import android.content.Context
import com.apprefer.sdk.internal.AppReferStorage
import com.apprefer.sdk.internal.SafeRunner.safely

/**
 * Marks API that exists only so other AppRefer modules (AppRefer Billing) can share
 * the attribution SDK's key, device id and endpoints. Not for app code; may change
 * in any release.
 */
@RequiresOptIn(
    message = "Internal AppRefer API for AppRefer modules. It may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalAppReferApi

@InternalAppReferApi
object AppReferInternal {
    const val TRACKING_URL: String = AppReferConfig.TRACKING_URL
    const val FALLBACK_URL: String = AppReferConfig.FALLBACK_URL
    const val SDK_VERSION: String = APPREFER_SDK_VERSION

    fun trackingConsent(context: Context? = null): String? = AppRefer.currentTrackingConsent(context)

    /** The SDK key passed to [AppRefer.configure] in this process, or null before it runs. */
    fun apiKey(): String? = AppRefer.currentApiKey()

    /** The persisted attribution device id, even before [AppRefer.configure] runs this launch. */
    fun deviceId(context: Context): String? = safely(null) { AppReferStorage(context).getDeviceId() }

    /**
     * The device id other modules may send, under the rule Support follows: only after
     * [AppRefer.configure] in this process, never while collection is off (saved or not) and
     * never after [AppRefer.reset] until configure runs again. Null otherwise.
     */
    fun linkableDeviceId(): String? = AppRefer.supportDeviceId()

    /** True while [linkableDeviceId] may be sent. */
    fun installLinkAllowed(): Boolean = AppRefer.installLinkAllowed()

    /** Process-wide counter that advances on every [AppRefer.reset] (and nothing else). */
    fun accountEpoch(): Int = AppRefer.accountEpoch

    /**
     * AppRefer Billing's logIn/logOut set the user. [freshAnonymousId] is the anonymous id a
     * logOut minted (null for a logIn): a later [AppRefer.reset] does not mark it as the previous
     * account. [clearMarker] clears the persisted reset marker; it runs under the lock reset()'s
     * marker write takes, and a reset() that started before this call writes no marker.
     */
    /**
     * One package's store price for Support offer cards: the store's formatted regular price
     * (never a trial or introductory phase), product name, the store's billing period of a
     * subscription (ISO 8601, e.g. `P1Y`; null for one-time purchases) and whether it is one-time.
     */
    data class SupportStorePrice(val price: String, val name: String?, val period: String?, val oneTime: Boolean)

    /**
     * AppRefer Billing's store prices for Support offer cards: offering id -> package id -> price.
     * Set by AppRefer Billing's configure.
     */
    @Volatile var supportOfferPrices: (suspend (Set<String>) -> Map<String, Map<String, SupportStorePrice>>)? = null

    /** AppRefer Billing's entitlements changed (purchase, restore, sign-in): offer cards refresh. */
    fun billingChanged() = com.apprefer.sdk.support.SupportOffers.billingChanged()

    fun billingIdentitySet(freshAnonymousId: String?, clearMarker: () -> Unit) =
        com.apprefer.sdk.internal.BillingResetMarker.identitySet(freshAnonymousId, clearMarker)
}
