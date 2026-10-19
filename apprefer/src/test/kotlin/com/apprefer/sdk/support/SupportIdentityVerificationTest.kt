package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** support.verifyIdentity (0.12.0): session-bound, single-use identity tokens. */
class SupportIdentityVerificationTest {
    private class Store : SupportStore { override var token: String? = null; override var profile: String? = null }
    private class Harness {
        val requests = mutableListOf<Triple<String, String, Map<String, Any?>>>()
        val sessions = mutableListOf<String?>()
        val responses = java.util.ArrayDeque<() -> SupportResponse>()
        val store = Store()
        var cleared = false
        var profile = mapOf<String, Any?>("appUserId" to "user_42")
        val api = SupportAPI({ "pk_live_key" }, store, { profile }, { emptyMap() }, SupportTransport { method, url, headers, bytes ->
            requests.add(Triple(method, url, JsonCodec.decodeObject(bytes?.toString(Charsets.UTF_8))))
            sessions.add(headers["X-AppRefer-Support-Session"])
            responses.removeFirst()()
        }, identityCleared = { cleared }).also { it.verifyRetryDelaysMs = listOf(1L, 1L) }
        fun reply(body: String, status: Int = 200) { responses.add { SupportResponse(status, body) } }
        fun then(block: () -> SupportResponse) { responses.add(block) }
        fun session(id: String = "s1") = reply("""{"session":"$id","created":true}""")
        val verified = """{"identity":{"verified":true,"appUserId":"user_42","verifiedAt":"2026-10-19T12:00:00.000Z"}}"""
    }

    @Test fun verifiesTheExistingSessionWithTheTokenOnce() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"conversations":[]}"""); h.api.conversations()
        h.reply(h.verified)
        val result = h.api.verifyIdentity("sit1.payload.sig")
        assertEquals(AppReferSupportIdentityVerification(true, null, "user_42"), result)
        val (method, url, body) = h.requests.last()
        assertEquals("POST", method); assertTrue(url.endsWith("/api/support/v1/session/identity"))
        assertEquals(mapOf("identityToken" to "sit1.payload.sig"), body)
        // On the session the conversations already use: no new session for verification.
        assertEquals("s1", h.sessions.last()); assertEquals(1, h.requests.count { it.second.endsWith("/session") })
        assertFalse(listOf(h.store.token, h.store.profile).any { it?.contains("sit1") == true })
    }

    @Test fun aLostAnswerIsRetriedWithTheSameTokenOnTheSameSession() = runBlocking {
        val h = Harness(); h.session()
        h.reply("""{"error":"Identity verification is unavailable; try again","reason":"unavailable"}""", 503)
        h.then { throw java.net.SocketTimeoutException("read timed out") }
        h.reply(h.verified)
        assertTrue(h.api.verifyIdentity("sit1.a.b").verified)
        val verifies = h.requests.filter { it.second.endsWith("/session/identity") }
        assertEquals(3, verifies.size)
        assertTrue(verifies.all { it.third["identityToken"] == "sit1.a.b" })
        assertEquals(listOf("s1", "s1", "s1"), h.sessions.drop(1))
    }

    @Test fun throwsAfterTheRetriesSoTheAppCanTryTheSameTokenLater() = runBlocking {
        val h = Harness(); h.session()
        repeat(3) { h.reply("""{"error":"down"}""", 503) }
        try { h.api.verifyIdentity("sit1.a.b"); fail() } catch (e: AppReferSupportException) { assertEquals(503, e.status) }
        assertEquals(3, h.requests.count { it.second.endsWith("/session/identity") })
    }

    @Test fun aRefusalIsReturnedNotRetried() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"identity":{"verified":false,"reason":"already_used"}}""")
        assertEquals(AppReferSupportIdentityVerification(false, "already_used", null), h.api.verifyIdentity("sit1.a.b"))
        assertEquals(2, h.requests.size)
    }

    @Test fun anUnknownSessionIsReplacedThenVerified() = runBlocking {
        val h = Harness(); h.session()
        h.reply("""{"error":"Unknown support session"}""", 401); h.session("s2"); h.reply(h.verified)
        assertTrue(h.api.verifyIdentity("sit1.a.b").verified)
        assertEquals("s2", h.sessions.last()); assertEquals("s2", h.store.token)
    }

    @Test fun resetWhileVerifyingAnswersAccountChanged() = runBlocking {
        val h = Harness(); h.session()
        h.then { runBlocking { h.api.reset() }; SupportResponse(200, h.verified) }
        assertEquals(AppReferSupportIdentityVerification(false, "account_changed", null), h.api.verifyIdentity("sit1.a.b"))
        assertNull(h.store.token)
    }

    @Test fun anotherUserWhileVerifyingAnswersAccountChanged() = runBlocking {
        val h = Harness(); h.session()
        h.then { h.profile = mapOf("appUserId" to "user_77"); SupportResponse(200, h.verified) }
        assertEquals("account_changed", h.api.verifyIdentity("sit1.a.b").reason)
    }

    @Test fun nothingIsSentAfterClearIdentityOrForAnEmptyToken() = runBlocking {
        val h = Harness(); h.cleared = true
        assertEquals("identity_cleared", h.api.verifyIdentity("sit1.a.b").reason)
        h.cleared = false
        assertEquals("malformed", h.api.verifyIdentity(" ").reason)
        assertEquals("malformed", h.api.verifyIdentity("x".repeat(2049)).reason)
        assertTrue(h.requests.isEmpty())
    }
}
