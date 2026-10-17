package com.apprefer.sdk.engagement

import com.apprefer.sdk.APPREFER_SDK_VERSION
import com.apprefer.sdk.AppReferConfig
import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** The current install, account epoch and identity serial, readable without suspending. */
internal data class EngagementSnapshot(
    val slot: String,
    val installId: String,
    /** null while an account change is in progress or unconfirmed. */
    val accountEpoch: Int?,
    /** Advances on every reset, logout, sign-in, deletion and server-side account change. */
    val serial: Int,
    val header: String,
    val trackedEvents: Set<String>?,
) {
    /** Queued events carry this and get the header only while it is current. */
    val stamp: String get() = "$slot:$installId:$serial"
}

internal data class EngagementDeviceState(val timezone: String, val locale: String?, val collectionEnabled: Boolean, val permission: String)

/** Small key-value store for non-secret state (SharedPreferences in the app). */
internal interface EngagementPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** The install credential, kept encrypted with a Keystore key (see [KeystoreCredentialStore]). */
internal interface EngagementCredentialStore {
    sealed class Read { data class Found(val value: String) : Read(); object Missing : Read(); object Unavailable : Read() }
    fun read(slot: String): Read
    /** False when it couldn't be saved. */
    fun write(slot: String, value: String): Boolean
    fun delete(slot: String)
}

internal typealias EngagementTransport = suspend (method: String, path: String, headers: Map<String, String>, body: Map<String, Any?>?) -> Pair<Int?, Map<String, Any?>?>

/**
 * Lifecycle engagement client: install registration, credential storage, device state,
 * preferences, account binding and receipts (contract: web/lib/engagement). Every operation runs
 * in call order under one fair mutex, so a logout never overtakes a sign-in that started before
 * it; results that arrive after a reset, logout, sign-in or key switch are discarded
 * (generation check after every request).
 */
internal class EngagementClient(
    private val credentials: EngagementCredentialStore,
    private val prefs: EngagementPrefs,
    private val transport: EngagementTransport = liveTransport(AppReferConfig.FALLBACK_URL),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val deviceState: () -> EngagementDeviceState,
    private val deviceId: () -> String? = { null },
    /** Mirrors the identity into the notification renderer (epoch -1 = nothing matches). */
    private val identitySink: (String?, Int) -> Unit = { _, _ -> },
) {
    val snapshot = AtomicReference<EngagementSnapshot?>(null)
    private val mutex = Mutex()

    @Volatile var status: AppReferEngagementStatus = AppReferEngagementStatus.NotStarted; private set
    private var apiKey: String? = null
    private var explicitKey = false
    private var started = false
    private var options = AppReferEngagementOptions()
    private var credential: Credential? = null
    private var accountEpoch: Int? = null
    private var pushToken: String? = null
    @Volatile private var generation = 0
    private var registrationRetried = false

    internal data class Credential(val installId: String, val secret: String, val pendingDelete: Boolean = false) {
        fun encode(): String = JsonCodec.encode(mapOf("installId" to installId, "secret" to secret, "pendingDelete" to pendingDelete))
        companion object {
            fun decode(raw: String): Credential? = runCatching {
                val m = JsonCodec.decodeObject(raw)
                Credential(m["installId"] as String, m["secret"] as String, m["pendingDelete"] == true)
            }.getOrNull()
        }
    }

    private val slot: String? get() = apiKey?.let { slotFor(it) }
    private fun key(name: String) = "apprefer_engagement_${name}_${slot ?: "none"}"
    private var serial: Int
        get() = prefs.getString(key("serial"))?.toIntOrNull() ?: 0
        set(v) = prefs.putString(key("serial"), v.toString())
    private var pendingLogout: Boolean
        get() = prefs.getString(key("pending_logout")) == "1"
        set(v) = prefs.putString(key("pending_logout"), if (v) "1" else null)
    private var disabledUntil: Long
        get() = prefs.getString(key("disabled_until"))?.toLongOrNull() ?: 0L
        set(v) = prefs.putString(key("disabled_until"), if (v > 0) v.toString() else null)
    private var lastSync: Pair<String, Long>?
        get() {
            val hash = prefs.getString(key("sync_hash")) ?: return null
            val at = prefs.getString(key("sync_at"))?.toLongOrNull() ?: return null
            return hash to at
        }
        set(v) { prefs.putString(key("sync_hash"), v?.first); prefs.putString(key("sync_at"), v?.second?.toString()) }

    private fun publish() {
        val c = credential
        val s = slot
        if (s == null || c == null || c.pendingDelete || status !is AppReferEngagementStatus.Active) {
            snapshot.set(null); identitySink(null, -1); return
        }
        snapshot.set(EngagementSnapshot(s, c.installId, accountEpoch, serial, "${c.installId}.${c.secret}", options.trackedEvents))
        identitySink(c.installId, accountEpoch ?: -1)
    }

    /** Identity changed (or is about to): old stamps, receipts and navigation stop matching. */
    private fun beginIdentityChange() {
        generation++
        serial += 1
        accountEpoch = null
        dropReceipts { true }
        publish()
    }

    // region start

    suspend fun start(apiKey: String, explicit: Boolean, options: AppReferEngagementOptions): AppReferEngagementStatus = mutex.withLock {
        if (this.apiKey != apiKey) {
            generation++
            credential = null; accountEpoch = null; registrationRetried = false
            status = AppReferEngagementStatus.Pending
            this.apiKey = apiKey
            publish()
        }
        this.apiKey = apiKey; explicitKey = explicit; this.options = options; started = true
        activate()
    }

    suspend fun configurationChanged(apiKey: String) {
        if (!started || explicitKey || apiKey == this.apiKey) return
        start(apiKey, false, options)
    }

    suspend fun becameActive() { if (started) mutex.withLock { activate() } }

    val isStarted: Boolean get() = started

    private suspend fun activate(): AppReferEngagementStatus {
        val key = apiKey ?: return AppReferEngagementStatus.NotStarted
        if (disabledUntil > now()) { status = AppReferEngagementStatus.Disabled; publish(); return status }
        val gen = generation
        if (credential == null) {
            when (val read = credentials.read(slot!!)) {
                is EngagementCredentialStore.Read.Found -> credential = Credential.decode(read.value) ?: run { credentials.delete(slot!!); null }
                EngagementCredentialStore.Read.Missing -> {}
                // Locked or failing: never mint a second install meanwhile.
                EngagementCredentialStore.Read.Unavailable -> { status = AppReferEngagementStatus.Pending; publish(); return status }
            }
        }
        if (credential?.pendingDelete == true && !sendDelete(key)) { status = AppReferEngagementStatus.Pending; publish(); return status }
        if (credential == null && !register(key, gen)) { publish(); return status }
        if (gen != generation) return status
        val c = credential ?: return status
        if (prefs.getString(PENDING_LOGOUT_ANY) == "1") {
            prefs.putString(PENDING_LOGOUT_ANY, null); pendingLogout = true; serial += 1
        }
        if (pendingLogout) {
            val (code, body) = send("POST", "/api/engagement/v1/installs/me/logout", emptyMap(), key, c)
            if (gen != generation) return status
            when {
                code == 200 -> { pendingLogout = false; accountEpoch = (body?.get("accountEpoch") as? Number)?.toInt() }
                handleFailure(code, body) -> return status
                else -> { status = AppReferEngagementStatus.Pending; publish(); return status }
            }
        }
        val (code, body) = send("GET", "/api/engagement/v1/installs/me", null, key, c)
        if (gen != generation) return status
        @Suppress("UNCHECKED_CAST") val install = body?.get("install") as? Map<String, Any?>
        if (code != 200 || install == null) {
            if (code == 401 && !registrationRetried) {
                registrationRetried = true
                forgetCredential()
                return activate()
            }
            if (!handleFailure(code, body)) status = AppReferEngagementStatus.Pending
            publish(); return status
        }
        observeEpoch((install["accountEpoch"] as? Number)?.toInt())
        status = AppReferEngagementStatus.Active(c.installId)
        publish()
        syncDevice(false)
        flushReceipts()
        return status
    }

    private fun forgetCredential() {
        slot?.let { credentials.delete(it) }
        credential = null; accountEpoch = null; lastSync = null
        serial += 1
        dropReceipts { true }
        publish()
    }

    private suspend fun register(key: String, gen: Int): Boolean {
        val state = deviceState()
        val body = linkedMapOf<String, Any?>("platform" to "android", "timezone" to state.timezone, "collectionEnabled" to state.collectionEnabled)
        state.locale?.let { body["locale"] = it }
        deviceId()?.let { body["deviceId"] = it }
        val (code, json) = send("POST", "/api/engagement/v1/installs", body, key, null)
        val installId = json?.get("installId") as? String
        val secret = json?.get("installSecret") as? String
        if (code != 201 || installId == null || secret == null) {
            if (!handleFailure(code, json)) status = AppReferEngagementStatus.Pending
            return false
        }
        val minted = Credential(installId, secret)
        if (gen != generation || this.apiKey != key || !credentials.write(slotFor(key), minted.encode())) {
            // Stale or unsaveable: an install nobody can use. Remove it.
            send("DELETE", "/api/engagement/v1/installs/me", null, key, minted)
            if (gen == generation) status = AppReferEngagementStatus.Pending
            return false
        }
        credential = minted
        accountEpoch = 0
        serial += 1
        lastSync = null
        return true
    }

    private fun observeEpoch(epoch: Int?) {
        if (epoch == null || accountEpoch == epoch) return
        if (accountEpoch != null) serial += 1
        accountEpoch = epoch
    }

    /** True when handled as a terminal state (engagement disabled, invalid install). */
    private fun handleFailure(code: Int?, body: Map<String, Any?>?): Boolean {
        val error = errorCode(body)
        if (code == 404 && error == "ENGAGEMENT_DISABLED") {
            disabledUntil = now() + 6 * 3600_000L
            status = AppReferEngagementStatus.Disabled
            publish()
            return true
        }
        if (code == 401 && error == "INVALID_INSTALL") {
            forgetCredential()
            status = AppReferEngagementStatus.Pending
            return true
        }
        return false
    }

    // endregion
    // region device state

    suspend fun setPushToken(token: String?) = mutex.withLock { pushToken = token; syncDevice(false) }

    suspend fun refreshDeviceState() = mutex.withLock { syncDevice(false) }

    private fun capabilities(): Map<String, Any?> = mapOf("schema" to 1, "images" to options.allowedImageHosts.isNotEmpty(), "actions" to true)

    private suspend fun syncDevice(force: Boolean) {
        val key = apiKey ?: return
        val c = credential ?: return
        if (status !is AppReferEngagementStatus.Active) return
        val gen = generation
        val state = deviceState()
        val body = linkedMapOf<String, Any?>(
            "timezone" to state.timezone, "collectionEnabled" to state.collectionEnabled,
            "notificationPermission" to state.permission, "capabilities" to capabilities(),
        )
        state.locale?.let { body["locale"] = it }
        pushToken?.let { body["pushToken"] = it }
        val hash = sha256(JsonCodec.encode(body.toSortedMap()))
        val last = lastSync
        if (!force && last != null && last.first == hash && now() - last.second < 86_400_000L) return
        val (code, json) = send("PATCH", "/api/engagement/v1/installs/me", body, key, c)
        if (gen != generation) return
        if (code == 200) {
            lastSync = hash to now()
            val before = serial
            @Suppress("UNCHECKED_CAST")
            observeEpoch(((json?.get("install") as? Map<String, Any?>)?.get("accountEpoch") as? Number)?.toInt())
            if (before != serial) publish()
        } else handleFailure(code, json)
    }

    // endregion
    // region identity

    suspend fun identify(assertion: String) {
        val result: Result<Unit> = mutex.withLock { runIdentify(assertion) }
        result.getOrThrow()
    }

    private suspend fun runIdentify(assertion: String): Result<Unit> {
        val key = apiKey; val c = credential
        if (status !is AppReferEngagementStatus.Active || key == null || c == null) return Result.failure(AppReferEngagementException(AppReferEngagementException.Reason.NOT_STARTED))
        beginIdentityChange()
        val gen = generation
        val (code, body) = send("POST", "/api/engagement/v1/installs/me/account", mapOf("assertion" to assertion), key, c)
        if (gen != generation) return Result.failure(AppReferEngagementException(AppReferEngagementException.Reason.IDENTITY_CHANGED))
        val epoch = (body?.get("accountEpoch") as? Number)?.toInt()
        if (code == 200 && epoch != null) {
            pendingLogout = false
            accountEpoch = epoch
            lastSync = null
            publish()
            syncDevice(true)
            return Result.success(Unit)
        }
        val error = if (code == 401 && errorCode(body) == "INVALID_ASSERTION") AppReferEngagementException(AppReferEngagementException.Reason.INVALID_ASSERTION) else failure(code, body)
        if (!handleFailure(code, body)) reverify(gen)
        return Result.failure(error)
    }

    private suspend fun reverify(gen: Int) {
        val key = apiKey ?: return; val c = credential ?: return
        val (code, body) = send("GET", "/api/engagement/v1/installs/me", null, key, c)
        if (gen != generation || code != 200) return
        @Suppress("UNCHECKED_CAST")
        observeEpoch(((body?.get("install") as? Map<String, Any?>)?.get("accountEpoch") as? Number)?.toInt())
        publish()
    }

    suspend fun logout() = mutex.withLock {
        if (!started || credential == null) {
            // Not started in this process: whichever slot starts next logs out first.
            prefs.putString(PENDING_LOGOUT_ANY, "1"); generation++
            return@withLock
        }
        beginIdentityChange()
        pendingLogout = true
        val key = apiKey; val c = credential
        if (status !is AppReferEngagementStatus.Active || key == null || c == null) return@withLock
        val gen = generation
        val (code, body) = send("POST", "/api/engagement/v1/installs/me/logout", emptyMap(), key, c)
        if (gen != generation) return@withLock
        if (code == 200) {
            pendingLogout = false
            accountEpoch = (body?.get("accountEpoch") as? Number)?.toInt()
            lastSync = null
            publish()
        } else handleFailure(code, body)
    }

    suspend fun deleteInstall() = mutex.withLock {
        val key = apiKey ?: return@withLock
        if (credential == null) (credentials.read(slotFor(key)) as? EngagementCredentialStore.Read.Found)?.let { credential = Credential.decode(it.value) }
        val marked = credential?.copy(pendingDelete = true) ?: return@withLock
        beginIdentityChange()
        credential = marked
        credentials.write(slotFor(key), marked.encode())
        sendDelete(key)
        status = if (credential == null) AppReferEngagementStatus.NotStarted else AppReferEngagementStatus.Pending
        started = false
        publish()
    }

    private suspend fun sendDelete(key: String): Boolean {
        val c = credential ?: return true
        val (code, _) = send("DELETE", "/api/engagement/v1/installs/me", null, key, c)
        // 401: already gone. 404 disabled: nothing there can be sent.
        if (code != 200 && code != 401 && code != 404) return false
        credentials.delete(slotFor(key))
        credential = null; accountEpoch = null; pendingLogout = false; lastSync = null
        return true
    }

    // endregion
    // region preferences

    suspend fun preferences(): AppReferEngagementPreferences {
        val result: Result<AppReferEngagementPreferences> = mutex.withLock {
            val key = apiKey; val c = credential
            if (status !is AppReferEngagementStatus.Active || key == null || c == null) return@withLock Result.failure(AppReferEngagementException(AppReferEngagementException.Reason.NOT_STARTED))
            val gen = generation
            val (code, body) = send("GET", "/api/engagement/v1/installs/me/preferences", null, key, c)
            if (gen != generation) return@withLock Result.failure(AppReferEngagementException(AppReferEngagementException.Reason.IDENTITY_CHANGED))
            val prefs = parsePreferences(body)
            if (code == 200 && prefs != null) Result.success(prefs) else { handleFailure(code, body); Result.failure(failure(code, body)) }
        }
        return result.getOrThrow()
    }

    suspend fun setPreferences(choices: Map<String, Boolean>): AppReferEngagementPreferences {
        val result: Result<AppReferEngagementPreferences> = mutex.withLock {
            if (choices.isEmpty()) return@withLock Result.failure(AppReferEngagementException(AppReferEngagementException.Reason.SERVER, 400, "INVALID_BODY"))
            val key = apiKey; val c = credential
            if (status !is AppReferEngagementStatus.Active || key == null || c == null) return@withLock Result.failure(AppReferEngagementException(AppReferEngagementException.Reason.NOT_STARTED))
            // Only for the identity in use now: never mid sign-in or sign-out.
            if (accountEpoch == null) return@withLock Result.failure(AppReferEngagementException(AppReferEngagementException.Reason.IDENTITY_CHANGED))
            val gen = generation
            val (code, body) = send("PUT", "/api/engagement/v1/installs/me/preferences", choices, key, c)
            if (gen != generation) return@withLock Result.failure(AppReferEngagementException(AppReferEngagementException.Reason.IDENTITY_CHANGED))
            if (code == 409) { reverify(gen); return@withLock Result.failure(AppReferEngagementException(AppReferEngagementException.Reason.IDENTITY_CHANGED)) }
            val prefs = parsePreferences(body)
            if (code == 200 && prefs != null) Result.success(prefs) else { handleFailure(code, body); Result.failure(failure(code, body)) }
        }
        return result.getOrThrow()
    }

    private fun parsePreferences(body: Map<String, Any?>?): AppReferEngagementPreferences? {
        @Suppress("UNCHECKED_CAST") val purposes = body?.get("purposes") as? Map<String, Any?> ?: return null
        fun granted(name: String) = (purposes[name] as? Map<*, *>)?.get("granted") as? Boolean
        return AppReferEngagementPreferences(granted("marketing") ?: return null, granted("reminders") ?: return null, granted("service") ?: return null)
    }

    private fun failure(code: Int?, body: Map<String, Any?>?): AppReferEngagementException = when {
        code == null -> AppReferEngagementException(AppReferEngagementException.Reason.UNAVAILABLE)
        code == 404 && errorCode(body) == "ENGAGEMENT_DISABLED" -> AppReferEngagementException(AppReferEngagementException.Reason.DISABLED)
        code == 409 -> AppReferEngagementException(AppReferEngagementException.Reason.IDENTITY_CHANGED)
        code >= 500 || code == 429 -> AppReferEngagementException(AppReferEngagementException.Reason.UNAVAILABLE)
        else -> AppReferEngagementException(AppReferEngagementException.Reason.SERVER, code, errorCode(body))
    }

    // endregion
    // region receipts

    internal data class Receipt(val slot: String, val installId: String, val accountEpoch: Int, val serial: Int, val messageId: String,
                                val token: String, val kind: String, val actionId: String?, val at: Long, val attempts: Int)

    private fun loadReceipts(): MutableList<Receipt> = runCatching {
        @Suppress("UNCHECKED_CAST")
        (JsonCodec.decodeObject(prefs.getString(RECEIPTS) ?: return mutableListOf())["items"] as List<Map<String, Any?>>).map {
            Receipt(it["slot"] as String, it["installId"] as String, (it["accountEpoch"] as Number).toInt(), (it["serial"] as Number).toInt(),
                it["messageId"] as String, it["token"] as String, it["kind"] as String, it["actionId"] as? String, (it["at"] as Number).toLong(), (it["attempts"] as Number).toInt())
        }.toMutableList()
    }.getOrElse { mutableListOf() }

    private fun saveReceipts(items: List<Receipt>) {
        val fresh = items.filter { now() - it.at < 7 * 86_400_000L && it.attempts < 10 }.takeLast(100)
        prefs.putString(RECEIPTS, JsonCodec.encode(mapOf("items" to fresh.map {
            mapOf("slot" to it.slot, "installId" to it.installId, "accountEpoch" to it.accountEpoch, "serial" to it.serial, "messageId" to it.messageId,
                "token" to it.token, "kind" to it.kind, "actionId" to it.actionId, "at" to it.at, "attempts" to it.attempts)
        })))
    }

    private fun dropReceipts(predicate: (Receipt) -> Boolean) = saveReceipts(loadReceipts().filterNot(predicate))

    /** Queues a receipt for a notification that matched this install and epoch. */
    suspend fun queueReceipt(kind: String, messageId: String, token: String, installId: String, epoch: Int, actionId: String?, at: Long) {
        val snap = snapshot.get() ?: return
        if (snap.installId != installId || snap.accountEpoch != epoch) return
        val items = loadReceipts()
        if (items.any { it.messageId == messageId && it.kind == kind }) return
        items.add(Receipt(snap.slot, installId, epoch, snap.serial, messageId, token, kind, actionId, at, 0))
        saveReceipts(items)
        mutex.withLock { flushReceipts() }
    }

    val pendingReceiptCount: Int get() = loadReceipts().size

    private suspend fun flushReceipts() {
        val key = apiKey ?: return; val c = credential ?: return; val s = slot ?: return; val epoch = accountEpoch ?: return
        if (status !is AppReferEngagementStatus.Active) return
        val gen = generation
        val currentSerial = serial
        // An earlier identity's receipts are never sent under the current one.
        dropReceipts { it.slot == s && (it.installId != c.installId || it.accountEpoch != epoch || it.serial != currentSerial) }
        val batch = loadReceipts().filter { it.slot == s }.take(20)
        if (batch.isEmpty()) return
        val body = mapOf("receipts" to batch.map { r ->
            linkedMapOf<String, Any?>("messageId" to r.messageId, "token" to r.token, "kind" to r.kind, "occurredAt" to r.at).apply { r.actionId?.let { put("actionId", it) } }
        })
        val (code, json) = send("POST", "/api/engagement/v1/installs/me/receipts", body, key, c)
        if (gen != generation) return
        val sentKeys = batch.map { it.messageId to it.kind }.toSet()
        when {
            // Every answered item is final (accepted, duplicate or rejected); a malformed batch too.
            code == 200 || code == 400 -> {
                saveReceipts(loadReceipts().filterNot { (it.messageId to it.kind) in sentKeys && it.slot == s })
                if (code == 200 && batch.size == 20) flushReceipts()
            }
            handleFailure(code, json) -> {}
            else -> saveReceipts(loadReceipts().map { if ((it.messageId to it.kind) in sentKeys) it.copy(attempts = it.attempts + 1) else it })
        }
    }

    // endregion

    internal fun stateForTesting(): Triple<Int?, Int, Boolean> = Triple(accountEpoch, serial, pendingLogout)

    private suspend fun send(method: String, path: String, body: Map<String, Any?>?, key: String, c: Credential?): Pair<Int?, Map<String, Any?>?> {
        val headers = linkedMapOf("X-AppRefer-Key" to key, "X-SDK-Version" to APPREFER_SDK_VERSION)
        c?.let { headers["X-AppRefer-Install"] = "${it.installId}.${it.secret}" }
        return try { transport(method, path, headers, body) } catch (_: Exception) { null to null }
    }

    companion object {
        private const val RECEIPTS = "apprefer_engagement_receipts"
        private const val PENDING_LOGOUT_ANY = "apprefer_engagement_pending_logout_any"

        fun slotFor(apiKey: String): String = sha256(apiKey).take(16)
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun errorCode(body: Map<String, Any?>?): String? = (body?.get("error") as? Map<*, *>)?.get("code") as? String

        fun liveTransport(baseUrl: String): EngagementTransport = { method, path, headers, body ->
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = 15_000; readTimeout = 15_000
                        useCaches = false
                        instanceFollowRedirects = false
                        headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    }
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(JsonCodec.encode(body).toByteArray(Charsets.UTF_8)) }
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.use { s -> s.readBytes().toString(Charsets.UTF_8) }
                    code to text?.let { runCatching { JsonCodec.decodeObject(it) }.getOrNull() }
                } catch (_: IOException) { null to null }
                finally { connection?.disconnect() }
            }
        }
    }
}
