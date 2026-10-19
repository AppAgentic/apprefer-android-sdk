package com.apprefer.sdk

import com.apprefer.sdk.internal.BillingResetMarker
import com.apprefer.sdk.internal.JsonCodec
import com.apprefer.sdk.support.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(InternalAppReferApi::class)
class SDK093Test {
    private data class Call(val method: String, val url: String, val session: String?, val body: Map<String, Any?>)

    private class Server {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()
        private var minted = 0
        @Volatile var deleteStatus = 200
        /** Called before answering a DELETE /session (lets a test hold a revocation in flight). */
        @Volatile var onDelete: (String?) -> Unit = {}
        val transport = SupportTransport { method, url, headers, bytes ->
            val session = headers["X-AppRefer-Support-Session"]
            calls.add(Call(method, url, session, JsonCodec.decodeObject(bytes?.toString(Charsets.UTF_8) ?: "{}")))
            if (method == "DELETE" && url.endsWith("/session")) { onDelete(session); return@SupportTransport SupportResponse(deleteStatus, "{}") }
            SupportResponse(200, when {
                url.endsWith("/session") && method == "POST" -> """{"session":"${session ?: "ars_093_${++minted}"}"}"""
                url.endsWith("/conversations") -> """{"conversations":[]}"""
                else -> "{}"
            })
        }
        fun pushPosts() = calls.filter { it.method == "POST" && it.url.endsWith("/push-token") }
        fun revoked(token: String?) = calls.filter { it.method == "DELETE" && it.url.endsWith("/session") && it.session == token }
    }
    private class Store : SupportStore {
        override var token: String? = null; override var profile: String? = null
        override var pushToken: String? = null; override var lastKey: String? = null
    }
    private fun Store.items() = JsonCodec.decodeObject(pendingRevocations).objects("items")

    // region Fix 1: a revocation asked for while a pass runs is processed in the same session

    @Test fun revocationQueuedAsAPassFinishesRunsAnotherPass() = runBlocking {
        val server = Server(); val store = Store().apply { pendingRevocations = """{"items":[{"token":"ars_old_1","key":"pk_test"}]}""" }
        val api = SupportAPI({ "pk_test" }, store, { emptyMap() }, { emptyMap() }, server.transport)
        var once = true
        // After the pass's last look for new entries, before it lets go: e.g. clearIdentity() retiring a session.
        api.afterRevocationPass = {
            if (once) {
                once = false
                store.pendingRevocations = JsonCodec.encode(mapOf("items" to store.items() + mapOf("token" to "ars_old_2", "key" to "pk_test")))
                runBlocking { api.revokePending() } // the pass is still running: remembered, not dropped
            }
        }
        api.revokePending()
        assertEquals(1, server.revoked("ars_old_1").size)
        assertEquals("processed in the same app session, not at the next foreground", 1, server.revoked("ars_old_2").size)
        assertTrue(store.items().isEmpty())
    }

    // endregion

    // region Fix 3: a re-registered push token waits while an old session still holds it

    @Test fun reRegisteringATokenHeldByAnUnflaggedRevocationDefersItUntilRevoked() = runBlocking {
        // Saved by 0.9.1 or by reset(): no move flag, no environment.
        val server = Server().apply { deleteStatus = 503 }
        val store = Store().apply { pendingRevocations = """{"items":[{"token":"ars_old","key":"pk_test","push":"fcm-1","move":false,"createdAt":${System.currentTimeMillis()},"attempts":0}]}""" }
        val api = SupportAPI({ "pk_test" }, store, { mapOf("appUserId" to "acct_fitquest") }, { emptyMap() }, server.transport)
        api.register("fcm-1")
        assertTrue("not posted while the old session still holds it", server.pushPosts().isEmpty())
        val item = store.items().single()
        assertEquals(true, item["move"]); assertEquals("production", item["environment"])
        assertEquals("fcm-1", store.pushToken)
        api.drainRevocations()
        assertTrue(server.pushPosts().isEmpty())

        server.deleteStatus = 200
        api.drainRevocations()
        val push = server.pushPosts().single()
        assertEquals("fcm-1", push.body["token"]); assertEquals("production", push.body["environment"])
        assertNotEquals("ars_old", push.session); assertEquals(store.token, push.session)
        assertTrue("after the DELETE succeeded", server.calls.indexOfLast { it.method == "DELETE" && it.session == "ars_old" } < server.calls.indexOf(push))
        assertTrue(store.items().isEmpty())
    }

    @Test fun reRegisteringWhileTheRevocationIsInFlightMovesItWhenItSucceeds() = runBlocking {
        val server = Server()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        server.onDelete = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val store = Store().apply { pendingRevocations = """{"items":[{"token":"ars_old","key":"pk_test","push":"fcm-1","move":false}]}""" }
        val api = SupportAPI({ "pk_test" }, store, { emptyMap() }, { emptyMap() }, server.transport)
        val pass = launch(Dispatchers.IO) { api.drainRevocations() }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        api.register("fcm-1")
        assertTrue(server.pushPosts().isEmpty())
        release.countDown(); pass.join()
        api.drainRevocations()
        assertEquals("moved by the pass that was running", 1, server.pushPosts().size)
        assertEquals(store.token, server.pushPosts().single().session)
    }

    @Test fun aTokenNoRevocationHoldsIsPostedAtOnce() = runBlocking {
        val server = Server().apply { deleteStatus = 503 }
        val store = Store().apply { pendingRevocations = """{"items":[{"token":"ars_old","key":"pk_test","push":"fcm-old"}]}""" }
        val api = SupportAPI({ "pk_test" }, store, { emptyMap() }, { emptyMap() }, server.transport)
        api.register("fcm-new")
        assertEquals("fcm-new", server.pushPosts().single().body["token"])
        assertNotEquals(true, store.items().single()["move"])
    }

    // endregion

    // region Fix 2: reset() persists the Billing user it ran for

    @Test fun resetStoresTheCurrentBillingUserAsTheMarker() = runBlocking {
        BillingResetMarker.resetForTests()
        assertEquals("apprefer_billing_reset_user", BillingResetMarker.KEY_RESET_USER)
        for ((loggedIn, anonymous, expected) in listOf(
            Triple("user_sparkly", "\$ARAnon:1", "user_sparkly"),
            Triple(null, "\$ARAnon:2", "\$ARAnon:2"),
            Triple(null, null, null), // Billing never configured: nothing stored
        )) {
            val f = SDK091Test.Fixture()
            try {
                loggedIn?.let { f.data["apprefer_billing_app_user_id"] = it }
                anonymous?.let { f.data["apprefer_billing_anonymous_id"] = it }
                AppRefer.reset(f.context)
                assertEquals(expected, f.data["apprefer_billing_reset_user"])
            } finally { f.close() }
        }
    }

    // region Round 2: logOut-then-reset order, and late marker writes

    @Test fun resetAfterBillingLogOutDoesNotMarkTheFreshAnonymousId() = runBlocking {
        BillingResetMarker.resetForTests()
        val f = SDK091Test.Fixture()
        try {
            // AppReferBilling.logOut() minted $ARAnon:fresh, then the app calls reset().
            f.data["apprefer_billing_anonymous_id"] = "\$ARAnon:fresh"
            AppReferInternal.billingIdentitySet("\$ARAnon:fresh") { f.data.remove("apprefer_billing_reset_user") }
            AppRefer.reset(f.context)
            assertNull("the fresh anonymous user keeps its device id", f.data["apprefer_billing_reset_user"])
            // A logIn(A) user is still marked by a later reset().
            f.data["apprefer_billing_app_user_id"] = "user_fitquest"
            AppReferInternal.billingIdentitySet(null) { f.data.remove("apprefer_billing_reset_user") }
            AppRefer.reset(f.context)
            assertEquals("user_fitquest", f.data["apprefer_billing_reset_user"])
            // Another logOut mints another id: that one is skipped, the old fresh id is not special any more.
            f.data.remove("apprefer_billing_app_user_id"); f.data["apprefer_billing_anonymous_id"] = "\$ARAnon:second"
            AppReferInternal.billingIdentitySet("\$ARAnon:second") { f.data.remove("apprefer_billing_reset_user") }
            AppRefer.reset(f.context)
            assertNull(f.data["apprefer_billing_reset_user"])
        } finally { f.close(); BillingResetMarker.resetForTests() }
    }

    @Test fun theLogOutExemptionIsUsedOnceThenTheSameAnonymousUserIsMarked() = runBlocking {
        BillingResetMarker.resetForTests()
        val f = SDK091Test.Fixture()
        try {
            f.data["apprefer_billing_anonymous_id"] = "\$ARAnon:fresh"
            AppReferInternal.billingIdentitySet("\$ARAnon:fresh") { f.data.remove("apprefer_billing_reset_user") }
            AppRefer.reset(f.context)
            assertNull("first reset after logOut: exempt", f.data["apprefer_billing_reset_user"])
            // Still the same anonymous user, no logIn/logOut since: the second reset marks them.
            AppRefer.reset(f.context)
            assertEquals("\$ARAnon:fresh", f.data["apprefer_billing_reset_user"])
        } finally { f.close(); BillingResetMarker.resetForTests() }
    }

    @Test fun aMarkerWriteFromAResetThatStartedBeforeALogInIsDropped() {
        BillingResetMarker.resetForTests()
        val data = mutableMapOf<String, String>("apprefer_billing_app_user_id" to "user_sparkly")
        // reset() starts (not awaited), then AppReferBilling.logIn("user_sparkly") clears the marker...
        val since = BillingResetMarker.generation()
        AppReferInternal.billingIdentitySet(null) { data.remove("apprefer_billing_reset_user") }
        // ...and the reset's write lands afterwards: it must not apply.
        assertFalse(BillingResetMarker.mark(since, { data[it] }, { k, v -> data[k] = v; true }))
        assertNull(data["apprefer_billing_reset_user"])
        // A reset that starts after the logIn still marks that user.
        assertTrue(BillingResetMarker.mark(BillingResetMarker.generation(), { data[it] }, { k, v -> data[k] = v; true }))
        assertEquals("user_sparkly", data["apprefer_billing_reset_user"])
        BillingResetMarker.resetForTests()
    }

    // endregion

    @Test fun versionIs0130() { assertEquals("0.13.0", APPREFER_SDK_VERSION) }
}
