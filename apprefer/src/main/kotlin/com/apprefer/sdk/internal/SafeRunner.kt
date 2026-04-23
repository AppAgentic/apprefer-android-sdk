package com.apprefer.sdk.internal

import android.util.Log

/**
 * Crash-isolation helpers. The #1 rule: the SDK MUST NEVER crash the host app.
 *
 * Apply `safely { ... }` to every public entry point body, every listener callback,
 * and every code path that touches platform APIs we don't control (Install Referrer,
 * GAID, SharedPreferences, network).
 */
internal object SafeRunner {
    private const val TAG = "AppRefer"

    inline fun <T> safely(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "SDK caught exception — returning fallback", t)
            fallback
        }

    inline fun safelyRun(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "SDK caught exception", t)
        }
    }
}
