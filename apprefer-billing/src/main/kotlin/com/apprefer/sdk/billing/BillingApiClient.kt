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
    ) : BillingEndpoint() {
        // The purchase token is a secret: keep it out of logs.
        override fun toString() = "Purchase(appUserId=$appUserId, productId=$productId, productType=$productType)"
    }

    data class Identify(val anonymousId: String, val appUserId: String) : BillingEndpoint()
}

internal data class BillingRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
) {
    override fun toString() = "$method $url"
}

internal data class BillingHttpResponse(val status: Int, val body: String)

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
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
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
            BillingHttpResponse(status, text)
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
    suspend fun send(endpoint: BillingEndpoint): BillingCustomerResponse {
        val key = resolveApiKey() ?: throw AppReferBillingException.MissingApiKey()
        var lastError: AppReferBillingException = AppReferBillingException.Network("no endpoint")

        for (baseUrl in baseUrls) {
            val request = makeRequest(endpoint, baseUrl, key)
            val response = try {
                transport.send(request)
            } catch (e: AppReferBillingException) {
                lastError = e
                continue
            } catch (e: Exception) {
                lastError = AppReferBillingException.Network(e.javaClass.simpleName)
                continue
            }

            if (response.status in 200..299) return CustomerInfoJson.parseResponse(response.body)
            val message = try {
                JSONObject(response.body).opt("error") as? String
            } catch (_: Exception) {
                null
            }
            val error = AppReferBillingException.Server(response.status, message)
            if (response.status >= 500) {
                lastError = error
                continue
            }
            throw error
        }
        throw lastError
    }

    companion object {
        fun live(apiKey: () -> String?) = BillingApiClient(
            baseUrls = listOf(AppReferInternal.TRACKING_URL, AppReferInternal.FALLBACK_URL),
            apiKey = apiKey,
            transport = HttpUrlConnectionTransport(),
        )

        fun makeRequest(endpoint: BillingEndpoint, baseUrl: String, apiKey: String): BillingRequest {
            var method = "POST"
            var query = ""
            val path: String
            val body: JSONObject?

            when (endpoint) {
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
                }
                is BillingEndpoint.Identify -> {
                    path = "/api/billing/v1/identify"
                    body = JSONObject()
                        .put("anonymousId", endpoint.anonymousId)
                        .put("appUserId", endpoint.appUserId)
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
