package com.apprefer.sdk.billing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TrackingConsentTest {
    @Test fun billingUsesCurrentConsentAndNeverFailsOverWhenKnown() = runTest {
        var consent: String? = null
        val hosts = mutableListOf<String>()
        val client = BillingApiClient(
            listOf("https://trk.apprefer.com", "https://apprefer.com"), { "pk" },
            BillingTransport { request -> hosts.add(request.url.substringBefore("/api/")); BillingHttpResponse(503, "{}") },
            keyWaitAttempts = 0, consentProvider = { consent },
        )
        suspend fun send() { try { client.send(BillingEndpoint.GetCustomer("user")) } catch (_: AppReferBillingException) {} }
        send()
        assertEquals(2, hosts.size)
        for (c in listOf("denied", "undetermined", "granted")) {
            consent = c
            hosts.clear()
            send()
            assertEquals(listOf(if (c == "granted") "https://trk.apprefer.com" else "https://apprefer.com"), hosts)
        }
    }
}
