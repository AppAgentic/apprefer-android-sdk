package com.apprefer.sdk.billing

import android.util.Log

/**
 * Level-gated logger, same levels as the attribution SDK's `Logger`:
 * 0 silent, 1 errors (default), 2 info + warnings, 3 debug (only with `debug = true`).
 * Never log purchase tokens or customer attributes.
 */
internal class BillingLogger(private val debug: Boolean = false, private val logLevel: Int = 1) {
    fun info(message: String) {
        if (logLevel >= 2) Log.i(TAG, message)
    }

    fun warn(message: String) {
        if (logLevel >= 2) Log.w(TAG, message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (logLevel >= 1) {
            if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        }
    }

    fun debug(message: String) {
        if (debug && logLevel >= 3) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "AppRefer"
    }
}
