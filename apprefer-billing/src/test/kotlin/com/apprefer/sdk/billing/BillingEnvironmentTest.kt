package com.apprefer.sdk.billing

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
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

class BillingEnvironmentEngineTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun replayAfterScopeSwitchWaitsForMatchingSnapshot() = runTest {
        var selected = BillingEnvironment.SANDBOX
        fun info(env: BillingEnvironment) = AppReferCustomerInfo("same-user", EntitlementInfos(emptyMap()), setOf(env.raw), NOW, AppReferCustomerInfo.Source.SERVER, env)
        val source = MutableStateFlow(info(BillingEnvironment.SANDBOX))
        val stream = environmentSnapshots(source) { selected }
        assertEquals(BillingEnvironment.SANDBOX, stream.first().environment)
        selected = BillingEnvironment.PRODUCTION
        val next = async { stream.first() }
        runCurrent()
        assertFalse(next.isCompleted)
        source.value = info(BillingEnvironment.PRODUCTION)
        assertEquals(BillingEnvironment.PRODUCTION, next.await().environment)
    }

    @Test fun onlyVerifiedPlayScopeGrantsDuringOutage() = runTest {
        var selected = BillingEnvironment.PRODUCTION
        val h = Harness(this, "same-user", options = BillingEngine.Options(defaultEntitlement = "premium", environment = { selected }))
        h.server.reachable = false
        h.play.purchases += storePurchase("prod-token", "prod", acknowledged = true)
        h.play.purchases += storePurchase("test-token", "test", acknowledged = true)
        h.play.purchases += storePurchase("unknown-token", "unknown", acknowledged = true)
        h.storage.recordPurchaseEnvironment("prod-token", BillingEnvironment.PRODUCTION)
        h.storage.recordPurchaseEnvironment("test-token", BillingEnvironment.SANDBOX)
        assertEquals(setOf("prod"), h.engine.localFallback().activeSubscriptions)
        selected = BillingEnvironment.SANDBOX
        assertEquals(setOf("test"), h.engine.localFallback().activeSubscriptions)
    }
    @Test fun heldOtherScopeReceiptNeverExpiresAndDoesNotBlockQueue() = runTest {
        val h = Harness(this, "same-user", options = BillingEngine.Options(environment = { BillingEnvironment.PRODUCTION }))
        h.storage.recordPurchaseEnvironment("old-test", BillingEnvironment.SANDBOX)
        h.queue.add(PendingPurchase("old-test", "test", ProductTypes.SUBSCRIPTION, "same-user", null, 0L, 999, migrationOnly = true))
        h.queue.add(PendingPurchase("prod", "prod", ProductTypes.SUBSCRIPTION, "same-user", null, h.now, 0, migrationOnly = true))
        h.engine.drainQueue()
        assertTrue(h.queue.entries.any { it.purchaseToken == "old-test" && it.attempts == 999 })
        assertFalse(h.queue.entries.any { it.purchaseToken == "prod" })
        assertEquals(BillingEnvironment.PRODUCTION, h.storage.purchaseEnvironment("prod"))
    }
    @Test fun verifiedMismatchClassifiesWithoutAcceptingOrGranting() = runTest {
        var bound: BillingEnvironment? = null
        val api = BillingApiClient(listOf("https://example.com"), { "pk_live_a" }, BillingTransport {
            BillingHttpResponse(400, """{"code":"billing_environment_mismatch","environment":"sandbox"}""")
        }, environmentProvider = { BillingEnvironment.PRODUCTION }, recordProofEnvironment = { _, value -> bound = value })
        try {
            api.send(BillingEndpoint.Purchase("same-user", null, "legacy", "test", ProductTypes.SUBSCRIPTION, emptyMap()))
            fail("mismatch must not return a customer snapshot")
        } catch (error: AppReferBillingException.EnvironmentMismatch) {
            assertEquals(BillingEnvironment.SANDBOX, error.environment)
            assertTrue(error.isRetryable)
            assertFalse(error.isConnectivity)
        }
        assertEquals(BillingEnvironment.SANDBOX, bound)
    }
}
