package com.apprefer.sdk.billing

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BillingEnvironmentTest {
    @Test fun scopeSurvivesCacheAndReevaluation() {
        val info = requireNotNull(CustomerInfoJson.decode(JSONObject("""{"appUserId":"same-user","environment":"sandbox"}""")))
        assertEquals(BillingEnvironment.SANDBOX, info.environment)
        assertEquals(BillingEnvironment.SANDBOX, info.reevaluated(100).environment)
        assertEquals(BillingEnvironment.SANDBOX, info.withSource(AppReferCustomerInfo.Source.CACHE).environment)
        assertEquals(BillingEnvironment.SANDBOX, CustomerInfoJson.decode(CustomerInfoJson.encode(info))?.environment)
        assertNull(CustomerInfoJson.decode(JSONObject("""{"appUserId":"same-user"}"""))?.environment)
    }
    @Test fun scopedTransportRejectsMissingOrOtherEnvironment() = runTest {
        for (selected in BillingEnvironment.entries) {
            for (returned in listOf(selected.raw, if (selected == BillingEnvironment.PRODUCTION) "sandbox" else "production", "")) {
                val api = BillingApiClient(listOf("https://example.com"), { "pk_live_a" }, BillingTransport { request ->
                    assertEquals(selected.raw, request.headers["X-AppRefer-Billing-Environment"])
                    BillingHttpResponse(200, JSONObject().put("appUserId", "same-user").put("environment", returned).toString())
                }, environment = selected)
                try {
                    val response = api.send(BillingEndpoint.GetCustomer("same-user"))
                    assertEquals(selected.raw, returned)
                    assertEquals(selected, response.info.environment)
                } catch (e: AppReferBillingException.InvalidResponse) {
                    assertNotEquals(selected.raw, returned)
                }
            }
        }
    }
}
