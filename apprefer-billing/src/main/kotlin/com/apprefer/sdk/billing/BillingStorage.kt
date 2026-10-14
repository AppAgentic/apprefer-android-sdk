package com.apprefer.sdk.billing

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Storage could not be read or written; billing must not mint a new identity over it. */
internal class BillingStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Small key-value store, so storage logic is testable without SharedPreferences. */
internal interface BillingKeyValueStore {
    /** Null when the key is missing; throws [BillingStorageException] when it can't be read. */
    fun getString(key: String): String?

    /**
     * Returns whether the value reached disk. [durable] writes wait for the disk
     * (identity, pending identifies); others may be written in the background.
     */
    fun putString(key: String, value: String?, durable: Boolean = true): Boolean
}

/**
 * SharedPreferences-backed store. Included in Android Auto Backup by default, so the
 * anonymous id usually survives a reinstall on the same Google account.
 */
internal class SharedPreferencesKeyValueStore(context: Context) : BillingKeyValueStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = try {
        prefs.getString(key, null)
    } catch (e: Exception) {
        throw BillingStorageException("Could not read $key", e)
    }

    override fun putString(key: String, value: String?, durable: Boolean): Boolean = try {
        val editor = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }
        if (durable) editor.commit() else editor.apply().let { true }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val PREFS_NAME = "apprefer_billing"
    }
}

internal class InMemoryKeyValueStore : BillingKeyValueStore {
    private val values = HashMap<String, String>()
    override fun getString(key: String): String? = synchronized(values) { values[key] }
    override fun putString(key: String, value: String?, durable: Boolean): Boolean {
        synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
        return true
    }
}

/**
 * Persistent billing state. Methods are synchronized: attribute setters are called from
 * any thread, everything else from the engine.
 */
internal class BillingStorage(private val store: BillingKeyValueStore, val directory: File) {

    init {
        directory.mkdirs()
    }

    // region Identity

    @get:Synchronized
    val hasAnonymousId: Boolean get() = store.getString(KEY_ANONYMOUS_ID) != null

    /**
     * `$ARAnon:<UUID>`, minted once and kept in SharedPreferences. Minted only when the key
     * is missing: an unreadable or malformed value throws [BillingStorageException] rather
     * than replacing the customer's identity, and a new id that can't be saved is not used.
     */
    @Synchronized
    fun anonymousId(): String {
        val existing = store.getString(KEY_ANONYMOUS_ID)
        if (existing != null) {
            if (isAnonymous(existing)) return existing
            throw BillingStorageException("Stored anonymous id is malformed")
        }
        return resetAnonymousId()
    }

    /** Mints and saves a new anonymous id; throws if it could not be saved. */
    @Synchronized
    fun resetAnonymousId(): String {
        val id = ANONYMOUS_PREFIX + UUID.randomUUID().toString()
        if (!store.putString(KEY_ANONYMOUS_ID, id)) throw BillingStorageException("Could not save the anonymous id")
        return id
    }

    /** The logged-in app user id, or null while anonymous. Throws if it can't be saved. */
    @get:Synchronized @set:Synchronized
    var loggedInAppUserId: String?
        get() = store.getString(KEY_APP_USER_ID)
        set(value) {
            if (!store.putString(KEY_APP_USER_ID, value)) throw BillingStorageException("Could not save the app user id")
        }

    val currentAppUserId: String @Synchronized get() = loggedInAppUserId ?: anonymousId()

    // endregion

    // region Account tokens (Google obfuscatedAccountId per app user)

    @Synchronized
    fun accountToken(appUserId: String): String? =
        readStringMap(KEY_ACCOUNT_TOKENS)[appUserId]?.takeIf(::isUuid)

    /** The app user an account token (a purchase's `obfuscatedAccountId`) was issued to. */
    @Synchronized
    fun appUserIdForAccountToken(token: String): String? =
        readStringMap(KEY_ACCOUNT_TOKENS).entries.firstOrNull { it.value.equals(token, ignoreCase = true) }?.key

    @Synchronized
    fun setAccountToken(token: String, appUserId: String) {
        if (!isUuid(token)) return
        val all = readStringMap(KEY_ACCOUNT_TOKENS)
        all[appUserId] = token
        writeStringMap(KEY_ACCOUNT_TOKENS, all)
    }

    // endregion

    // region Attributes waiting to be sent

    @get:Synchronized
    val pendingAttributes: Map<String, String> get() = readStringMap(KEY_PENDING_ATTRIBUTES)

    @Synchronized
    fun mergePendingAttributes(attributes: Map<String, String>) {
        val all = readStringMap(KEY_PENDING_ATTRIBUTES)
        all.putAll(attributes)
        // Called from the app's thread: don't block it on disk.
        writeStringMap(KEY_PENDING_ATTRIBUTES, all, durable = false)
    }

    /** Removes attributes that were sent, unless they changed while the request was in flight. */
    @Synchronized
    fun clearSentAttributes(sent: Map<String, String>) {
        val all = readStringMap(KEY_PENDING_ATTRIBUTES)
        for ((k, v) in sent) if (all[k] == v) all.remove(k)
        writeStringMap(KEY_PENDING_ATTRIBUTES, all)
    }

    // endregion

    // region productId -> entitlement ids learned from server responses

    @get:Synchronized
    val learnedEntitlements: Map<String, List<String>>
        get() = parseObject(store.getString(KEY_LEARNED_ENTITLEMENTS))?.let { obj ->
            obj.keys().asSequence().associateWith { key ->
                val arr = obj.optJSONArray(key) ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
            }
        } ?: emptyMap()

    @Synchronized
    fun learnEntitlements(info: AppReferCustomerInfo) {
        val map = learnedEntitlements.mapValues { it.value.toMutableList() }.toMutableMap()
        var changed = false
        for ((id, ent) in info.entitlements.all) {
            if (ent.productIdentifier.isEmpty()) continue
            val ids = map.getOrPut(ent.productIdentifier) { mutableListOf() }
            if (id !in ids) {
                ids.add(id)
                ids.sort()
                changed = true
            }
        }
        if (!changed) return
        val obj = JSONObject()
        for ((product, ids) in map) obj.put(product, JSONArray(ids))
        store.putString(KEY_LEARNED_ENTITLEMENTS, obj.toString())
    }

    // endregion

    // region logIns that still need to reach the server

    /**
     * (anonymousId, appUserId) merges waiting for `/identify`, oldest first. They survive
     * logOut: each still has to reach the server. Includes the single entry older builds saved.
     */
    @get:Synchronized
    val pendingIdentifies: List<Pair<String, String>>
        get() {
            val list = ArrayList<Pair<String, String>>()
            parseObject(store.getString(KEY_PENDING_IDENTIFY))?.let(::decodeIdentify)?.let(list::add)
            parseArray(store.getString(KEY_PENDING_IDENTIFIES))?.let { arr ->
                for (i in 0 until arr.length()) {
                    val pair = arr.optJSONObject(i)?.let(::decodeIdentify) ?: continue
                    if (pair !in list) list.add(pair)
                }
            }
            return list
        }

    /** Adds a merge to the end of the list (once). Returns whether it was saved. */
    @Synchronized
    fun addPendingIdentify(anonymousId: String, appUserId: String): Boolean {
        val pair = anonymousId to appUserId
        val list = pendingIdentifies
        if (pair in list) return true
        return writeIdentifies(list + pair)
    }

    /** Removes [pending] if it is still in the list (a newer entry for the same ids stays once). */
    @Synchronized
    fun removePendingIdentify(pending: Pair<String, String>): Boolean {
        val list = pendingIdentifies.toMutableList()
        if (!list.remove(pending)) return true
        return writeIdentifies(list)
    }

    private fun writeIdentifies(list: List<Pair<String, String>>): Boolean {
        val arr = JSONArray()
        for ((anon, user) in list) arr.put(JSONObject().put("anonymousId", anon).put("appUserId", user))
        val saved = store.putString(KEY_PENDING_IDENTIFIES, if (list.isEmpty()) null else arr.toString())
        // The legacy single entry is now part of the list.
        if (saved) store.putString(KEY_PENDING_IDENTIFY, null)
        return saved
    }

    private fun decodeIdentify(obj: JSONObject): Pair<String, String>? {
        val anon = obj.opt("anonymousId") as? String ?: return null
        val user = obj.opt("appUserId") as? String ?: return null
        return anon to user
    }

    // endregion

    // region Cached CustomerInfo (last server answer)

    private val customerInfoFile get() = File(directory, "customer_info.json")

    @Synchronized
    fun cachedCustomerInfo(): AppReferCustomerInfo? = try {
        if (!customerInfoFile.exists()) null
        else CustomerInfoJson.decode(JSONObject(customerInfoFile.readText()))
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun setCachedCustomerInfo(info: AppReferCustomerInfo): Boolean {
        return writeAtomically(customerInfoFile, CustomerInfoJson.encode(info).toString())
    }

    // endregion

    private fun readStringMap(key: String): MutableMap<String, String> {
        val obj = parseObject(store.getString(key)) ?: return LinkedHashMap()
        val out = LinkedHashMap<String, String>()
        for (k in obj.keys()) (obj.opt(k) as? String)?.let { out[k] = it }
        return out
    }

    private fun writeStringMap(key: String, map: Map<String, String>, durable: Boolean = true) {
        store.putString(key, if (map.isEmpty()) null else JSONObject(map).toString(), durable)
    }

    companion object {
        const val ANONYMOUS_PREFIX = "\$ARAnon:"

        private const val KEY_ANONYMOUS_ID = "apprefer_billing_anonymous_id"
        private const val KEY_APP_USER_ID = "apprefer_billing_app_user_id"
        private const val KEY_ACCOUNT_TOKENS = "apprefer_billing_account_tokens"
        private const val KEY_PENDING_ATTRIBUTES = "apprefer_billing_pending_attributes"
        private const val KEY_LEARNED_ENTITLEMENTS = "apprefer_billing_learned_entitlements"
        /** Legacy single pending identify (read and migrated into the list). */
        private const val KEY_PENDING_IDENTIFY = "apprefer_billing_pending_identify"
        private const val KEY_PENDING_IDENTIFIES = "apprefer_billing_pending_identifies"

        fun isAnonymous(appUserId: String) = appUserId.startsWith(ANONYMOUS_PREFIX)

        fun live(context: Context) = BillingStorage(
            SharedPreferencesKeyValueStore(context),
            File(context.applicationContext.filesDir, "apprefer/billing"),
        )

        internal fun isUuid(value: String): Boolean = try {
            UUID.fromString(value).toString().equals(value, ignoreCase = true)
        } catch (_: IllegalArgumentException) {
            false
        }

        private fun parseObject(raw: String?): JSONObject? = try {
            raw?.let(::JSONObject)
        } catch (_: Exception) {
            null
        }

        private fun parseArray(raw: String?): JSONArray? = try {
            raw?.let(::JSONArray)
        } catch (_: Exception) {
            null
        }

        /**
         * Write to a temp file, sync it, then rename, so a crash never leaves a half-written
         * file. Returns whether the file now holds [text].
         */
        internal fun writeAtomically(file: File, text: String): Boolean = try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
        } catch (_: Exception) {
            false
        }
    }
}
