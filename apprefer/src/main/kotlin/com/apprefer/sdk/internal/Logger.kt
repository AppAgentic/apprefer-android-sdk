package com.apprefer.sdk.internal

import android.util.Log

/**
 * Level-gated logger. Mirrors iOS `AppReferLogger.swift`.
 *
 * Levels:
 *   0 — silent
 *   1 — error only (default)
 *   2 — info + warn + error
 *   3 — debug + verbose (DEBUG BUILDS ONLY — gated by [debug] flag)
 *
 * NEVER log raw PII. Email/phone/name/DOB must never appear in any log line.
 * The hashing layer operates on the input and only hashed values leave the device —
 * but defense-in-depth: if you're tempted to add a log like
 *   `logger.debug("setAdvancedMatching(email=$email)")` — STOP. Never do that.
 */
internal class Logger(private val debug: Boolean, private val logLevel: Int) {

    fun info(message: String) {
        if (logLevel >= 2) {
            Log.i(TAG, message)
        }
    }

    fun warn(message: String) {
        if (logLevel >= 2) {
            Log.w(TAG, message)
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (logLevel >= 1) {
            if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        }
    }

    fun debug(message: String) {
        if (debug && logLevel >= 3) {
            Log.d(TAG, message)
        }
    }

    fun verbose(message: String) {
        if (debug && logLevel >= 3) {
            Log.v(TAG, message)
        }
    }

    companion object {
        private const val TAG = "AppRefer"
    }
}
