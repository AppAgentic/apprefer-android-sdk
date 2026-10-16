package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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
    fun cancel() {}
}
internal object SupportNetwork : SupportTransport {
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    override fun cancel() { connections.forEach { it.disconnect() }; connections.clear() }
    override fun request(method: String, url: String, headers: Map<String, String>, bytes: ByteArray?): SupportResponse {
        require(URL(url).protocol == "https") { "Support requires HTTPS" }
        val c = URL(url).openConnection() as HttpURLConnection
        connections.add(c)
        try {
            c.requestMethod = method; c.connectTimeout = if (method == "DELETE") 5_000 else 20_000; c.readTimeout = if (method == "DELETE") 5_000 else 60_000
            c.instanceFollowRedirects = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (bytes != null) { c.doOutput = true; c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) } }
            val status = c.responseCode
            return SupportResponse(status, (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { connections.remove(c); c.disconnect() }
    }
}
private object SupportStoreDefaults { val revocations = java.util.Collections.synchronizedMap(java.util.WeakHashMap<SupportStore, String>()) }
internal interface SupportStore {
    var pendingRevocations: String?
        get() = SupportStoreDefaults.revocations[this]
        set(value) { if (value == null) SupportStoreDefaults.revocations.remove(this) else SupportStoreDefaults.revocations[this] = value }
    var lastKey: String?
        get() = null
        set(_) {}
    var token: String?
    var profile: String?
    var pushToken: String?
        get() = null
        set(_) {}
}
class AppReferSupportException(val status: Int, message: String) : Exception(message)

internal class SupportAPI(
    private val key: () -> String?, private val store: SupportStore,
    private val identity: () -> Map<String, Any?>,
    private val context: () -> Map<String, Any?>,
    private val transport: SupportTransport = SupportNetwork,
    private val base: String = "https://apprefer.com",
) {
    private val mutex = Mutex()
    @Volatile private var generation = 0
    private val accountLock = Any()
    private val deferredRevocations = mutableListOf<Map<String, Any?>>()
    val actionGeneration: Int get() = generation
    private val revokeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    suspend fun reset() = withContext(Dispatchers.IO) {
        synchronized(accountLock) {
            generation++
            val old = store.token; val push = store.pushToken; val oldKey = key() ?: store.lastKey
            store.token = null; store.profile = null; store.pushToken = null; persona = null
            if (old != null) {
                deferredRevocations.add(mapOf("token" to old, "key" to oldKey, "push" to push, "createdAt" to System.currentTimeMillis(), "attempts" to 0))
                runCatching {
                    val items = JsonCodec.decodeObject(store.pendingRevocations).objects("items")
                    store.pendingRevocations = JsonCodec.encode(mapOf("items" to (items + deferredRevocations).distinctBy { it["token"] }))
                }
            }
        }
        transport.cancel()
        // Revocation never holds the session mutex or delays local logout / B's session.
        revokeScope.launch { runCatching { revokePending() } }
        Unit
    }
    fun resumeRevocations() { revokeScope.launch { runCatching { revokePending() } } }
    private val revocationMutex = Mutex()
    internal suspend fun revokePending() {
        // Do not queue foreground/configure passes behind slow network requests.
        if (!revocationMutex.tryLock()) return
        try {
            val pending = synchronized(accountLock) { (JsonCodec.decodeObject(store.pendingRevocations).objects("items") + deferredRevocations).distinctBy { it["token"] } }
            for (original in pending) {
                val token = original["token"] as? String ?: continue
                val created = (original["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                var attempts = (original["attempts"] as? Number)?.toInt() ?: 0
                var remove = System.currentTimeMillis() - created > 400L * 86_400_000 || attempts >= 20
                val apiKey = original["key"] as? String ?: key()
                if (!remove && apiKey != null) {
                    remove = runCatching {
                        val response = transport.request("DELETE", base + root + "/session", mapOf("X-AppRefer-Key" to apiKey, "X-AppRefer-Support-Session" to token), null)
                        response.status in 200..299 || response.status in listOf(401, 403, 404)
                    }.getOrDefault(false)
                    if (!remove) { attempts++; remove = attempts >= 20 }
                }
                synchronized(accountLock) {
                    val current = (JsonCodec.decodeObject(store.pendingRevocations).objects("items") + deferredRevocations).distinctBy { it["token"] }
                    val updated = current.mapNotNull { item ->
                        if (item["token"] != token) item else if (remove) null else item + mapOf("createdAt" to created, "attempts" to attempts)
                    }
                    val encoded = JsonCodec.encode(mapOf("items" to updated))
                    if (store.pendingRevocations != encoded) store.pendingRevocations = encoded
                    deferredRevocations.removeAll { it["token"] == token }
                }
            }
        } finally { revocationMutex.unlock() }
    }
    private val root = "/api/support/v1"
    private fun raw(method: String, path: String, body: Map<String, Any?>?, token: String?, epoch: Int = generation): Map<String, Any?> {
        check(epoch == generation) { "Support reset" }
        val headers = mutableMapOf("X-AppRefer-Key" to (key() ?: error("Configure AppRefer first")))
        token?.let { headers["X-AppRefer-Support-Session"] = it }
        val bytes = body?.let { headers["Content-Type"] = "application/json"; JsonCodec.encode(it).toByteArray(Charsets.UTF_8) }
        check(epoch == generation) { "Support reset" }
        val r = transport.request(method, base + path, headers, bytes)
        check(epoch == generation) { "Support reset" }
        val obj = JsonCodec.decodeObject(r.body)
        if (r.status !in 200..299) throw AppReferSupportException(r.status, obj["error"] as? String ?: obj["message"] as? String ?: "Something went wrong (${r.status})")
        return obj
    }
    private fun session(): String {
        val epoch = generation
        store.lastKey = key() ?: store.lastKey
        val p = identity().toSortedMap(); val fingerprint = JsonCodec.encode(p)
        val old = store.token
        if (old != null) {
            if (store.profile != fingerprint) { val replacement = raw("POST", "$root/session", p, old, epoch)["session"] as? String ?: error("Missing support session"); synchronized(accountLock) { check(epoch == generation) { "Support reset" }; store.token = replacement; store.profile = fingerprint } }
            return requireNotNull(store.token)
        }
        val token = raw("POST", "$root/session", p, null, epoch)["session"] as? String ?: error("Missing support session")
        synchronized(accountLock) { check(epoch == generation) { "Support reset" }; store.token = token; store.profile = fingerprint }
        return token
    }
    private suspend fun call(method: String, path: String, body: Map<String, Any?>? = null, epoch: Int = generation): Map<String, Any?> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        check(epoch == generation) { "Support reset" }
        mutex.withLock {
            check(epoch == generation) { "Support reset" }
            currentCoroutineContext().ensureActive()
            try {
                val bearer = session()
                check(epoch == generation) { "Support reset" }
                raw(method, root + path, body, bearer, epoch)
            }
            catch (e: AppReferSupportException) {
                check(epoch == generation) { "Support reset" }
                if (e.status != 401 || !e.message.orEmpty().contains("support session", true)) throw e
                store.token = null; store.profile = null
                raw(method, root + path, body, session(), epoch)
            }
        }
    }
    suspend fun syncIdentity() = withContext(Dispatchers.IO) { mutex.withLock {
        try { session() } catch (e: AppReferSupportException) {
            if (e.status != 401 || !e.message.orEmpty().contains("support session", true)) throw e
            store.token = null; store.profile = null; session()
        }
    }; Unit }
    /** The app's AI assistant, from the latest list or conversation read (null when the app has none). */
    @Volatile var persona: AppReferSupportPersona? = null
        private set
    suspend fun conversations(): List<AppReferSupportConversation> {
        val epoch = generation
        val list = call("GET", "/conversations")
        synchronized(accountLock) { check(epoch == generation); persona = AppReferSupportPersona.from(list) }
        return list.objects("conversations").map { AppReferSupportConversation(it) }
    }
    suspend fun thread(id: String, after: String? = null): SupportThread =
        SupportThread(call("GET", "/conversations/${escape(id)}" + (after?.let { "?after=${escape(it)}" } ?: ""))).also { persona = it.persona }
    suspend fun start(text: String, ids: List<String>, clientMessageId: String = java.util.UUID.randomUUID().toString(), subject: String? = null, generation: Int = this.generation): SupportThread =
        SupportThread(call("POST", "/conversations", messageBody(text, ids, clientMessageId) + ("context" to context()) +
            (subject?.trim()?.takeIf { it.isNotEmpty() }?.let { mapOf("subject" to it) } ?: emptyMap()), generation))
    suspend fun send(id: String, text: String, ids: List<String>, clientMessageId: String = java.util.UUID.randomUUID().toString(), generation: Int = this.generation): AppReferSupportMessage = AppReferSupportMessage(call("POST", "/conversations/${escape(id)}/messages", messageBody(text, ids, clientMessageId), generation).obj("message"))
    suspend fun register(token: String) { val epoch = generation; call("POST", "/push-token", mapOf("platform" to "android", "token" to token, "environment" to "production")); synchronized(accountLock) { check(epoch == generation); store.pushToken = token } }
    suspend fun remove(token: String) { val epoch = generation; call("DELETE", "/push-token", mapOf("token" to token)); synchronized(accountLock) { check(epoch == generation); store.pushToken = null } }
    suspend fun upload(bytes: ByteArray, generation: Int = this.generation): String {
        check(generation == this.generation) { "Support reset" }
        val slot = call("POST", "/attachments", mapOf("contentType" to "image/jpeg", "size" to bytes.size, "name" to "screenshot.jpg"), generation)
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            check(generation == this@SupportAPI.generation) { "Support reset" }
            val headers = slot.obj("headers").mapValues { it.value.toString() }
            val response = transport.request("PUT", slot["uploadUrl"] as String, headers, bytes)
            check(generation == this@SupportAPI.generation) { "Support reset" }
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
data class AppReferSupportMessage @JvmOverloads constructor(
    val id: String, val from: String,
    /** A team member's first name, or the AI assistant's name on "bot" messages. */
    val authorName: String?, val text: String, val createdAt: String, val attachments: List<AppReferSupportAttachment>,
    /** The AI assistant's note that it passed the conversation to a person. */
    val handoff: Boolean? = null,
) {
    internal constructor(m: Map<String, Any?>) : this(m["id"].toString(), m["from"].toString(), m["authorName"] as? String, m["text"] as? String ?: "", m["createdAt"] as? String ?: "", m.objects("attachments").map { AppReferSupportAttachment(it["url"] as? String, it["name"] as? String, it["contentType"] as? String, (it["size"] as? Number)?.toLong()) }, m["handoff"] as? Boolean)
    val isHandoff: Boolean get() = handoff == true
}
/** The app's AI support assistant, set in AppRefer. Every message from it is tagged "AI". */
data class AppReferSupportPersona(
    val name: String,
    val avatarUrl: String?,
    /** Who it passes conversations to ("Jay"); null means the team. */
    val handoffTo: String?,
) {
    internal companion object {
        /** The response's `persona`; null when it is null, missing (older servers) or has no name. */
        fun from(response: Map<String, Any?>): AppReferSupportPersona? {
            val m = response["persona"] as? Map<*, *> ?: return null
            val name = (m["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return AppReferSupportPersona(name, (m["avatarUrl"] as? String)?.takeIf { it.startsWith("https://") }, (m["handoffTo"] as? String)?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}
internal class SupportThread(m: Map<String, Any?>) {
    val conversation = AppReferSupportConversation(m.obj("conversation"))
    val messages = m.objects("messages").map { AppReferSupportMessage(it) }
    val persona = AppReferSupportPersona.from(m)
}
