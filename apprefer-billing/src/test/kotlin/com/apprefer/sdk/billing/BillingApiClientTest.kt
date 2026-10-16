package com.apprefer.sdk.billing

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BillingApiClientTest {

    private val base = "https://trk.apprefer.com"

    @Test
    fun createCustomerRequest() {
        val r = BillingApiClient.makeRequest(
            BillingEndpoint.CreateCustomer("user_1", "device-1", mapOf("\$email" to "a@b.com")), base, "pk_live_x",
        )
        assertEquals("POST", r.method)
        assertEquals("$base/api/billing/v1/customers", r.url)
        assertEquals("pk_live_x", r.headers["X-AppRefer-Key"])
        assertEquals("0.10.0", r.headers["X-SDK-Version"])
        assertEquals("application/json", r.headers["Content-Type"])
        val body = JSONObject(r.body!!)
        assertEquals("user_1", body.getString("appUserId"))
        assertEquals("device-1", body.getString("appreferId"))
        assertEquals("a@b.com", body.getJSONObject("attributes").getString("\$email"))
    }

    @Test
    fun createCustomerOmitsEmptyOptionalFields() {
        val body = JSONObject(BillingApiClient.makeRequest(BillingEndpoint.CreateCustomer("u", null, emptyMap()), base, "k").body!!)
        assertEquals(setOf("appUserId"), body.keys().asSequence().toSet())
    }

    @Test
    fun getCustomerEncodesAnonymousIdAndPlus() {
        val r = BillingApiClient.makeRequest(BillingEndpoint.GetCustomer("\$ARAnon:AB-12"), base, "k")
        assertEquals("GET", r.method)
        assertNull(r.body)
        assertEquals("$base/api/billing/v1/customers?appUserId=%24ARAnon%3AAB-12", r.url)
        val plus = BillingApiClient.makeRequest(BillingEndpoint.GetCustomer("a+b@c.com"), base, "k")
        assertTrue(plus.url.endsWith("appUserId=a%2Bb%40c.com"))
    }

    @Test
    fun purchaseRequest() {
        val r = BillingApiClient.makeRequest(
            BillingEndpoint.Purchase("user_1", "device-1", "tok-123", "pro_monthly", ProductTypes.SUBSCRIPTION, emptyMap()),
            base, "k",
        )
        assertEquals("$base/api/billing/v1/purchases", r.url)
        val body = JSONObject(r.body!!)
        assertEquals("play_store", body.getString("store"))
        assertEquals("tok-123", body.getString("purchaseToken"))
        assertEquals("pro_monthly", body.getString("productId"))
        assertEquals("subscription", body.getString("productType"))
        assertEquals("device-1", body.getString("appreferId"))
        assertFalse(body.has("attributes"))
        assertFalse(r.toString().contains("tok-123"))
    }

    @Test
    fun identifyRequest() {
        val r = BillingApiClient.makeRequest(BillingEndpoint.Identify("\$ARAnon:x", "user_1"), base, "k")
        assertEquals("$base/api/billing/v1/identify", r.url)
        val body = JSONObject(r.body!!)
        assertEquals("\$ARAnon:x", body.getString("anonymousId"))
        assertEquals("user_1", body.getString("appUserId"))
        assertTrue("0.9.2: link defaults to true", body.getBoolean("link"))
        val closed = JSONObject(BillingApiClient.makeRequest(BillingEndpoint.Identify("\$ARAnon:x", "user_1", link = false), base, "k").body!!)
        assertFalse(closed.getBoolean("link"))
    }

    private fun client(key: () -> String? = { "k" }, handler: (BillingRequest) -> BillingHttpResponse) =
        RecordingClient(key, handler)

    private class RecordingClient(key: () -> String?, val handler: (BillingRequest) -> BillingHttpResponse) {
        val hosts = mutableListOf<String>()
        val api = BillingApiClient(
            baseUrls = listOf("https://trk.test", "https://main.test", "https://trk.test"),
            apiKey = key,
            transport = { r -> hosts += r.url.substringBefore("/api"); handler(r) },
            keyWaitAttempts = 3,
            keyWaitDelayMs = 1,
        )
    }

    private val ok = BillingHttpResponse(200, """{"appUserId":"u","entitlements":{},"activeSubscriptions":[],"requestDateMs":1,"accountToken":"0f8fad5b-d9cb-469f-a165-70867728950e"}""")

    @Test
    fun fallsBackToMainHostWhenTrackingHostIsDown() = runTest {
        val c = client { r -> if (r.url.startsWith("https://trk")) throw AppReferBillingException.Network("down") else ok }
        val response = c.api.send(BillingEndpoint.GetCustomer("u"))
        assertEquals(listOf("https://trk.test", "https://main.test"), c.hosts)
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", response.accountToken)
        assertEquals(AppReferCustomerInfo.Source.SERVER, response.info.source)
    }

    @Test
    fun fallsBackOn5xx() = runTest {
        val c = client { r -> if (r.url.startsWith("https://trk")) BillingHttpResponse(503, "") else ok }
        c.api.send(BillingEndpoint.GetCustomer("u"))
        assertEquals(2, c.hosts.size)
    }

    @Test
    fun clientErrorIsFinalAndNotRetried() = runTest {
        val c = client { BillingHttpResponse(400, """{"error":"appUserId is required"}""") }
        try {
            c.api.send(BillingEndpoint.GetCustomer("u"))
            fail()
        } catch (e: AppReferBillingException.Server) {
            assertEquals(400, e.status)
            assertEquals("appUserId is required", e.serverMessage)
            assertFalse(e.isRetryable)
        }
        assertEquals(1, c.hosts.size)
    }

    @Test
    fun allHostsDownThrowsRetryableError() = runTest {
        val c = client { throw AppReferBillingException.Network("down") }
        try {
            c.api.send(BillingEndpoint.GetCustomer("u"))
            fail()
        } catch (e: AppReferBillingException) {
            assertTrue(e.isRetryable)
            assertTrue(e.isConnectivity)
        }
        assertEquals(2, c.hosts.size) // duplicate base URL removed
    }

    @Test
    fun unreadable2xxIsInvalidResponse() = runTest {
        val c = client { BillingHttpResponse(200, "<html>") }
        try {
            c.api.send(BillingEndpoint.GetCustomer("u"))
            fail()
        } catch (e: AppReferBillingException.InvalidResponse) {
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun missingApiKeyMakesNoRequest() = runTest {
        val c = client(key = { null }) { ok }
        try {
            c.api.send(BillingEndpoint.GetCustomer("u"))
            fail()
        } catch (e: AppReferBillingException.MissingApiKey) {
        }
        assertTrue(c.hosts.isEmpty())
    }

    @Test
    fun waitsBrieflyForApiKeyFromAppReferConfigure() = runTest {
        var calls = 0
        val c = client(key = { if (++calls >= 3) "pk_late" else null }) { r ->
            assertEquals("pk_late", r.headers["X-AppRefer-Key"])
            ok
        }
        c.api.send(BillingEndpoint.GetCustomer("u"))
        assertEquals(1, c.hosts.size)
    }

    @Test
    fun retryableClassification() {
        for (status in listOf(400, 413, 422)) assertFalse(AppReferBillingException.Server(status, null).isRetryable)
        for (status in listOf(401, 404, 409, 429, 500, 503)) assertTrue(AppReferBillingException.Server(status, null).isRetryable)
        assertFalse(AppReferBillingException.InvalidAppUserId().isRetryable)
        assertTrue(AppReferBillingException.Server(502, null).isConnectivity)
        assertFalse(AppReferBillingException.Server(404, null).isConnectivity)
    }

    @Test
    fun purchaseSendsRestoreOnlyWhenAsked() {
        val plain = JSONObject(BillingApiClient.makeRequest(BillingEndpoint.Purchase("u", null, "tok", "p", ProductTypes.SUBSCRIPTION, emptyMap()), base, "pk").body!!)
        assertFalse(plain.has("restore"))
        val restore = JSONObject(
            BillingApiClient.makeRequest(BillingEndpoint.Purchase("u", null, "tok", "p", ProductTypes.SUBSCRIPTION, emptyMap(), restore = true), base, "pk").body!!,
        )
        assertEquals(true, restore.get("restore"))
    }

    @Test
    fun backoffClassification() {
        for (status in listOf(401, 403, 404, 409, 429)) {
            assertTrue(AppReferBillingException.Server(status, null).isBackoff)
            assertTrue(AppReferBillingException.Server(status, null).isRetryable)
        }
        for (status in listOf(400, 413, 422, 500, 503)) assertFalse(AppReferBillingException.Server(status, null).isBackoff)
        assertFalse(AppReferBillingException.Network("x").isBackoff)
    }

    @Test
    fun retryAfterIsParsedFromSecondsOrHttpDate() = runTest {
        assertEquals(30_000L, BillingApiClient.parseRetryAfter("30", 0))
        assertNull(BillingApiClient.parseRetryAfter("soon", 0))
        assertNull(BillingApiClient.parseRetryAfter(null, 0))
        val now = 1_784_000_000_000L // 2026-07-14
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.format(java.util.Date(now + 90_000))
        assertEquals(90_000L, BillingApiClient.parseRetryAfter(date, now))

        val c = client { BillingHttpResponse(429, """{"error":"slow down"}""", "45") }
        try {
            c.api.send(BillingEndpoint.GetCustomer("u"))
            fail()
        } catch (e: AppReferBillingException.Server) {
            assertEquals(45_000L, e.retryAfterMillis)
        }
    }

    @Test
    fun verifiedButNotRecordedIsRetryable() = runTest {
        val c = client { BillingHttpResponse(503, """{"error":"Purchase verified but not recorded yet; retry","retryable":true}""", "30") }
        try {
            c.api.send(BillingEndpoint.Purchase("u", null, "tok", "p", ProductTypes.CONSUMABLE, emptyMap()))
            fail()
        } catch (e: AppReferBillingException.Server) {
            assertEquals(503, e.status)
            assertEquals(30_000L, e.retryAfterMillis)
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun okAnswerSayingLedgerWriteFailedIsTreatedAsNotRecorded() = runTest {
        val body = JSONObject(ok.body).put("purchase", JSONObject().put("conversion", "failed")).toString()
        val c = client { BillingHttpResponse(200, body) }
        try {
            c.api.send(BillingEndpoint.Purchase("u", null, "tok", "p", ProductTypes.CONSUMABLE, emptyMap()))
            fail()
        } catch (e: AppReferBillingException.Server) {
            assertEquals(503, e.status)
        }
        // Other conversions are recorded purchases.
        val processed = JSONObject(ok.body).put("purchase", JSONObject().put("conversion", "processed")).toString()
        client { BillingHttpResponse(200, processed) }.api.send(BillingEndpoint.Purchase("u", null, "tok", "p", ProductTypes.CONSUMABLE, emptyMap()))
    }
}
