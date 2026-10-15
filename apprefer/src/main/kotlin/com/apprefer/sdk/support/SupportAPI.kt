package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class SupportResponse(val status: Int, val body: String)
internal fun interface SupportTransport {
    fun request(method: String, url: String, headers: Map<String, String>, bytes: ByteArray?): SupportResponse
}
internal object SupportNetwork : SupportTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, bytes: ByteArray?): SupportResponse {
        require(URL(url).protocol == "https") { "Support requires HTTPS" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (bytes != null) { c.doOutput = true; c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) } }
            val status = c.responseCode
            return SupportResponse(status, (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }
}
internal interface SupportStore { var token: String?; var profile: String? }
class AppReferSupportException(val status: Int, message: String) : Exception(message)

internal class SupportAPI(
    private val key: () -> String?, private val store: SupportStore,
    private val identity: () -> Map<String, Any?>,
    private val context: () -> Map<String, Any?>,
    private val transport: SupportTransport = SupportNetwork,
    private val base: String = "https://apprefer.com",
) {
    private val mutex = Mutex()
    private val root = "/api/support/v1"
    private fun raw(method: String, path: String, body: Map<String, Any?>?, token: String?): Map<String, Any?> {
        val headers = mutableMapOf("X-AppRefer-Key" to (key() ?: error("Configure AppRefer first")))
        token?.let { headers["X-AppRefer-Support-Session"] = it }
        val bytes = body?.let { headers["Content-Type"] = "application/json"; JsonCodec.encode(it).toByteArray(Charsets.UTF_8) }
        val r = transport.request(method, base + path, headers, bytes)
        val obj = JsonCodec.decodeObject(r.body)
        if (r.status !in 200..299) throw AppReferSupportException(r.status, obj["error"] as? String ?: obj["message"] as? String ?: "Something went wrong (${r.status})")
        return obj
    }
    private fun session(): String {
        val p = identity().toSortedMap(); val fingerprint = JsonCodec.encode(p)
        val old = store.token
        if (old != null) {
            if (store.profile != fingerprint) { val replacement = raw("POST", "$root/session", p, old)["session"] as? String ?: error("Missing support session"); store.token = replacement; store.profile = fingerprint }
            return requireNotNull(store.token)
        }
        val token = raw("POST", "$root/session", p, null)["session"] as? String ?: error("Missing support session")
        store.token = token; store.profile = fingerprint
        return token
    }
    private suspend fun call(method: String, path: String, body: Map<String, Any?>? = null): Map<String, Any?> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { raw(method, root + path, body, session()) }
            catch (e: AppReferSupportException) {
                if (e.status != 401 || !e.message.orEmpty().contains("support session", true)) throw e
                store.token = null; store.profile = null
                raw(method, root + path, body, session())
            }
        }
    }
    suspend fun syncIdentity() = withContext(Dispatchers.IO) { mutex.withLock {
        try { session() } catch (e: AppReferSupportException) {
            if (e.status != 401 || !e.message.orEmpty().contains("support session", true)) throw e
            store.token = null; store.profile = null; session()
        }
    }; Unit }
    suspend fun conversations(): List<AppReferSupportConversation> = call("GET", "/conversations").objects("conversations").map { AppReferSupportConversation(it) }
    suspend fun thread(id: String, after: String? = null): SupportThread = SupportThread(call("GET", "/conversations/${escape(id)}" + (after?.let { "?after=${escape(it)}" } ?: "")))
    suspend fun start(text: String, ids: List<String>, clientMessageId: String = java.util.UUID.randomUUID().toString()): SupportThread = SupportThread(call("POST", "/conversations", messageBody(text, ids, clientMessageId) + ("context" to context())))
    suspend fun send(id: String, text: String, ids: List<String>, clientMessageId: String = java.util.UUID.randomUUID().toString()): AppReferSupportMessage = AppReferSupportMessage(call("POST", "/conversations/${escape(id)}/messages", messageBody(text, ids, clientMessageId)).obj("message"))
    suspend fun register(token: String) { call("POST", "/push-token", mapOf("platform" to "android", "token" to token, "environment" to "production")) }
    suspend fun remove(token: String) { call("DELETE", "/push-token", mapOf("token" to token)) }
    suspend fun upload(bytes: ByteArray): String {
        val slot = call("POST", "/attachments", mapOf("contentType" to "image/jpeg", "size" to bytes.size, "name" to "screenshot.jpg"))
        return withContext(Dispatchers.IO) {
            val headers = slot.obj("headers").mapValues { it.value.toString() }
            val response = transport.request("PUT", slot["uploadUrl"] as String, headers, bytes)
            if (response.status !in 200..299) throw AppReferSupportException(response.status, "The screenshot didn't upload")
            slot["attachmentId"] as String
        }
    }
    private fun messageBody(text: String, ids: List<String>, clientMessageId: String): Map<String, Any?> {
        require(clientMessageId.matches(Regex("[A-Za-z0-9_-]{8,64}"))); require(ids.size <= 4); require(text.isNotBlank() || ids.isNotEmpty())
        return mapOf("text" to text, "clientMessageId" to clientMessageId) + if (ids.isEmpty()) emptyMap() else mapOf("attachmentIds" to ids)
    }
    private fun escape(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
@Suppress("UNCHECKED_CAST") internal fun Map<String, Any?>.obj(k: String) = this[k] as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST") internal fun Map<String, Any?>.objects(k: String) = this[k] as? List<Map<String, Any?>> ?: emptyList()

data class AppReferSupportConversation(val id: String, val subject: String, val status: String, val preview: String, val lastMessageAt: String, val lastFrom: String, val unread: Int) {
    internal constructor(m: Map<String, Any?>) : this(m["id"].toString(), m["subject"] as? String ?: "Conversation", m["status"].toString(), m["preview"] as? String ?: "", m["lastMessageAt"] as? String ?: "", m["lastFrom"].toString(), (m["unread"] as? Number)?.toInt() ?: 0)
}
data class AppReferSupportAttachment(val url: String?, val name: String?, val contentType: String?, val size: Long?)
data class AppReferSupportMessage(val id: String, val from: String, val authorName: String?, val text: String, val createdAt: String, val attachments: List<AppReferSupportAttachment>) {
    internal constructor(m: Map<String, Any?>) : this(m["id"].toString(), m["from"].toString(), m["authorName"] as? String, m["text"] as? String ?: "", m["createdAt"] as? String ?: "", m.objects("attachments").map { AppReferSupportAttachment(it["url"] as? String, it["name"] as? String, it["contentType"] as? String, (it["size"] as? Number)?.toLong()) })
}
internal class SupportThread(m: Map<String, Any?>) {
    val conversation = AppReferSupportConversation(m.obj("conversation"))
    val messages = m.objects("messages").map { AppReferSupportMessage(it) }
}
