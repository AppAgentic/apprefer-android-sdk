package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupportAPITest {
    private class Store : SupportStore { override var token: String? = null; override var profile: String? = null }
    private data class Request(val method: String, val url: String, val headers: Map<String,String>, val bytes: ByteArray?) {
        val body get() = JsonCodec.decodeObject(bytes?.toString(Charsets.UTF_8))
    }
    private class Harness {
        val requests = mutableListOf<Request>()
        val responses = java.util.ArrayDeque<SupportResponse>()
        val store = Store()
        var profile = mapOf<String,Any?>("appUserId" to "u1", "deviceId" to "d1", "name" to "María", "email" to "m@example.com")
        val api = SupportAPI({ "pk_test_key" }, store, { profile }, { mapOf("platform" to "android", "appVersion" to "2", "os" to "Android 15", "device" to "Pixel", "locale" to "en-GB") }, SupportTransport { method, url, headers, bytes ->
            requests.add(Request(method, url, headers.toMap(), bytes)); responses.removeFirst()
        })
        fun reply(body: String = "{}", status: Int = 200) { responses.add(SupportResponse(status, body)) }
        fun session() { reply("""{"session":"s1","created":true}""") }
    }
    @Test fun mintOnceHeadersAndIdentityChanges() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"conversations":[]}"""); h.api.conversations()
        assertEquals("POST", h.requests[0].method); assertTrue(h.requests[0].url.endsWith("/session"))
        assertEquals(h.profile, h.requests[0].body)
        assertEquals("pk_test_key", h.requests[0].headers["X-AppRefer-Key"])
        assertNull(h.requests[0].headers["X-AppRefer-Support-Session"])
        assertEquals("s1", h.requests[1].headers["X-AppRefer-Support-Session"])
        h.reply("""{"conversations":[]}"""); h.api.conversations(); assertEquals(3, h.requests.size)
        h.profile = h.profile + ("name" to "Jane"); h.reply("""{"session":"s2"}"""); h.reply("""{"conversations":[]}"""); h.api.conversations()
        assertTrue(h.requests[3].url.endsWith("/session")); assertEquals("s1", h.requests[3].headers["X-AppRefer-Support-Session"])
        assertEquals("Jane", h.requests[3].body["name"]); assertEquals("s2", h.store.token); assertEquals("s2", h.requests.last().headers["X-AppRefer-Support-Session"])
    }
    @Test fun explicitRetryKeepsIdentityAndRefreshesAttachments() = runBlocking {
        val h = Harness(); h.session()
        h.reply("""{"error":"lost response"}""", 503)
        try { h.api.start("hello", listOf("old"), "client-123"); fail() } catch (_: AppReferSupportException) {}
        h.reply("""{"conversation":{"id":"c"},"messages":[],"duplicate":true}""")
        h.api.start("hello", listOf("fresh"), "client-123")
        assertEquals("client-123", h.requests[1].body["clientMessageId"])
        assertEquals("client-123", h.requests[2].body["clientMessageId"])
        assertEquals(listOf("fresh"), h.requests[2].body["attachmentIds"])
        h.reply("""{"message":{"id":"m"}}"""); h.api.send("c", "hi", emptyList(), "message-123")
        h.reply("""{"message":{"id":"m"},"duplicate":true}"""); h.api.send("c", "hi", emptyList(), "message-123")
        assertEquals(h.requests[3].body["clientMessageId"], h.requests[4].body["clientMessageId"])
    }
    @Test fun retryExpiredSessionOnce() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"error":"Invalid support session"}""",401); h.reply("""{"session":"s2"}"""); h.reply("""{"conversations":[]}""")
        h.api.conversations(); assertEquals(4, h.requests.size); assertEquals("s2", h.store.token)
        assertNull(h.requests[2].headers["X-AppRefer-Support-Session"])
        assertEquals("s2", h.requests[3].headers["X-AppRefer-Support-Session"])
    }
    @Test fun doesNotRetryBadKeyOrRetryForever() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"error":"Invalid key"}""",401)
        try { h.api.conversations(); fail() } catch(e: AppReferSupportException) { assertEquals(401,e.status) }
        assertEquals(2,h.requests.size)
        val j = Harness(); j.session(); j.reply("""{"error":"support session expired"}""",401); j.session(); j.reply("""{"error":"support session expired"}""",401)
        try { j.api.conversations(); fail() } catch(e: AppReferSupportException) { assertEquals(401,e.status) }
        assertEquals(4,j.requests.size)
    }
    @Test fun startSendPollAndPushShapes() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"conversation":{"id":"c"},"messages":[]}""")
        h.api.start("hello", listOf("a")); val start = h.requests.last()
        assertEquals("hello",start.body["text"]); assertEquals(listOf("a"),start.body["attachmentIds"])
        assertEquals("android",start.body.obj("context")["platform"])
        h.reply("""{"message":{"id":"m","from":"customer","text":"","attachments":[]}}"""); h.api.send("c/a", "", listOf("b"))
        assertTrue(h.requests.last().url.endsWith("/c%2Fa/messages")); assertEquals("",h.requests.last().body["text"])
        h.reply("""{"conversation":{"id":"c"},"messages":[]}"""); h.api.thread("c", "2026-10-15T12:34:56.000+01:00")
        assertTrue(h.requests.last().url.contains("after=2026-10-15T12%3A34%3A56.000%2B01%3A00"))
        h.reply(); h.api.register("fcm"); assertEquals("android",h.requests.last().body["platform"])
        h.reply(); h.api.remove("fcm"); assertEquals("DELETE",h.requests.last().method); assertEquals(mapOf("token" to "fcm"),h.requests.last().body)
    }
    @Test fun uploadUsesExactBytesAndOnlySlotHeaders() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"attachmentId":"a1","uploadUrl":"https://storage.example/u","headers":{"Content-Type":"image/jpeg"},"expiresIn":600}"""); h.reply()
        val bytes = byteArrayOf(1,2,3,4); assertEquals("a1",h.api.upload(bytes))
        assertEquals(4,h.requests[1].body["size"]); assertEquals("image/jpeg",h.requests[1].body["contentType"])
        val put = h.requests.last(); assertEquals("PUT",put.method); assertEquals(mapOf("Content-Type" to "image/jpeg"),put.headers); assertArrayEquals(bytes,put.bytes)
    }
    @Test fun failedIdentitySyncIsRetried() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"conversations":[]}"""); h.api.conversations()
        val previous = h.store.profile; h.profile = h.profile + ("appUserId" to "u2"); h.reply("""{"error":"offline"}""",503)
        try { h.api.conversations(); fail() } catch (_: AppReferSupportException) {}
        assertEquals(previous,h.store.profile)
        h.reply("""{"session":"replacement"}"""); h.api.syncIdentity()
        assertEquals("replacement", h.store.token)
        assertNotEquals(previous, h.store.profile)
        h.reply("""{"conversations":[]}"""); h.api.conversations()
        assertEquals("replacement", h.requests.last().headers["X-AppRefer-Support-Session"])
    }
}
