package com.apprefer.sdk
import com.apprefer.sdk.internal.*
import com.apprefer.sdk.support.*
import com.apprefer.sdk.models.Attribution
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class Round2Test {
    private class Store : SupportStore {
        override var token: String? = "A"
        override var profile: String? = "A"
        override var pushToken: String? = "push-A"
        override var pendingRevocations: String? = null
    }
    @Test fun failedRevocationsPersistAcrossRestartUntilSessionDeleted() = runBlocking {
        val store = Store()
        val attempts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failed = SupportAPI({ "pk_A" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { _, url, _, _ -> attempts.add(url); SupportResponse(503, "{}") })
        failed.reset(); failed.revokePending()
        assertNull(store.token); assertNull(store.pushToken)
        assertTrue(store.pendingRevocations!!.contains("push-A"))
        val next = SupportAPI({ "pk_B" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { _, url, headers, _ ->
            assertEquals("pk_A", headers["X-AppRefer-Key"]); attempts.add(url); SupportResponse(200, "{}")
        })
        next.revokePending()
        assertTrue(JsonCodec.decodeObject(store.pendingRevocations).objects("items").isEmpty())
        assertFalse(attempts.any { it.endsWith("/push-token") }); assertTrue(attempts.any { it.endsWith("/session") })
    }
    @Test fun pendingRefreshDoesNotNeedAConsentChange() = runBlocking {
        val reporter = ConsentReporter(); var pending = true; var calls = 0; var network = "organic"
        reporter.report({ "undetermined" }, { "undetermined" }, { calls++; mapOf("attribution" to mapOf("network" to "meta")) }, { network = "meta"; pending = false }, {}, pending = { pending })
        assertEquals(1, calls); assertEquals("meta", network)
        reporter.report({ "undetermined" }, { "undetermined" }, { calls++; emptyMap() }, {}, {}, pending = { pending })
        assertEquals(1, calls)
    }
    @Test fun pendingWithoutAnyConsentStillPolls() = runBlocking {
        var calls = 0
        ConsentReporter().report({ null }, { null }, { assertNull(it); calls++; emptyMap() }, {}, { fail("Must not save synthetic consent") }, pending = { true })
        assertEquals(1, calls)
    }
    @Test fun adServicesAlwaysReportsAttributed() {
        assertTrue(AppReferAttributionResult.resolve(Attribution("apple_search_ads", matchType = "adservices", trackingDecisionPending = true), false, false) is AppReferAttributionResult.Attributed)
    }
    @Test fun sessionSuccessNeedsNoPushRemoval() = runBlocking {
        val store = Store()
        val api = SupportAPI({ "pk" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { _, url, _, _ -> SupportResponse(if (url.endsWith("/push-token")) 503 else 200, "{}") })
        api.reset(); api.revokePending()
        withTimeout(5000) { while (JsonCodec.decodeObject(store.pendingRevocations).objects("items").isNotEmpty()) yield() }
        assertTrue(JsonCodec.decodeObject(store.pendingRevocations).objects("items").isEmpty())
    }
    @Test fun queueClearOrdersNewlyAcceptedEvents() = runBlocking {
        var saved: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = EventQueue({ saved }, { saved = it }, { true }, { 503 }, scope)
        queue.clear(); assertEquals(AppReferEventResult.QUEUED, queue.enqueue(mapOf("event_name" to "signed_out")))
        assertTrue(saved!!.contains("signed_out")); queue.clear(); scope.cancel()
    }
    @Test fun identicalConcurrentConfigureCallsJoinOneRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ConfigureCoordinator(scope)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); var calls = 0
        val first = async { coordinator.run(listOf("pk", null, false, 1)) { calls++; entered.complete(Unit); finish.await(); Attribution("meta", matchType = "click") } }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.run(listOf("pk", null, false, 1)) { fail("Must join"); null } }
        finish.complete(Unit)
        assertEquals("meta", first.await()?.network); assertEquals("meta", second.await()?.network); assertEquals(1, calls)
        scope.cancel()
    }

    @Test fun goneSessionsAreDroppedAndPollingNeverRevokes() = runBlocking {
        for (status in listOf(200, 204, 299, 401, 403, 404)) {
            val store = Store().apply { token = "B"; profile = "{}"; pendingRevocations = "{\"items\":[{\"token\":\"A\",\"key\":\"old-key\",\"push\":\"push-A\"}]}" }
            var deletes = 0
            val api = SupportAPI({ "pk_B" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { method, url, headers, bytes ->
                if (method == "DELETE") {
                    deletes++; assertTrue(url.endsWith("/session")); assertEquals("old-key", headers["X-AppRefer-Key"]); assertNull(bytes)
                    SupportResponse(status, "{}")
                } else SupportResponse(200, "{\"conversations\":[]}")
            })
            repeat(3) { api.conversations(); api.thread("chat") }
            assertEquals(0, deletes)
            api.revokePending()
            assertEquals(1, deletes)
            assertTrue(JsonCodec.decodeObject(store.pendingRevocations).objects("items").isEmpty())
        }
    }
    @Test fun ageAndAttemptCapsBoundRetries() = runBlocking {
        val store = Store().apply { pendingRevocations = JsonCodec.encode(mapOf("items" to listOf(
            mapOf("token" to "expired", "createdAt" to System.currentTimeMillis() - 401L * 86400000),
            mapOf("token" to "capped", "attempts" to 20),
            mapOf("token" to "last", "attempts" to 19)
        ))) }
        var calls = 0
        val api = SupportAPI({ "pk" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { _, _, headers, _ ->
            calls++; assertEquals("last", headers["X-AppRefer-Support-Session"]); SupportResponse(503, "{}")
        })
        api.revokePending(); api.revokePending()
        assertEquals(1, calls); assertTrue(JsonCodec.decodeObject(store.pendingRevocations).objects("items").isEmpty())
    }
    @Test fun overlappingPassesSkipInsteadOfQueueing() = runBlocking {
        val store = Store().apply { pendingRevocations = "{\"items\":[{\"token\":\"A\"}]}" }
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        var calls = 0
        val api = SupportAPI({ "pk" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { _, _, _, _ ->
            calls++; entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); SupportResponse(503, "{}")
        })
        val first = launch(Dispatchers.IO) { api.revokePending() }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        try { withTimeout(1000) { repeat(10) { api.revokePending() } } } finally { release.countDown() }
        first.join(); assertEquals(1, calls)
    }

}
