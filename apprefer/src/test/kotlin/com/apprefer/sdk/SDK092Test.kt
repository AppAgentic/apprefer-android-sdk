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
        val transport = SupportTransport { method, url, headers, bytes ->
            val session = headers["X-AppRefer-Support-Session"]
            calls.add(Call(method, url, session, JsonCodec.decodeObject(bytes?.toString(Charsets.UTF_8) ?: "{}")))
            SupportResponse(200, when {
                url.endsWith("/session") && method == "POST" -> """{"session":"${session ?: "ars_092_${++minted}"}"}"""
                url.endsWith("/conversations") -> """{"conversations":[]}"""
                else -> "{}"
            })
        }
        fun sessionPosts() = calls.filter { it.method == "POST" && it.url.endsWith("/session") }
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
            }, { emptyMap() }, server.transport)
            api.conversations()
            assertEquals("acct_A", server.sessionPosts().last().body["appUserId"])
            val old = store.token!!

            f.storage.supportIdentityCleared = true; name = null // what AppReferSupport.clearIdentity() does first
            api.clearIdentity()
            val fresh = server.sessionPosts().last()
            assertNull("a new session, not an update of the old one", fresh.session)
            assertFalse(fresh.body.containsKey("appUserId")); assertFalse(fresh.body.containsKey("name"))
            assertNotEquals(old, store.token)
            assertEquals("acct_A", f.storage.getUserId()) // attribution's user id is untouched
            // The old session is revoked through the durable list.
            api.revokePending()
            assertTrue(server.calls.any { it.method == "DELETE" && it.url.endsWith("/session") && it.session == old })
            assertTrue(JsonCodec.decodeObject(store.pendingRevocations).objects("items").none { it["token"] == old })

            // configure(userId:) with the same id on the next launch keeps Support anonymous.
            f.storage.setUserId("acct_A")
            val posts = server.sessionPosts().size
            api.conversations()
            assertEquals(posts, server.sessionPosts().size)
            assertEquals(store.token, server.calls.last().session)
            // Another account: linked again.
            f.storage.setUserId("acct_B")
            assertFalse(f.storage.supportIdentityCleared)
            api.conversations()
            assertEquals("acct_B", server.sessionPosts().last().body["appUserId"])
        } finally { f.close() }
    }

    @Test fun clearIdentityMovesThePushTokenToTheNewSession() = runBlocking {
        val server = Server(); val store = Store()
        val api = SupportAPI({ "pk_test" }, store, { mapOf("appUserId" to "acct_A") }, { emptyMap() }, server.transport)
        api.register("fcm-1")
        val old = store.token
        api.clearIdentity()
        val push = server.calls.last()
        assertTrue(push.url.endsWith("/push-token")); assertEquals("fcm-1", push.body["token"])
        assertNotNull(push.session); assertNotEquals(old, push.session)
        assertEquals("fcm-1", store.pushToken)
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
