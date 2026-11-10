package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** support.send and compose's app data (0.14.0): `context.app` on a new conversation only. */
class SupportAppContextTest {
    private class Store : SupportStore { override var token: String? = null; override var profile: String? = null }
    private class Harness {
        val requests = mutableListOf<Triple<String, String, Map<String, Any?>>>()
        val responses = java.util.ArrayDeque<() -> SupportResponse>()
        val store = Store()
        val api = SupportAPI({ "pk_live_key" }, store, { mapOf("appUserId" to "user_42") }, { mapOf("platform" to "android", "os" to "Android 15") }, SupportTransport { method, url, _, bytes ->
            requests.add(Triple(method, url, JsonCodec.decodeObject(bytes?.toString(Charsets.UTF_8))))
            responses.removeFirst()()
        }).also { it.sendRetryDelaysMs = listOf(1L, 1L) }
        fun reply(body: String, status: Int = 200) { responses.add { SupportResponse(status, body) } }
        fun then(block: () -> SupportResponse) { responses.add(block) }
        fun session(id: String = "s1") = reply("""{"session":"$id","created":true}""")
        fun created(id: String = "cv_1") = reply("""{"conversation":{"id":"$id","subject":"Map issue"},"messages":[]}""")
        val starts get() = requests.filter { it.first == "POST" && it.second.endsWith("/api/support/v1/conversations") }
    }

    // Cleaning

    @Test fun keepsValidKeysSortedAndCleansValues() {
        val cleaned = SupportAppContext.clean(linkedMapOf(
            "source" to "  map_report ", "course_id" to "c_123", "Bad-Key" to "x", "" to "x",
            "k".repeat(41) to "x", "empty" to "   ", "line" to "a\nb\tc\u0000", "ok_1" to "1"))
        assertEquals(listOf("course_id", "line", "ok_1", "source"), cleaned.keys.toList())
        assertEquals("map_report", cleaned["source"]); assertEquals("a b c", cleaned["line"])
        assertTrue(SupportAppContext.clean(mapOf("k".repeat(40) to "v")).isNotEmpty())
    }

    @Test fun keepsTheFirstTwentySortedKeysAndCutsValues() {
        val many = (0 until 30).associate { "key_%02d".format(29 - it) to "v$it" }
        val cleaned = SupportAppContext.clean(many)
        assertEquals((0 until 20).map { "key_%02d".format(it) }, cleaned.keys.toList())
        assertEquals(500, SupportAppContext.clean(mapOf("a" to "x".repeat(900)))["a"]!!.length)
        // A surrogate pair across the cut is not split.
        val emoji = SupportAppContext.clean(mapOf("a" to "x".repeat(499) + "😀"))["a"]!!
        assertEquals(499, emoji.length)
        // Empty values don't use up the 20.
        val withEmpties = (0 until 25).associate { "k%02d".format(it) to if (it < 5) "" else "v" }
        assertEquals(20, SupportAppContext.clean(withEmpties).size)
    }

    @Test fun javaNullsAreDropped() {
        @Suppress("UNCHECKED_CAST") val fromJava = hashMapOf<String?, String?>("a" to null, null to "b", "c" to "d") as Map<String, String>
        assertEquals(mapOf("c" to "d"), SupportAppContext.clean(fromJava))
    }

    // Wire format

    @Test fun startSendsAppDataInsideContextAndLeavesItOutWhenEmpty() = runBlocking {
        val h = Harness(); h.session(); h.created(); h.created("cv_2")
        h.api.start("hello", emptyList(), appContext = mapOf("course_id" to "c_1", "BAD" to "x"))
        val context = h.starts[0].third.obj("context")
        assertEquals(mapOf("course_id" to "c_1"), context["app"]); assertEquals("android", context["platform"])
        assertEquals("hello", h.starts[0].third["text"])
        h.api.start("hello", emptyList(), appContext = mapOf("BAD" to "x"))
        assertFalse(h.starts[1].third.obj("context").containsKey("app"))
    }

    @Test fun messagesNeverCarryAppData() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"message":{"id":"m"}}""")
        h.api.send("cv_1", "more", emptyList())
        assertNull(h.requests.last().third["context"])
    }

    // send()

    @Test fun sendStartsAConversationWithAppDataAndReturnsItsId() = runBlocking {
        val h = Harness(); h.session(); h.created("cv_9")
        val id = h.api.startConversation("  The 7th green is wrong  ", "Map issue", mapOf("course_id" to "c_1", "source" to "map_report"))
        assertEquals("cv_9", id)
        val body = h.starts.single().third
        assertEquals("The 7th green is wrong", body["text"]); assertEquals("Map issue", body["subject"])
        assertEquals(mapOf("course_id" to "c_1", "source" to "map_report"), body.obj("context")["app"])
        assertTrue((body["clientMessageId"] as String).matches(Regex("[A-Za-z0-9_-]{8,64}")))
        assertNull(body["attachmentIds"])
    }

    @Test fun transientFailuresAreRetriedWithTheSameClientMessageId() = runBlocking {
        val h = Harness(); h.session()
        h.reply("""{"error":"down"}""", 503)
        h.then { throw java.net.SocketTimeoutException("read timed out") }
        h.reply("""{"conversation":{"id":"cv_1"},"messages":[],"duplicate":true}""")
        assertEquals("cv_1", h.api.startConversation("hi", null, mapOf("a" to "b")))
        assertEquals(3, h.starts.size)
        assertEquals(1, h.starts.map { it.third["clientMessageId"] }.toSet().size)
        assertTrue(h.starts.all { it.third.obj("context")["app"] == mapOf("a" to "b") })
        // The next call is a new message.
        h.created("cv_2"); h.api.startConversation("again", null, emptyMap())
        assertFalse(h.starts.last().third["clientMessageId"] == h.starts.first().third["clientMessageId"])
    }

    @Test fun throwsAfterTwoRetries() = runBlocking {
        val h = Harness(); h.session()
        repeat(3) { h.reply("""{"error":"slow down"}""", 429) }
        try { h.api.startConversation("hi", null, emptyMap()); fail() } catch (e: AppReferSupportException) { assertEquals(429, e.status) }
        assertEquals(3, h.starts.size)
    }

    @Test fun aClientErrorIsNotRetried() = runBlocking {
        val h = Harness(); h.session(); h.reply("""{"error":"too long"}""", 413)
        try { h.api.startConversation("hi", null, emptyMap()); fail() } catch (e: AppReferSupportException) { assertEquals(413, e.status) }
        assertEquals(1, h.starts.size)
    }

    @Test fun emptyTextThrowsWithoutSending() = runBlocking {
        val h = Harness()
        for (text in listOf("", "   \n ")) {
            try { h.api.startConversation(text, null, mapOf("a" to "b")); fail() } catch (e: AppReferSupportException) { assertEquals("EMPTY_TEXT", e.code) }
        }
        assertTrue(h.requests.isEmpty())
    }

    @Test fun anUnknownSessionIsReplacedOnce() = runBlocking {
        val h = Harness(); h.session()
        h.reply("""{"error":"Unknown support session"}""", 401); h.session("s2"); h.created()
        assertEquals("cv_1", h.api.startConversation("hi", null, emptyMap()))
        assertEquals("s2", h.store.token)
        assertEquals(1, h.starts.map { it.third["clientMessageId"] }.toSet().size)
    }

    @Test fun aResetWhileSendingThrowsAndIsNotRetried() = runBlocking {
        val h = Harness(); h.session()
        h.then { runBlocking { h.api.reset() }; SupportResponse(503, """{"error":"down"}""") }
        try { h.api.startConversation("hi", null, emptyMap()); fail() } catch (_: AppReferSupportException) {}
        assertEquals(1, h.starts.size)
    }
}
