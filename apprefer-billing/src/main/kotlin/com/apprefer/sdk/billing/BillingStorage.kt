package com.apprefer.sdk.billing

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Small key-value store, so storage logic is testable without SharedPreferences. */
internal interface BillingKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/**
 * SharedPreferences-backed store. Included in Android Auto Backup by default, so the
 * anonymous id usually survives a reinstall on the same Google account.
 */
internal class SharedPreferencesKeyValueStore(context: Context) : BillingKeyValueStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    companion object {
        const val PREFS_NAME = "apprefer_billing"
    }
}

internal class InMemoryKeyValueStore : BillingKeyValueStore {
    private val values = HashMap<String, String>()
    override fun getString(key: String): String? = synchronized(values) { values[key] }
    override fun putString(key: String, value: String?) {
        synchronized(values) { if (value == null) values.remove(key) else values[key] = value }
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

    /** `$ARAnon:<UUID>`, minted once and kept in SharedPreferences. */
    @Synchronized
    fun anonymousId(): String {
        val existing = store.getString(KEY_ANONYMOUS_ID)
        if (existing != null && isAnonymous(existing)) return existing
        return resetAnonymousId()
    }

    @Synchronized
    fun resetAnonymousId(): String {
        val id = ANONYMOUS_PREFIX + UUID.randomUUID().toString()
        store.putString(KEY_ANONYMOUS_ID, id)
        return id
    }

    /** The logged-in app user id, or null while anonymous. */
    @get:Synchronized @set:Synchronized
    var loggedInAppUserId: String?
        get() = store.getString(KEY_APP_USER_ID)
        set(value) = store.putString(KEY_APP_USER_ID, value)

    val currentAppUserId: String @Synchronized get() = loggedInAppUserId ?: anonymousId()

    // endregion

    // region Account tokens (Google obfuscatedAccountId per app user)

    @Synchronized
    fun accountToken(appUserId: String): String? =
        readStringMap(KEY_ACCOUNT_TOKENS)[appUserId]?.takeIf(::isUuid)

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
        writeStringMap(KEY_PENDING_ATTRIBUTES, all)
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

    // region logIn that still needs to reach the server

    /** (anonymousId, appUserId) */
    @get:Synchronized @set:Synchronized
    var pendingIdentify: Pair<String, String>?
        get() {
            val obj = parseObject(store.getString(KEY_PENDING_IDENTIFY)) ?: return null
            val anon = obj.opt("anonymousId") as? String ?: return null
            val user = obj.opt("appUserId") as? String ?: return null
            return anon to user
        }
        set(value) = store.putString(
            KEY_PENDING_IDENTIFY,
            value?.let { JSONObject().put("anonymousId", it.first).put("appUserId", it.second).toString() },
        )

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
    fun setCachedCustomerInfo(info: AppReferCustomerInfo) {
        writeAtomically(customerInfoFile, CustomerInfoJson.encode(info).toString())
    }

    // endregion

    private fun readStringMap(key: String): MutableMap<String, String> {
        val obj = parseObject(store.getString(key)) ?: return LinkedHashMap()
        val out = LinkedHashMap<String, String>()
        for (k in obj.keys()) (obj.opt(k) as? String)?.let { out[k] = it }
        return out
    }

    private fun writeStringMap(key: String, map: Map<String, String>) {
        store.putString(key, if (map.isEmpty()) null else JSONObject(map).toString())
    }

    companion object {
        const val ANONYMOUS_PREFIX = "\$ARAnon:"

        private const val KEY_ANONYMOUS_ID = "apprefer_billing_anonymous_id"
        private const val KEY_APP_USER_ID = "apprefer_billing_app_user_id"
        private const val KEY_ACCOUNT_TOKENS = "apprefer_billing_account_tokens"
        private const val KEY_PENDING_ATTRIBUTES = "apprefer_billing_pending_attributes"
        private const val KEY_LEARNED_ENTITLEMENTS = "apprefer_billing_learned_entitlements"
        private const val KEY_PENDING_IDENTIFY = "apprefer_billing_pending_identify"

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

        /** Write to a temp file then rename, so a crash never leaves a half-written file. */
        internal fun writeAtomically(file: File, text: String) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (_: Exception) {
            }
        }
    }
}
