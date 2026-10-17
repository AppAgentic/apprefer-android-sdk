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

internal data class SupportResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    /** A response header, ignoring case. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
internal fun interface SupportTransport {
    fun request(method: String, url: String, headers: Map<String, String>, bytes: ByteArray?): SupportResponse
    fun cancel() {}
}
internal object SupportNetwork : SupportTransport {
    // Not ConcurrentHashMap.newKeySet(): that is API 24+, and the SDK supports API 21.
    private val connections: MutableSet<HttpURLConnection> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>())
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
            val responseHeaders = c.headerFields.orEmpty().mapNotNull { (k, v) -> if (k == null || v.isNullOrEmpty()) null else k to v.joinToString(", ") }.toMap()
            return SupportResponse(status, (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "", responseHeaders)
        } finally { connections.remove(c); c.disconnect() }
    }
}
private object SupportStoreDefaults {
    val revocations = java.util.Collections.synchronizedMap(java.util.WeakHashMap<SupportStore, String>())
    val cleared: MutableSet<SupportStore> = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<SupportStore, Boolean>()))
}
internal interface SupportStore {
    var pendingRevocations: String?
        get() = SupportStoreDefaults.revocations[this]
        set(value) { if (value == null) SupportStoreDefaults.revocations.remove(this) else SupportStoreDefaults.revocations[this] = value }
    /** The stored token was minted (or adopted) while the Support identity was cleared. */
    var clearedSession: Boolean
        get() = this in SupportStoreDefaults.cleared
        set(value) { if (value) SupportStoreDefaults.cleared.add(this) else SupportStoreDefaults.cleared.remove(this) }
    var lastKey: String?
        get() = null
        set(_) {}
    var token: String?
    var profile: String?
    var pushToken: String?
        get() = null
        set(_) {}
}
/**
 * A failed Support request. [message] is the server's (or the SDK's) English text, for logs; the
 * chat shows its own text in the customer's language instead.
 */
class AppReferSupportException(val status: Int, message: String) : Exception(message) {
    /** The help article doesn't exist or isn't published (HTTP 404). */
    val isNotFound: Boolean get() = status == 404
    /** What kind of failure, so the chat can explain it in the customer's language. */
    internal enum class Reason { SERVER, UPLOAD, UNAVAILABLE }
    internal var reason: Reason = Reason.SERVER
        private set
    internal constructor(status: Int, message: String, reason: Reason) : this(status, message) { this.reason = reason }
}

internal class SupportAPI(
    private val key: () -> String?, private val store: SupportStore,
    private val identity: () -> Map<String, Any?>,
    private val context: () -> Map<String, Any?>,
    private val transport: SupportTransport = SupportNetwork,
    private val base: String = "https://apprefer.com",
    /** True while `clearIdentity()` is in force (the persisted `supportIdentityCleared` flag). */
    private val identityCleared: () -> Boolean = { false },
) {
    private val mutex = Mutex()
    /** Help centre and status: SDK key only, no session (SupportHelp.kt). */
    val help = SupportHelpClient(key, transport, base)
    /** Whether this install has registered for reply notifications. */
    val hasPushToken: Boolean get() = store.pushToken != null
    @Volatile private var generation = 0
    private val accountLock = Any()
    private val deferredRevocations = mutableListOf<Map<String, Any?>>()
    val actionGeneration: Int get() = generation
    private val revokeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    suspend fun reset() = withContext(Dispatchers.IO) {
        retireSession(keepPushToken = false)
        transport.cancel()
        // Revocation never holds the session mutex or delays local logout / B's session.
        resumeRevocations()
        Unit
    }
    /**
     * Consent withdrawn: revokes the session through the durable revocation list and starts a
     * new anonymous one (the profile carries no app user id or device id), keeping no link to
     * the old one. A registered push token moves to the new session only once the old one is
     * revoked. Old conversations stay in the team inbox but are no longer shown in the app.
     */
    suspend fun clearIdentity() = withContext(Dispatchers.IO) {
        val unmoved = retireForClearedIdentity()
        if (key() != null) runCatching { syncIdentity(); if (unmoved != null) postPushToken(unmoved) }
        Unit
    }
    /**
     * The local half of [clearIdentity]: forgets the session and queues its revocation, with the
     * push token kept and flagged to move. Returns the push token when there was no session to
     * revoke (nothing to wait for, so the caller registers it on the new session directly).
     */
    internal fun retireForClearedIdentity(): String? {
        val hadSession = store.token != null
        val push = retireSession(keepPushToken = true, move = true)
        transport.cancel()
        resumeRevocations()
        return push.takeUnless { hadSession }
    }
    /**
     * Forgets the session locally and queues its revocation; returns its push token. [advance]
     * moves the action generation on (cancelling in-flight UI calls); [move] flags the push token
     * to be registered on the current session once the revocation succeeds.
     */
    private fun retireSession(keepPushToken: Boolean, move: Boolean = false, advance: Boolean = true): String? = synchronized(accountLock) {
        if (advance) generation++
        val old = store.token; val push = store.pushToken; val oldKey = key() ?: store.lastKey
        store.token = null; store.profile = null; store.clearedSession = false
        if (advance) persona = null
        if (!keepPushToken) store.pushToken = null
        if (old != null) {
            deferredRevocations.add(mapOf("token" to old, "key" to oldKey, "push" to push, "move" to (move && push != null), "createdAt" to System.currentTimeMillis(), "attempts" to 0))
            runCatching {
                val items = JsonCodec.decodeObject(store.pendingRevocations).objects("items")
                store.pendingRevocations = JsonCodec.encode(mapOf("items" to (items + deferredRevocations).distinctBy { it["token"] }))
            }
        }
        push
    }
    /**
     * A session minted while the identity was cleared is never given an app user id: once a
     * different user id ends the clear (without reset), it is retired and a fresh one minted.
     * The action generation is not advanced, so in-flight calls carry on with the new session.
     */
    private fun retireClearedSessionIfNeeded() {
        val retire = synchronized(accountLock) { store.token != null && store.clearedSession && !identityCleared() }
        if (!retire) return
        retireSession(keepPushToken = true, move = true, advance = false)
        resumeRevocations()
    }
    fun resumeRevocations() { revokeScope.launch { runCatching { revokePending() } } }
    private val revocationMutex = Mutex()
    /** A pass was asked for while one was running: run another once it ends. */
    private val revokeAgain = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Tests: runs after a pass's last check for new entries, before the pass lets go. */
    @Volatile internal var afterRevocationPass: (() -> Unit)? = null
    internal suspend fun revokePending() {
        // Do not queue foreground/configure passes behind slow network requests: a call while a
        // pass runs is remembered, and the running pass runs again (same app session) when done.
        // A re-run picks up sessions retired meanwhile; entries this call already tried wait for
        // the next launch/foreground/configure, as before.
        revokeAgain.set(true)
        val attempted = HashSet<String>()
        while (revokeAgain.get()) {
            if (!revocationMutex.tryLock()) return
            try {
                while (revokeAgain.getAndSet(false)) revokeAll(attempted)
                afterRevocationPass?.invoke()
            } finally { revocationMutex.unlock() }
        }
    }
    /** Waits for a running pass, then runs one (tests). */
    internal suspend fun drainRevocations() { revocationMutex.withLock { revokeAll() } }
    /** One attempt per entry; sessions retired while the pass runs are picked up before it ends. */
    private suspend fun revokeAll(attempted: MutableSet<String> = HashSet()) {
        while (true) {
            val pending = synchronized(accountLock) { pendingItems() }.filter { (it["token"] as? String)?.let { t -> t !in attempted } == true }
            if (pending.isEmpty()) return
            pending.forEach { attempted.add(it["token"] as String) }
            revokeOnce(pending)
        }
    }
    private fun pendingItems() = (JsonCodec.decodeObject(store.pendingRevocations).objects("items") + deferredRevocations).distinctBy { it["token"] }
    private suspend fun revokeOnce(pending: List<Map<String, Any?>>) {
        for (original in pending) {
            val token = original["token"] as? String ?: continue
            val created = (original["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            var attempts = (original["attempts"] as? Number)?.toInt() ?: 0
            var remove = System.currentTimeMillis() - created > 400L * 86_400_000 || attempts >= 20
            var revoked = false
            val apiKey = original["key"] as? String ?: key()
            if (!remove && apiKey != null) {
                revoked = runCatching {
                    val response = transport.request("DELETE", base + root + "/session", mapOf("X-AppRefer-Key" to apiKey, "X-AppRefer-Support-Session" to token), null)
                    response.status in 200..299 || response.status in listOf(401, 403, 404)
                }.getOrDefault(false)
                remove = revoked
                if (!remove) { attempts++; remove = attempts >= 20 }
            }
            val move = synchronized(accountLock) {
                // The current entry: a registerPushToken() since the pass started may have flagged it to move.
                val current = pendingItems().firstOrNull { it["token"] == token } ?: original
                val updated = pendingItems().mapNotNull { item ->
                    if (item["token"] != token) item else if (remove) null else item + mapOf("createdAt" to created, "attempts" to attempts)
                }
                val encoded = JsonCodec.encode(mapOf("items" to updated))
                if (store.pendingRevocations != encoded) store.pendingRevocations = encoded
                deferredRevocations.removeAll { it["token"] == token }
                // Only after a real revocation (never a drop for age or attempts), only while the
                // token is still this device's (a reset since then cleared it) and once no other
                // old session waiting for revocation still holds it.
                val push = current["push"] as? String
                push.takeIf { revoked && current["move"] == true && it != null && store.pushToken == it &&
                    updated.none { other -> other["move"] == true && other["push"] == it } }
            }
            // Failure is swallowed: the app's next registerPushToken fixes it.
            if (move != null) runCatching { postPushToken(move) }
        }
    }
    /**
     * When an old session waiting for revocation still holds [token] (flagged to move or not, e.g.
     * after reset() or from an older SDK), flags it to move to the current session once that
     * revocation succeeds, saves the token and returns true. False when no revocation holds it.
     */
    private fun holdForRevocation(token: String): Boolean = synchronized(accountLock) {
        val items = pendingItems()
        if (items.none { it["push"] == token }) return@synchronized false
        val flag = mapOf("move" to true, "environment" to "production")
        val updated = items.map { if (it["push"] == token) it + flag else it }
        runCatching { store.pendingRevocations = JsonCodec.encode(mapOf("items" to updated)) }
        for (i in deferredRevocations.indices) if (deferredRevocations[i]["push"] == token) deferredRevocations[i] = deferredRevocations[i] + flag
        store.pushToken = token
        true
    }
    private val root = "/api/support/v1"
    private fun raw(method: String, path: String, body: Map<String, Any?>?, token: String?, epoch: Int = generation): Map<String, Any?> {
        check(epoch == generation) { "Support reset" }
        val headers = mutableMapOf("X-AppRefer-Key" to (key() ?: throw AppReferSupportException(0, "AppRefer isn't configured", AppReferSupportException.Reason.UNAVAILABLE)))
        token?.let { headers["X-AppRefer-Support-Session"] = it }
        val bytes = body?.let { headers["Content-Type"] = "application/json"; JsonCodec.encode(it).toByteArray(Charsets.UTF_8) }
        check(epoch == generation) { "Support reset" }
        val r = transport.request(method, base + path, headers, bytes)
        check(epoch == generation) { "Support reset" }
        val obj = JsonCodec.decodeObject(r.body)
        if (r.status !in 200..299) throw AppReferSupportException(r.status, obj["error"] as? String ?: obj["message"] as? String ?: "Something went wrong (${r.status})")
        return obj
    }
    private fun dropToken() = synchronized(accountLock) { store.token = null; store.profile = null; store.clearedSession = false }
    private fun session(): String {
        retireClearedSessionIfNeeded()
        val epoch = generation
        store.lastKey = key() ?: store.lastKey
        val cleared = identityCleared()
        val p = identity().toSortedMap(); val fingerprint = JsonCodec.encode(p)
        val old = store.token
        if (old != null) {
            if (store.profile != fingerprint) { val replacement = raw("POST", "$root/session", p, old, epoch)["session"] as? String ?: error("Missing support session"); synchronized(accountLock) { check(epoch == generation) { "Support reset" }; store.token = replacement; store.profile = fingerprint; store.clearedSession = cleared } }
            return requireNotNull(store.token)
        }
        val token = raw("POST", "$root/session", p, null, epoch)["session"] as? String ?: error("Missing support session")
        synchronized(accountLock) { check(epoch == generation) { "Support reset" }; store.token = token; store.profile = fingerprint; store.clearedSession = cleared }
        // The key works now: old sessions queued without one (or still waiting) are revoked.
        if (synchronized(accountLock) { pendingItems().isNotEmpty() }) resumeRevocations()
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
                dropToken()
                raw(method, root + path, body, session(), epoch)
            }
        }
    }
    suspend fun syncIdentity() = withContext(Dispatchers.IO) { mutex.withLock {
        try { session() } catch (e: AppReferSupportException) {
            if (e.status != 401 || !e.message.orEmpty().contains("support session", true)) throw e
            dropToken(); session()
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
    /**
     * Registers the push token on the current session. While an old session that still holds
     * this token waits for revocation (with or without the move flag), it is not posted now: that
     * revocation is flagged to move it, and the token goes to the current session once it succeeds.
     */
    suspend fun register(token: String) {
        if (holdForRevocation(token)) { resumeRevocations(); return }
        postPushToken(token)
    }
    private suspend fun postPushToken(token: String) { val epoch = generation; call("POST", "/push-token", mapOf("platform" to "android", "token" to token, "environment" to "production")); synchronized(accountLock) { check(epoch == generation); store.pushToken = token } }
    suspend fun remove(token: String) { val epoch = generation; call("DELETE", "/push-token", mapOf("token" to token)); synchronized(accountLock) { check(epoch == generation); store.pushToken = null } }
    suspend fun upload(bytes: ByteArray, generation: Int = this.generation): String {
        check(generation == this.generation) { "Support reset" }
        val slot = call("POST", "/attachments", mapOf("contentType" to "image/jpeg", "size" to bytes.size, "name" to "screenshot.jpg"), generation)
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            check(generation == this@SupportAPI.generation) { "Support reset" }
            val headers = slot.obj("headers").mapValues { it.value.toString() }
            val uploadUrl = slot["uploadUrl"] as? String ?: throw AppReferSupportException(0, "Bad upload address", AppReferSupportException.Reason.UPLOAD)
            val response = transport.request("PUT", uploadUrl, headers, bytes)
            check(generation == this@SupportAPI.generation) { "Support reset" }
            if (response.status !in 200..299) throw AppReferSupportException(response.status, "The screenshot didn't upload (${response.status})", AppReferSupportException.Reason.UPLOAD)
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
    internal constructor(m: Map<String, Any?>) : this(m["id"].toString(), m["subject"] as? String ?: SupportStrings.text("support.conversation.defaultSubject"), m["status"].toString(), m["preview"] as? String ?: "", m["lastMessageAt"] as? String ?: "", m["lastFrom"].toString(), (m["unread"] as? Number)?.toInt() ?: 0)
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
