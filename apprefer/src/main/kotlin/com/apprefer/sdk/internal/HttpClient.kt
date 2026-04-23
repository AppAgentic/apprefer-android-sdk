package com.apprefer.sdk.internal

import com.apprefer.sdk.APPREFER_SDK_VERSION
import com.apprefer.sdk.AppReferConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * `HttpURLConnection`-based JSON POST client. Mirrors iOS
 * `sdk/ios/Sources/AppRefer/Services/HTTPClient.swift`:
 *
 *   - Primary URL first, then fallback on full failure.
 *   - 3 retries with exponential backoff (2^n seconds) on 5xx / network errors.
 *   - No retry on 4xx.
 *   - 10 s per-request timeout.
 *   - Headers: Content-Type, X-SDK-Version, X-AppRefer-Key.
 */
internal class HttpClient(
    private val apiKey: String,
    private val logger: Logger,
    private val primaryBaseUrl: String = AppReferConfig.TRACKING_URL,
    private val fallbackBaseUrl: String? = AppReferConfig.FALLBACK_URL,
) {

    /**
     * POST [body] to [path], return decoded JSON object or null on total failure.
     */
    suspend fun post(path: String, body: Map<String, Any?>): Map<String, Any?>? = withContext(Dispatchers.IO) {
        val payload = JsonCodec.encode(body).toByteArray(StandardCharsets.UTF_8)

        performPost(primaryBaseUrl, path, payload)?.let { return@withContext it }

        val fb = fallbackBaseUrl
        if (!fb.isNullOrBlank() && fb != primaryBaseUrl) {
            logger.info("Primary URL failed, retrying on fallback: $fb$path")
            performPost(fb, path, payload)?.let { return@withContext it }
        }

        logger.error("POST $path failed on all endpoints")
        null
    }

    private suspend fun performPost(baseUrl: String, path: String, body: ByteArray): Map<String, Any?>? {
        val urlString = "$baseUrl$path"
        val url = try { URL(urlString) } catch (e: Exception) {
            logger.error("Invalid URL: $urlString", e); return null
        }

        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) {
                val delayMs = (1L shl attempt) * 1000L // 2s, 4s, 8s — matches iOS 2^attempt
                logger.debug("POST $path retry $attempt after ${delayMs}ms")
                delay(delayMs)
            }

            val result = doPost(url, body, path, attempt)
            when (result.outcome) {
                Outcome.SUCCESS -> return result.json
                Outcome.CLIENT_ERROR -> return null // 4xx — don't retry or fall through
                Outcome.SERVER_ERROR, Outcome.NETWORK_ERROR -> continue
            }
        }

        logger.error("POST $path failed after $MAX_RETRIES attempts on $baseUrl")
        return null
    }

    private fun doPost(url: URL, body: ByteArray, path: String, attempt: Int): HttpResult {
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-SDK-Version", APPREFER_SDK_VERSION)
                setRequestProperty("X-AppRefer-Key", apiKey)
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }

            logger.debug("POST $url")

            conn.outputStream.use { it.write(body); it.flush() }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.use { s ->
                s.readBytes().toString(StandardCharsets.UTF_8)
            } ?: ""

            return when {
                code in 200..299 -> {
                    val parsed = JsonCodec.decodeObject(respBody)
                    HttpResult(Outcome.SUCCESS, parsed)
                }
                code in 400..499 -> {
                    logger.error("POST $path failed: $code ${respBody.take(500)}")
                    HttpResult(Outcome.CLIENT_ERROR, null)
                }
                else -> {
                    logger.error("POST $path server error: $code (attempt ${attempt + 1}/$MAX_RETRIES)")
                    HttpResult(Outcome.SERVER_ERROR, null)
                }
            }
        } catch (e: IOException) {
            logger.error("POST $path network error (attempt ${attempt + 1}/$MAX_RETRIES): ${e.javaClass.simpleName}")
            return HttpResult(Outcome.NETWORK_ERROR, null)
        } catch (e: Exception) {
            logger.error("POST $path exception", e)
            return HttpResult(Outcome.CLIENT_ERROR, null) // don't retry unknown errors
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private enum class Outcome { SUCCESS, CLIENT_ERROR, SERVER_ERROR, NETWORK_ERROR }

    private data class HttpResult(val outcome: Outcome, val json: Map<String, Any?>?)

    companion object {
        private const val MAX_RETRIES = 3
        private const val TIMEOUT_MS = 10_000
    }
}
