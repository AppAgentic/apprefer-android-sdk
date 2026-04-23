package com.apprefer.sdk.internal

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.apprefer.sdk.internal.SafeRunner.safely
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * One-shot Install Referrer retrieval using the Play `installreferrer` library.
 *
 * PORTED from `sdk/flutter/android/src/main/kotlin/com/apprefer/sdk/AppReferPlugin.kt`
 * (lines 71–180). The Flutter plugin's logic is production-tested across tens of
 * thousands of installs — we keep EVERY defensive pattern intact:
 *
 *   - AtomicBoolean `completed` guard prevents double-completion when the timeout
 *     races against a late success callback (some OEMs fire both).
 *   - 3× retry on SERVICE_DISCONNECTED / SERVICE_UNAVAILABLE (1 s backoff).
 *   - 5 s hard timeout — some OEMs never invoke the listener at all.
 *   - Explicit `endConnection()` on every client lifecycle branch (success,
 *     disconnect, retry, timeout). The library leaks the binding otherwise.
 *   - Try/catch around every library call — Play Services can throw synchronously.
 *
 * DO NOT "clean this up" — the defensive code earned its keep in prod.
 */
internal class InstallReferrerCollector(
    private val context: Context,
    private val logger: Logger,
) {

    /**
     * Returns the raw referrer data or null if retrieval failed / timed out.
     * The caller passes the whole map through to the server.
     */
    suspend fun collect(): ReferrerData? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val ctx = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            val completed = AtomicBoolean(false)
            var retryCount = 0
            val maxRetries = 3
            val timeoutMs = 5000L
            val retryDelayMs = 1000L
            // Track current client for cleanup on timeout
            var activeClient: InstallReferrerClient? = null
            // Track retry runnable for cancellation on timeout/completion
            var pendingRetryRunnable: Runnable? = null

            fun completeOnce(data: ReferrerData?) {
                if (completed.compareAndSet(false, true)) {
                    // Cancel any pending retry
                    pendingRetryRunnable?.let { handler.removeCallbacks(it) }
                    // Close active client if still connected
                    try { activeClient?.endConnection() } catch (_: Exception) {}
                    activeClient = null
                    if (cont.isActive) cont.resume(data)
                }
            }

            fun tryConnect() {
                if (completed.get()) return // Already completed (timeout or prior success)
                try {
                    val referrerClient = InstallReferrerClient.newBuilder(ctx).build()
                    activeClient = referrerClient
                    referrerClient.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            logger.debug(
                                "Install referrer response code: $responseCode (attempt ${retryCount + 1}/$maxRetries)"
                            )
                            when (responseCode) {
                                InstallReferrerClient.InstallReferrerResponse.OK -> {
                                    try {
                                        val details = referrerClient.installReferrer
                                        completeOnce(
                                            ReferrerData(
                                                installReferrer = details.installReferrer,
                                                referrerClickTimestampSeconds = details.referrerClickTimestampSeconds,
                                                installBeginTimestampSeconds = details.installBeginTimestampSeconds,
                                                referrerClickTimestampServerSeconds = details.referrerClickTimestampServerSeconds,
                                                installBeginTimestampServerSeconds = details.installBeginTimestampServerSeconds,
                                                installVersion = safely<String?>(null) { details.installVersion },
                                                googlePlayInstantParam = safely(false) { details.googlePlayInstantParam },
                                            )
                                        )
                                    } catch (e: Exception) {
                                        logger.error("Failed to read referrer details", e)
                                        completeOnce(null)
                                    }
                                }

                                InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE,
                                InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED -> {
                                    try { referrerClient.endConnection() } catch (_: Exception) {}
                                    activeClient = null
                                    retryCount++
                                    if (retryCount < maxRetries && !completed.get()) {
                                        logger.debug(
                                            "Retrying install referrer in ${retryDelayMs}ms (attempt ${retryCount + 1}/$maxRetries)"
                                        )
                                        val retryRunnable = Runnable { tryConnect() }
                                        pendingRetryRunnable = retryRunnable
                                        handler.postDelayed(retryRunnable, retryDelayMs)
                                    } else {
                                        logger.warn("Install referrer exhausted $maxRetries retries, returning null")
                                        completeOnce(null)
                                    }
                                }

                                else -> {
                                    logger.warn("Install referrer failed with response code: $responseCode")
                                    completeOnce(null)
                                }
                            }
                        }

                        override fun onInstallReferrerServiceDisconnected() {
                            // Service disconnected — retry if we have attempts left
                            logger.debug("Install referrer service disconnected")
                            try { referrerClient.endConnection() } catch (_: Exception) {}
                            activeClient = null
                            retryCount++
                            if (retryCount < maxRetries && !completed.get()) {
                                val retryRunnable = Runnable { tryConnect() }
                                pendingRetryRunnable = retryRunnable
                                handler.postDelayed(retryRunnable, retryDelayMs)
                            }
                            // If max retries exhausted, the timeout guard will complete
                        }
                    })
                } catch (e: Exception) {
                    // Client creation or startConnection threw synchronously
                    // (e.g., Play Services missing, SecurityException)
                    logger.error("Install referrer client error", e)
                    completeOnce(null)
                }
            }

            // Start first attempt
            tryConnect()

            // Timeout guard — ensure we always complete even if callback never fires
            handler.postDelayed({
                if (!completed.get()) {
                    logger.warn("Install referrer timed out after ${timeoutMs}ms")
                }
                completeOnce(null)
            }, timeoutMs)

            cont.invokeOnCancellation {
                // Coroutine cancelled — best-effort cleanup
                try { activeClient?.endConnection() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        /**
         * Extract `ar_click_id=...` from the raw referrer query string. Mirrors the
         * Flutter `extractClickId` helper in `install_referrer.dart`.
         */
        fun extractArClickId(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val decoded = try { Uri.decode(raw) } catch (_: Exception) { raw }
            return decoded.split("&")
                .firstOrNull { it.startsWith("ar_click_id=") }
                ?.substringAfter("=")
                ?.takeIf { it.isNotBlank() }
        }
    }
}

internal data class ReferrerData(
    val installReferrer: String?,
    val referrerClickTimestampSeconds: Long,
    val installBeginTimestampSeconds: Long,
    val referrerClickTimestampServerSeconds: Long,
    val installBeginTimestampServerSeconds: Long,
    val installVersion: String?,
    val googlePlayInstantParam: Boolean,
)
