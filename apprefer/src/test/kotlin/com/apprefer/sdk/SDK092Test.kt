package com.apprefer.sdk

import com.apprefer.sdk.internal.JsonCodec
import com.apprefer.sdk.support.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@OptIn(InternalAppReferApi::class)
class SDK092Test {
    private data class Call(val method: String, val url: String, val session: String?, val body: Map<String, Any?>)

    /** Records every Support request; mints a new token for each session POST without one. */
    private class Server {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Call>()
        private var minted = 0
        /** Status for DELETE /session (revocation). */
        @Volatile var deleteStatus = 200
        val transport = SupportTransport { method, url, headers, bytes ->
            val session = headers["X-AppRefer-Support-Session"]
            calls.add(Call(method, url, session, JsonCodec.decodeObject(bytes?.toString(Charsets.UTF_8) ?: "{}")))
            if (method == "DELETE" && url.endsWith("/session")) return@SupportTransport SupportResponse(deleteStatus, "{}")
            SupportResponse(200, when {
                url.endsWith("/session") && method == "POST" -> """{"session":"${session ?: "ars_092_${++minted}"}"}"""
                url.endsWith("/conversations") -> """{"conversations":[]}"""
                else -> "{}"
            })
        }
        fun sessionPosts() = calls.filter { it.method == "POST" && it.url.endsWith("/session") }
        fun pushPosts() = calls.filter { it.method == "POST" && it.url.endsWith("/push-token") }
        fun revoked(token: String?) = calls.filter { it.method == "DELETE" && it.url.endsWith("/session") && it.session == token }
    }
    private class Store : SupportStore {
        override var token: String? = null; override var profile: String? = null
        override var pushToken: String? = null; override var lastKey: String? = null
    }

    // region 1. Billing's gate is Support's gate

    @Test fun billingGateFollowsCollectionResetAndConfigure() = runBlocking {
        val f = SDK091Test.Fixture()
        try {
            f.status = 200
            assertEquals("device", AppReferInternal.linkableDeviceId())
            assertTrue(AppReferInternal.installLinkAllowed())
            // Saved off.
            AppRefer.setCollectionEnabled(false)
            assertNull(AppReferInternal.linkableDeviceId()); assertFalse(AppReferInternal.installLinkAllowed())
            // On again but not configured yet: still paused.
            AppRefer.setCollectionEnabled(true)
            assertNull(AppReferInternal.linkableDeviceId())
            AppRefer.configure(f.context, "pk_test")
            assertEquals("device", AppReferInternal.linkableDeviceId())
            // After reset: closed until configure runs again for the next user.
            AppRefer.reset()
            assertNull(AppReferInternal.linkableDeviceId())
            AppRefer.configure(f.context, "pk_test")
            assertEquals("device", AppReferInternal.linkableDeviceId())
            // The persisted id stays readable for other uses.
            assertEquals("device", AppReferInternal.deviceId(f.context))
        } finally { f.close() }
    }

    @Test fun unsavedOffClosesTheBillingGate() = runBlocking {
        val f = SDK091Test.Fixture()
        try {
            f.commit = { false }
            AppRefer.setCollectionEnabled(false)
            assertTrue(AppRefer.collectionOffUnsaved)
            assertNull(AppReferInternal.linkableDeviceId())
            assertFalse(AppReferInternal.installLinkAllowed())
        } finally { f.close() }
    }

    @Test fun billingOnlyAppsNeverLink() {
        val stateField = AppRefer::class.java.getDeclaredField("state").apply { isAccessible = true }
        val old = stateField.get(AppRefer)
        try {
            stateField.set(AppRefer, null)
            assertNull(AppReferInternal.linkableDeviceId()); assertFalse(AppReferInternal.installLinkAllowed())
        } finally { stateField.set(AppRefer, old) }
    }

    // endregion

    // region 2. Support clearIdentity

    @Test fun clearIdentityRevokesAndStartsAnAnonymousSession() = runBlocking {
        val f = SDK091Test.Fixture()
        try {
            f.storage.setUserId("acct_A")
            val server = Server(); val store = Store()
            var name: String? = "Sam"
            val api = SupportAPI({ "pk_test" }, store, {
                AppReferSupport.profile(f.context) + (name?.let { mapOf("name" to it) } ?: emptyMap())
            }, { emptyMap() }, server.transport, identityCleared = { f.storage.supportIdentityCleared })
            api.conversations()
            assertEquals("acct_A", server.sessionPosts().last().body["appUserId"])
            assertEquals("device", server.sessionPosts().last().body["deviceId"])
            val old = store.token!!

            f.storage.supportIdentityCleared = true; name = null // what AppReferSupport.clearIdentity() does first
            api.clearIdentity()
            val fresh = server.sessionPosts().last()
            assertNull("a new session, not an update of the old one", fresh.session)
            assertFalse(fresh.body.containsKey("appUserId")); assertFalse(fresh.body.containsKey("name"))
            assertFalse("no device id links it back to the account", fresh.body.containsKey("deviceId"))
            assertTrue(store.clearedSession)
            assertNotEquals(old, store.token)
            assertEquals("acct_A", f.storage.getUserId()) // attribution's user id is untouched
            // The old session is revoked through the durable list.
            api.drainRevocations()
            assertTrue(server.revoked(old).isNotEmpty())
            assertTrue(JsonCodec.decodeObject(store.pendingRevocations).objects("items").none { it["token"] == old })

            // configure(userId:) with the same id on the next launch keeps Support anonymous.
            f.storage.setUserId("acct_A")
            val posts = server.sessionPosts().size
            api.conversations()
            assertEquals(posts, server.sessionPosts().size)
            assertEquals(store.token, server.calls.last { it.url.endsWith("/conversations") }.session)
            // Another account (without reset): a fresh session, never the cleared one.
            val cleared = store.token!!
            f.storage.setUserId("acct_B")
            assertFalse(f.storage.supportIdentityCleared)
            api.conversations()
            val forB = server.sessionPosts().last()
            assertEquals("acct_B", forB.body["appUserId"])
            assertNull("minted, not an upgrade of the cleared session", forB.session)
            assertTrue(server.sessionPosts().none { it.session == cleared && it.body["appUserId"] == "acct_B" })
            assertNotEquals(cleared, store.token); assertFalse(store.clearedSession)
            assertEquals(store.token, server.calls.last { it.url.endsWith("/conversations") }.session)
            api.drainRevocations()
            assertEquals(1, server.revoked(cleared).size)
        } finally { f.close() }
    }

    @Test fun clearIdentityMovesThePushTokenOnlyAfterTheOldSessionIsRevoked() = runBlocking {
        val server = Server().apply { deleteStatus = 500 }; val store = Store()
        val api = SupportAPI({ "pk_test" }, store, { mapOf("appUserId" to "acct_A") }, { emptyMap() }, server.transport)
        api.register("fcm-1")
        val old = store.token
        api.clearIdentity()
        api.drainRevocations()
        assertTrue(server.revoked(old).isNotEmpty())
        assertEquals("not on the new session while the old one still holds it", 1, server.pushPosts().size)
        assertEquals("fcm-1", store.pushToken)
        // The next successful revocation pass moves it.
        server.deleteStatus = 200
        api.drainRevocations()
        val push = server.pushPosts().last()
        assertEquals(2, server.pushPosts().size)
        assertEquals("fcm-1", push.body["token"]); assertEquals("production", push.body["environment"])
        assertEquals(store.token, push.session); assertNotEquals(old, push.session)
        val okDelete = server.calls.indexOfLast { it.method == "DELETE" && it.session == old }
        assertTrue("moved after the DELETE succeeded", okDelete < server.calls.indexOf(push))
        api.drainRevocations()
        assertEquals("moved once", 2, server.pushPosts().size)
    }

    @Test fun resetBeforeTheRevocationSucceedsNeverMovesTheToken() = runBlocking {
        val server = Server().apply { deleteStatus = 500 }; val store = Store()
        val api = SupportAPI({ "pk_test" }, store, { mapOf("appUserId" to "acct_A") }, { emptyMap() }, server.transport)
        api.register("fcm-1")
        api.clearIdentity()
        api.drainRevocations()
        api.reset()
        server.deleteStatus = 200
        api.drainRevocations()
        assertEquals(1, server.pushPosts().size)
        assertNull(store.pushToken)
        assertTrue(JsonCodec.decodeObject(store.pendingRevocations).objects("items").isEmpty())
    }

    @Test fun registeringTheTokenWhileItWaitsToMoveDefersThePost() = runBlocking {
        val server = Server().apply { deleteStatus = 500 }; val store = Store()
        val api = SupportAPI({ "pk_test" }, store, { mapOf("appUserId" to "acct_A") }, { emptyMap() }, server.transport)
        api.register("fcm-1")
        api.clearIdentity()
        api.drainRevocations()
        api.register("fcm-1") // the app re-registers on launch
        assertEquals(1, server.pushPosts().size)
        assertEquals("fcm-1", store.pushToken)
        // A new token is not held back.
        api.register("fcm-2")
        assertEquals(2, server.pushPosts().size)
        server.deleteStatus = 200
        api.drainRevocations()
        assertEquals("the old token is no longer this device's, so it isn't moved", 2, server.pushPosts().size)
    }

    @Test fun clearIdentityWithNoSessionRegistersTheTokenOnTheNewSession() = runBlocking {
        val server = Server(); val store = Store().apply { pushToken = "fcm-1" }
        val api = SupportAPI({ "pk_test" }, store, { emptyMap() }, { emptyMap() }, server.transport)
        api.clearIdentity()
        assertEquals(store.token, server.pushPosts().single().session)
    }

    @Test fun clearedSessionRetiredForAnotherUserMovesItsPushToken() = runBlocking {
        val f = SDK091Test.Fixture()
        try {
            f.storage.setUserId("acct_A")
            val server = Server(); val store = Store()
            val api = SupportAPI({ "pk_test" }, store, { AppReferSupport.profile(f.context) }, { emptyMap() }, server.transport,
                identityCleared = { f.storage.supportIdentityCleared })
            f.storage.supportIdentityCleared = true
            api.register("fcm-1") // minted while cleared
            val cleared = store.token!!
            assertTrue(store.clearedSession)
            val gen = api.actionGeneration
            f.storage.setUserId("acct_B")
            api.syncIdentity()
            assertEquals("in-flight UI calls are not cancelled", gen, api.actionGeneration)
            assertEquals("acct_B", server.sessionPosts().last().body["appUserId"])
            assertNull(server.sessionPosts().last().session)
            api.drainRevocations()
            assertEquals(1, server.revoked(cleared).size)
            assertEquals(store.token, server.pushPosts().last().session)
            assertEquals(2, server.pushPosts().size)
        } finally { f.close() }
    }

    @Test fun profileWhileClearedHasNoDeviceIdEvenWhenConfigured() = runBlocking {
        val f = SDK091Test.Fixture()
        try {
            f.storage.setUserId("acct_A")
            assertEquals("device", AppReferSupport.profile(f.context)["deviceId"])
            f.storage.supportIdentityCleared = true
            val profile = AppReferSupport.profile(f.context)
            assertFalse(profile.containsKey("deviceId")); assertFalse(profile.containsKey("appUserId"))
        } finally { f.close() }
    }

    @Test fun clearIdentityWithNoContextIsAppliedWhenOneAppears() = runBlocking {
        val f = SDK091Test.Fixture()
        val appField = AppReferSupport::class.java.getDeclaredField("app").apply { isAccessible = true }
        val clientField = AppReferSupport::class.java.getDeclaredField("client").apply { isAccessible = true }
        val oldApp = appField.get(AppReferSupport); val oldClient = clientField.get(AppReferSupport)
        try {
            f.storage.setUserId("acct_A")
            appField.set(AppReferSupport, null); clientField.set(AppReferSupport, null)
            AppReferContext.context = null
            AppReferSupport.identify("Sam", "sam@example.com")
            AppReferSupport.clearIdentity(null)
            assertTrue("remembered, not a silent no-op", AppReferSupport.pendingClearIdentity)
            assertNull(AppReferSupport.name)
            assertFalse(f.storage.supportIdentityCleared)

            val server = Server(); val store = Store().apply { token = "ars_named" }
            clientField.set(AppReferSupport, SupportAPI({ "pk_test" }, store, { AppReferSupport.profile(f.context) }, { emptyMap() },
                server.transport, identityCleared = { f.storage.supportIdentityCleared }))
            // The first Support use with a context applies it before any request.
            val api = AppReferSupport.api(f.context, initialize = false)
            assertFalse(AppReferSupport.pendingClearIdentity)
            assertTrue(f.storage.supportIdentityCleared)
            assertNull(store.token)
            assertTrue(JsonCodec.decodeObject(store.pendingRevocations).objects("items").any { it["token"] == "ars_named" })
            api.conversations()
            assertNull(server.sessionPosts().last().session)
            assertFalse(server.sessionPosts().last().body.containsKey("appUserId"))
        } finally {
            AppReferSupport.pendingClearIdentity = false
            appField.set(AppReferSupport, oldApp); clientField.set(AppReferSupport, oldClient)
            f.close()
        }
    }

    @Test fun resetEndsAnIdentityClear() = runBlocking {
        val f = SDK091Test.Fixture()
        try {
            f.storage.setUserId("acct_A"); f.storage.supportIdentityCleared = true
            assertFalse(AppReferSupport.profile(f.context).containsKey("appUserId"))
            f.storage.clearUser()
            assertFalse(f.storage.supportIdentityCleared)
        } finally { f.close() }
    }

    // endregion
}
