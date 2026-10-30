package com.apprefer.sdk.billing

import com.apprefer.sdk.AppReferInternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Play product types as the server names them. */
internal object ProductTypes {
    const val SUBSCRIPTION = "subscription"
    const val CONSUMABLE = "consumable"
    const val NON_CONSUMABLE = "non_consumable"
}

internal sealed class BillingEndpoint {
    data class Offerings(val body: JSONObject) : BillingEndpoint()
    data class CreateCustomer(
        val appUserId: String,
        val appreferId: String?,
        val attributes: Map<String, String>,
    ) : BillingEndpoint()

    data class GetCustomer(val appUserId: String) : BillingEndpoint()

    data class Purchase(
        val appUserId: String,
        val appreferId: String?,
        val purchaseToken: String,
        val productId: String,
        /** [ProductTypes] value. */
        val productType: String,
        val attributes: Map<String, String>,
        /**
         * True only for Restore Purchases: lets the server move a purchase that already
         * belongs to another app user to this one. Purchase flows and Play's purchase
         * listener never set it.
         */
        val restore: Boolean = false,
        val presentedOfferingContext: PresentedOfferingContext? = null,
        val bucketId: String? = null,
    ) : BillingEndpoint() {
        // The purchase token is a secret: keep it out of logs.
        override fun toString() =
            "Purchase(appUserId=$appUserId, productId=$productId, productType=$productType, restore=$restore)"
    }

    /** [link] false: merge the purchases without copying the anonymous customer's install link. */
    data class Identify(val anonymousId: String, val appUserId: String, val link: Boolean = true) : BillingEndpoint()
}

internal data class BillingRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
) {
    override fun toString() = "$method $url"
}

internal data class BillingHttpResponse(
    val status: Int,
    val body: String,
    /** The raw `Retry-After` header, if any. */
    val retryAfter: String? = null,
)

/** Sends one HTTP request. Abstracted so request building and failover are testable. */
internal fun interface BillingTransport {
    /** Returns status and body; throws [AppReferBillingException.Network] when unreachable. */
    suspend fun send(request: BillingRequest): BillingHttpResponse
}

internal class HttpUrlConnectionTransport(private val timeoutMs: Int = 15_000) : BillingTransport {
    override suspend fun send(request: BillingRequest): BillingHttpResponse = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                useCaches = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                for ((k, v) in request.headers) setRequestProperty(k, v)
            }
            val body = request.body?.toByteArray(StandardCharsets.UTF_8)
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) } ?: ""
            BillingHttpResponse(status, text, conn.getHeaderField("Retry-After"))
        } catch (e: IOException) {
            throw AppReferBillingException.Network(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Client for `/api/billing/v1/` endpoints. Tries the tracking host first, then the main
 * host, like the attribution client.
 */
internal class BillingApiClient(
    baseUrls: List<String>,
    private val apiKey: () -> String?,
    private val transport: BillingTransport,
    /** 100 ms polls while waiting for the SDK key. */
    private val keyWaitAttempts: Int = 30,
    private val keyWaitDelayMs: Long = 100,
    private val now: () -> Long = System::currentTimeMillis,
    private val consentProvider: () -> String? = { AppReferInternal.trackingConsent() },
    private val environment: BillingEnvironment? = null,
    private val environmentProvider: (() -> BillingEnvironment?)? = null,
    private val proofEnvironment: ((String) -> BillingEnvironment?)? = null,
    private val recordProofEnvironment: ((String, BillingEnvironment) -> Unit)? = null,
) {
    val baseUrls: List<String> = baseUrls.distinct()

    /**
     * `AppRefer.configure` usually runs in a coroutine started just before
     * `AppReferBilling.configure`, so give it a moment to register the key.
     */
    private suspend fun resolveApiKey(): String? {
        for (attempt in 0..keyWaitAttempts) {
            apiKey()?.takeIf { it.isNotEmpty() }?.let { return it }
            if (attempt < keyWaitAttempts) delay(keyWaitDelayMs)
        }
        return null
    }

    /** Sends the request. 5xx and network errors fail over to the next host; 4xx are final. */
    suspend fun send(endpoint: BillingEndpoint): BillingCustomerResponse = CustomerInfoJson.parseResponse(sendRaw(endpoint))

    suspend fun sendRaw(endpoint: BillingEndpoint): String {
        val key = resolveApiKey() ?: throw AppReferBillingException.MissingApiKey()
        val selected = environmentProvider?.invoke() ?: environment
        if (environmentProvider != null && selected == null) throw AppReferBillingException.InvalidResponse()
        if (endpoint is BillingEndpoint.Purchase && selected != null) {
            proofEnvironment?.invoke(endpoint.purchaseToken)?.let { if (it != selected) throw AppReferBillingException.EnvironmentMismatch(it) }
        }
        var lastError: AppReferBillingException = AppReferBillingException.Network("no endpoint")

        for (baseUrl in com.apprefer.sdk.TrackingConsent.hosts(consentProvider(), baseUrls)) {
            val rawRequest = makeRequest(endpoint, baseUrl, key)
            if (key.startsWith("pk_test_") && selected == BillingEnvironment.PRODUCTION) throw AppReferBillingException.InvalidResponse()
            val request = if (selected == null) rawRequest else rawRequest.copy(headers = rawRequest.headers + ("X-AppRefer-Billing-Environment" to selected.raw))
            val response = try {
                transport.send(request)
            } catch (e: AppReferBillingException) {
                lastError = e
                continue
            } catch (e: Exception) {
                lastError = AppReferBillingException.Network(e.javaClass.simpleName)
                continue
            }

            if (response.status in 200..299) {
                if (endpoint is BillingEndpoint.Purchase && !isRecorded(response.body)) {
                    // Older servers answered 200 when the purchase was verified but not
                    // recorded; treat it like today's 503 so the retry copy is kept.
                    throw AppReferBillingException.Server(503, "Purchase verified but not recorded yet", DEFAULT_RETRY_AFTER_MS)
                }
                if (endpoint !is BillingEndpoint.Offerings && selected != null && !(try { JSONObject(response.body).optString("environment") == selected.raw } catch (_: Exception) { false })) throw AppReferBillingException.InvalidResponse()
                if (endpoint is BillingEndpoint.Purchase && selected != null) {
                    CustomerInfoJson.parseResponse(response.body)
                    recordProofEnvironment?.invoke(endpoint.purchaseToken, selected)
                }
                return response.body
            }
            if (endpoint is BillingEndpoint.Purchase && selected != null && response.status == 400) {
                val body = try { JSONObject(response.body) } catch (_: Exception) { null }
                val actual = BillingEnvironment.entries.firstOrNull { it.raw == body?.optString("environment") }
                if (body?.optString("code") == "billing_environment_mismatch" && actual != null) {
                    recordProofEnvironment?.invoke(endpoint.purchaseToken, actual)
                    throw AppReferBillingException.EnvironmentMismatch(actual)
                }
            }
            val message = try {
                JSONObject(response.body).opt("error") as? String
            } catch (_: Exception) {
                null
            }
            val error = AppReferBillingException.Server(response.status, message, parseRetryAfter(response.retryAfter, now()))
            if (response.status >= 500) {
                lastError = error
                continue
            }
            throw error
        }
        throw lastError
    }

    companion object {
        private const val DEFAULT_RETRY_AFTER_MS = 30_000L

        /** False only when a 2xx purchase answer says the ledger write failed. */
        private fun isRecorded(body: String): Boolean = try {
            JSONObject(body).optJSONObject("purchase")?.opt("conversion") != "failed"
        } catch (_: Exception) {
            true
        }

        /** `Retry-After` as delay-seconds or an HTTP date; null when absent or unreadable. */
        internal fun parseRetryAfter(raw: String?, nowMillis: Long): Long? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
            return try {
                val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("GMT")
                val at = format.parse(value)?.time ?: return null
                maxOf(0L, at - nowMillis)
            } catch (_: Exception) {
                null
            }
        }

        fun live(context: android.content.Context? = null, environmentProvider: (() -> BillingEnvironment?)? = null, proofEnvironment: ((String) -> BillingEnvironment?)? = null, recordProofEnvironment: ((String, BillingEnvironment) -> Unit)? = null, apiKey: () -> String?) = BillingApiClient(
            baseUrls = listOf(AppReferInternal.TRACKING_URL, AppReferInternal.FALLBACK_URL),
            apiKey = apiKey,
            transport = HttpUrlConnectionTransport(),
            environmentProvider = environmentProvider,
            proofEnvironment = proofEnvironment,
            recordProofEnvironment = recordProofEnvironment,
            // With collection off or after reset, never the tracking host.
            consentProvider = { if (AppReferInternal.installLinkAllowed()) AppReferInternal.trackingConsent(context) else "denied" },
        )

        fun makeRequest(endpoint: BillingEndpoint, baseUrl: String, apiKey: String): BillingRequest {
            var method = "POST"
            var query = ""
            val path: String
            val body: JSONObject?

            when (endpoint) {
                is BillingEndpoint.Offerings -> {
                    path = "/api/billing/v1/offerings"
                    body = endpoint.body
                }
                is BillingEndpoint.CreateCustomer -> {
                    path = "/api/billing/v1/customers"
                    body = JSONObject().put("appUserId", endpoint.appUserId)
                    endpoint.appreferId?.let { body.put("appreferId", it) }
                    if (endpoint.attributes.isNotEmpty()) body.put("attributes", JSONObject(endpoint.attributes))
                }
                is BillingEndpoint.GetCustomer -> {
                    path = "/api/billing/v1/customers"
                    method = "GET"
                    query = "?appUserId=" + URLEncoder.encode(endpoint.appUserId, "UTF-8")
                    body = null
                }
                is BillingEndpoint.Purchase -> {
                    path = "/api/billing/v1/purchases"
                    body = JSONObject()
                        .put("appUserId", endpoint.appUserId)
                        .put("store", "play_store")
                        .put("purchaseToken", endpoint.purchaseToken)
                        .put("productId", endpoint.productId)
                        .put("productType", endpoint.productType)
                    endpoint.appreferId?.let { body.put("appreferId", it) }
                    if (endpoint.attributes.isNotEmpty()) body.put("attributes", JSONObject(endpoint.attributes))
                    if (endpoint.restore) body.put("restore", true)
                    endpoint.presentedOfferingContext?.let { body.put("presentedOfferingContext", it.json()) }
                    endpoint.bucketId?.let { body.put("bucketId", it) }
                }
                is BillingEndpoint.Identify -> {
                    path = "/api/billing/v1/identify"
                    body = JSONObject()
                        .put("anonymousId", endpoint.anonymousId)
                        .put("appUserId", endpoint.appUserId)
                        .put("link", endpoint.link)
                }
            }

            val headers = LinkedHashMap<String, String>()
            headers["X-AppRefer-Key"] = apiKey
            headers["X-SDK-Version"] = AppReferInternal.SDK_VERSION
            headers["Accept"] = "application/json"
            if (body != null) headers["Content-Type"] = "application/json"
            return BillingRequest(method, baseUrl + path + query, headers, body?.toString())
        }
    }
}
