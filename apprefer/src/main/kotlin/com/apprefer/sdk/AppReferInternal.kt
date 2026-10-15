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
}
