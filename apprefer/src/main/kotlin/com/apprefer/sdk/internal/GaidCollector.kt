package com.apprefer.sdk.internal

import android.content.Context
import com.apprefer.sdk.internal.SafeRunner.safely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google Advertising ID collector. PORTED from
 * `sdk/flutter/android/src/main/kotlin/com/apprefer/sdk/AppReferPlugin.kt` lines 52–69.
 *
 * Semantics match Flutter exactly:
 *   - Must be called off the main thread (we enforce via `Dispatchers.IO`).
 *   - Returns null if Play Services is unavailable, if the user has limit-ad-tracking
 *     enabled, or if any exception is thrown.
 *
 * Reflection-based invocation keeps the compile-time dependency on
 * `com.google.android.gms:play-services-ads-identifier` soft — the call site still
 * works if the host app excluded the artifact, it just always returns null.
 */
internal object GaidCollector {

    suspend fun getGaid(context: Context, logger: Logger): String? = withContext(Dispatchers.IO) {
        safely<String?>(null) {
            val appCtx = context.applicationContext

            val clientCls = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
            val getInfo = clientCls.getMethod("getAdvertisingIdInfo", Context::class.java)
            val info = getInfo.invoke(null, appCtx) ?: return@safely null

            val infoCls = info.javaClass
            val isLatEnabled = infoCls.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean ?: false
            if (isLatEnabled) {
                logger.debug("GAID: limit ad tracking enabled — returning null")
                return@safely null
            }
            val id = infoCls.getMethod("getId").invoke(info) as? String
            logger.debug("GAID acquired: ${if (id.isNullOrBlank()) "<empty>" else "<redacted>"}")
            id?.takeIf { it.isNotBlank() }
        }
    }
}
