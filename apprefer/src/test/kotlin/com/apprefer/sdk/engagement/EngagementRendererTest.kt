package com.apprefer.sdk.engagement

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Payload validation and the bounded image fetch (pure Java parts of the shared renderer). */
class EngagementRendererTest {
    companion object {
        val IID = "ei_" + "a".repeat(26)
        val TOKEN = "er1." + "A".repeat(43)
        fun payload(overrides: Map<String, Any?> = emptyMap()): JSONObject {
            val o = JSONObject(mapOf(
                "type" to "engagement", "v" to 1, "mid" to "sparkly_live_finish-setup_abc_nudge", "purpose" to "reminders",
                "exp" to System.currentTimeMillis() + 3_600_000, "epoch" to 2, "iid" to IID, "dest" to "/setup", "ck" to "setup",
                "title" to "Finish Garden", "body" to "One step left.", "img" to "https://assets.example.com/push/v1/a.png", "rt" to TOKEN,
            ))
            o.put("actions", org.json.JSONArray(listOf(JSONObject(mapOf("id" to "start", "kind" to "start", "dest" to "/setup/next")), JSONObject(mapOf("id" to "later", "kind" to "snooze", "min" to 60)))))
            for ((k, v) in overrides) if (v == null) o.remove(k) else o.put(k, v)
            return o
        }
        fun data(o: JSONObject) = mapOf("type" to "engagement", "v" to "1", "engagement" to o.toString())
        fun reason(o: JSONObject): String? = try { EngagementRenderer.parse(o); null } catch (e: EngagementRenderer.InvalidPayload) { e.message }
    }

    /**
     * The renderer is shared by copy with the Flutter plugin and the React Native module. This
     * repo's copy must match the hash in shared-sources.json (the parent's
     * scripts/check-sdk-shared-sources.mjs keeps all copies and manifests equal).
     */
    @Test fun sharedRendererMatchesTheManifest() {
        val source = java.io.File("src/main/java/com/apprefer/sdk/engagement/EngagementRenderer.java").readText()
        val normalized = source.replace("\r\n", "\n").replaceFirst(Regex("^package [^\n]*\n"), "")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val manifest = JSONObject(java.io.File("../shared-sources.json").readText())
        assertEquals("EngagementRenderer.java changed without the shared copies: run node scripts/check-sdk-shared-sources.mjs in the parent repo",
            manifest.getString("engagementRenderer"), hash)
    }

    @Test fun validPayload() {
        val p = EngagementRenderer.parse(data(payload()))!!
        assertEquals(2, p.accountEpoch); assertEquals(IID, p.installId); assertEquals("/setup", p.destination)
        assertEquals(listOf("start", "snooze"), p.actions.map { it.kind }); assertEquals(60, p.actions[1].snoozeMinutes)
        assertFalse(p.isExpired(System.currentTimeMillis()))
    }

    @Test fun supportAndForeignMessagesAreNotEngagement() {
        assertNull(EngagementRenderer.parse(mapOf("type" to "support_reply", "conversationId" to "cv_1")))
        assertFalse(EngagementRenderer.isEngagement(mapOf("type" to "support_reply")))
        assertFalse(EngagementRenderer.isEngagement(emptyMap()))
        try { EngagementRenderer.parse(mapOf("type" to "engagement", "engagement" to "{bad")); fail() } catch (e: EngagementRenderer.InvalidPayload) { assertEquals("malformed", e.message) }
    }

    @Test fun malformedFieldsAreRejected() {
        assertEquals("unsupported_version", reason(payload(mapOf("v" to 2))))
        assertEquals("unsupported_version", reason(payload(mapOf("v" to true))))
        assertEquals("message_id", reason(payload(mapOf("mid" to "a/b"))))
        assertEquals("purpose", reason(payload(mapOf("purpose" to "promo"))))
        assertEquals("epoch", reason(payload(mapOf("epoch" to 1.5))))
        assertEquals("install", reason(payload(mapOf("iid" to "ei_x"))))
        assertEquals("copy", reason(payload(mapOf("title" to "t".repeat(129)))))
        assertEquals("receipt_token", reason(payload(mapOf("rt" to "x"))))
        assertEquals("collapse_key", reason(payload(mapOf("ck" to "Bad Key"))))
        for (bad in listOf("https://evil.example/x", "//evil.example/x", "javascript:x", "setup", "/a b")) {
            assertEquals(bad, "destination", reason(payload(mapOf("dest" to bad))))
        }
        assertEquals("actions", reason(payload(mapOf("actions" to org.json.JSONArray(listOf(JSONObject(mapOf("id" to "a", "kind" to "open")), JSONObject(mapOf("id" to "b", "kind" to "open"))))))))
        assertEquals("actions", reason(payload(mapOf("actions" to org.json.JSONArray(listOf(JSONObject(mapOf("id" to "a", "kind" to "snooze", "min" to 2))))))))
        assertEquals("actions", reason(payload(mapOf("actions" to org.json.JSONArray(listOf(JSONObject(mapOf("id" to "a", "kind" to "open", "dest" to "//x"))))))))
    }

    @Test fun imagePolicy() {
        for (bad in listOf("http://assets.example.com/a.png", "https://assets.example.com:8443/a.png", "https://u@assets.example.com/a.png",
            "https://10.0.0.1/a.png", "https://localhost/a.png", "https://assets.example.com/a.png?x", "https://assets.example.com/a.gif",
            "https://assets.example.com/../a.png", "https://assets.example.com//a.png", "https://assets.example.com/%2e%2e/a.png", "https://Assets.example.com/a.png")) {
            assertNull(bad, EngagementRenderer.imageHost(bad))
            assertEquals(bad, "image", reason(payload(mapOf("img" to bad))))
        }
        assertEquals("assets.example.com", EngagementRenderer.imageHost("https://assets.example.com/push/a.jpg"))
        val cfg = JSONObject().put("hosts", org.json.JSONArray(listOf("assets.example.com")))
        assertTrue(EngagementRenderer.allowed(cfg, "https://assets.example.com/a.png"))
        assertFalse(EngagementRenderer.allowed(cfg, "https://cdn.example.com/a.png"))
        assertFalse(EngagementRenderer.allowed(JSONObject(), "https://assets.example.com/a.png"))
    }

    // Bounded fetch against a local server (HTTPS is enforced earlier by imageHost/allowed).
    private lateinit var socket: ServerSocket
    private var base = ""
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) + ByteArray(100)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(100)

    private data class Route(val status: Int, val type: String?, val body: ByteArray, val headers: Map<String, String> = emptyMap(), val sleepMs: Long = 0)
    private val routes = mapOf(
        "/ok.png" to Route(200, "image/png", png),
        "/ok.jpg" to Route(200, "image/jpeg; charset=binary", jpeg),
        "/mismatch.png" to Route(200, "image/png", jpeg),
        "/html" to Route(200, "text/html", png),
        "/missing" to Route(404, "image/png", png),
        "/big.png" to Route(200, "image/png", png + ByteArray(400_000)),
        "/redirect" to Route(302, null, ByteArray(0), mapOf("Location" to "/ok.png")),
        "/slow.png" to Route(200, "image/png", png, sleepMs = 3000),
    )

    /** A tiny HTTP/1.1 server (one request per connection). */
    @Before fun startServer() {
        socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${socket.localPort}"
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break }
                        val r = routes[path] ?: Route(404, null, ByteArray(0))
                        if (r.sleepMs > 0) Thread.sleep(r.sleepMs)
                        val head = StringBuilder("HTTP/1.1 ${r.status} X\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\n")
                        r.type?.let { head.append("Content-Type: $it\r\n") }
                        r.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                        head.append("\r\n")
                        runCatching { c.getOutputStream().apply { write(head.toString().toByteArray()); write(r.body); flush() } }
                    }
                }
            }
        }
    }
    @After fun stopServer() { socket.close() }

    @Test fun fetchAcceptsOnlyDeclaredMatchingBytesWithinLimits() {
        assertArrayEquals(png, EngagementRenderer.fetch("$base/ok.png", 2000, 300_000))
        assertArrayEquals(jpeg, EngagementRenderer.fetch("$base/ok.jpg", 2000, 300_000))
        assertNull("MIME and bytes differ", EngagementRenderer.fetch("$base/mismatch.png", 2000, 300_000))
        assertNull("not an image type", EngagementRenderer.fetch("$base/html", 2000, 300_000))
        assertNull("not 200", EngagementRenderer.fetch("$base/missing", 2000, 300_000))
        assertNull("too large", EngagementRenderer.fetch("$base/big.png", 2000, 300_000))
        assertNull("redirects are never followed", EngagementRenderer.fetch("$base/redirect", 2000, 300_000))
        val start = System.currentTimeMillis()
        assertNull("time-bounded", EngagementRenderer.fetch("$base/slow.png", 500, 300_000))
        assertTrue(System.currentTimeMillis() - start < 2500)
        assertNull("offline", EngagementRenderer.fetch("http://127.0.0.1:1/x.png", 500, 300_000))
    }
}
