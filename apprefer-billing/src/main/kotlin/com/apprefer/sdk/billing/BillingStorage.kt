package com.apprefer.sdk.billing

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Storage could not be read or written; billing must not mint a new identity over it. */
internal class BillingStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Saved before the purchase sheet opens: who started a purchase of [productId], with the
 * account token it was tagged with (null when untagged).
 */
internal data class PurchaseIntent(
    val productId: String,
    val appUserId: String,
    val accountToken: String?,
    val startedAtMillis: Long,
    val presentedOfferingContext: PresentedOfferingContext? = null,
    val bucketId: String? = null,
)

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
internal class BillingStorage(
    private val store: BillingKeyValueStore,
    val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val automaticDirectory: File = directory,
) {

    init {
        directory.mkdirs()
    }

    private val importedTokens = HashSet<String>()
    fun isMigrationPurchase(token: String): Boolean =
        token in importedTokens || importFile(token).exists()
    fun markMigrationPurchase(token: String): Boolean {
        val saved = writeAtomically(importFile(token), "readonly")
        if (saved) importedTokens.add(token)
        return saved
    }
    private fun importFile(token: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(directory, "migration-purchase-$hash.flag")
    }

    fun migrationSynced(user: String): Boolean = migrationFile(user).let {
        it.exists() && it.readText() == "done"
    }

    fun markMigrationSynced(user: String): Boolean = writeAtomically(migrationFile(user), "done")

    private fun migrationFile(user: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(user.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(directory, "migration-v2-$hash.flag")
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
    @Volatile var identityRevision: Long = 0
        private set

    @Synchronized
    fun resetAnonymousId(): String {
        val id = ANONYMOUS_PREFIX + UUID.randomUUID().toString()
        invalidateAutomaticIntents()
        if (!store.putString(KEY_ANONYMOUS_ID, id)) throw BillingStorageException("Could not save the anonymous id")
        identityRevision++
        return id
    }

    /** The logged-in app user id, or null while anonymous. Throws if it can't be saved. */
    @get:Synchronized @set:Synchronized
    var loggedInAppUserId: String?
        get() = store.getString(KEY_APP_USER_ID)
        set(value) {
            if (value != store.getString(KEY_APP_USER_ID)) invalidateAutomaticIntents()
            if (!store.putString(KEY_APP_USER_ID, value)) throw BillingStorageException("Could not save the app user id")
            identityRevision++
        }

    val currentAppUserId: String @Synchronized get() = loggedInAppUserId ?: anonymousId()

    /**
     * The Billing app user id that was current when `AppRefer.reset()` last ran (written by the
     * attribution module under [KEY_RESET_USER]), or null. While it equals the current user,
     * that user gets no install device id, even after a relaunch. Throws if it can't be read.
     */
    @get:Synchronized
    val resetUser: String? get() = store.getString(KEY_RESET_USER)

    /** Forgets the reset() marker; returns whether that reached disk. */
    @Synchronized
    fun clearResetUser(): Boolean {
        if (store.getString(KEY_RESET_USER) == null) return true
        invalidateAutomaticIntents()
        return store.putString(KEY_RESET_USER, null)
    }

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

    // region Purchase intents: who opened a purchase sheet, for purchases delivered after a
    // restart, logOut or user switch (pending payment methods can take days)

    /**
     * The saved intents, in the order they were saved (matching picks the oldest by
     * `startedAtMillis`, not by position). Starting another purchase for the same user and
     * product adds an intent and never replaces one. Throws [BillingStorageException] when they can't be
     * read. Malformed storage is quarantined (its raw text copied aside for support), the
     * entries that still decode are kept, and the intents-lost marker is saved
     * ([intentsLostAtMillis]); it throws if any of that can't be saved, so nothing is lost.
     */
    @get:Synchronized
    val purchaseIntents: List<PurchaseIntent>
        get() {
            val raw = store.getString(KEY_PURCHASE_INTENTS) ?: return emptyList()
            val arr = parseArray(raw)
            val decoded = arr?.let { a -> (0 until a.length()).map { i -> a.optJSONObject(i)?.let(::decodeIntent) } }
            val kept = decoded.orEmpty().filterNotNull()
            if (decoded != null && kept.size == decoded.size) return kept
            quarantineIntents(raw, kept)
            return kept
        }

    private fun quarantineIntents(raw: String, kept: List<PurchaseIntent>) {
        val copy = File(directory, "purchase_intents.${now()}.corrupt")
        if (!writeAtomically(copy, raw)) throw BillingStorageException("Could not quarantine malformed purchase intents")
        if (intentsLostAtMillis == null && !store.putString(KEY_INTENTS_LOST_AT, now().toString())) {
            throw BillingStorageException("Could not save the intents-lost marker")
        }
        if (!writeIntents(kept)) throw BillingStorageException("Could not rewrite the purchase intents")
    }

    /**
     * When intent storage was found malformed, while that may still hide who started an
     * open purchase; null when no intents were lost. Throws [BillingStorageException] when
     * it can't be read.
     */
    @get:Synchronized
    val intentsLostAtMillis: Long?
        get() {
            val raw = store.getString(KEY_INTENTS_LOST_AT) ?: return null
            // A damaged marker still means intents were lost; its 48-hour wait is over.
            return raw.toLongOrNull() ?: 0L
        }

    /** Clears the intents-lost marker. Returns whether it is gone from disk. */
    @Synchronized
    fun clearIntentsLost(): Boolean = store.putString(KEY_INTENTS_LOST_AT, null)

    /** Adds [intent]. Returns whether it is on disk (false also when the list can't be read). */
    @Synchronized
    fun addPurchaseIntent(intent: PurchaseIntent): Boolean {
        val list = try {
            purchaseIntents
        } catch (_: BillingStorageException) {
            return false
        }
        return writeIntents(list.filterNot { it == intent } + intent)
    }

    /** Removes [remove] from the saved intents. Returns whether the list on disk no longer holds them. */
    @Synchronized
    fun removePurchaseIntents(remove: Collection<PurchaseIntent>): Boolean {
        if (remove.isEmpty()) return true
        val list = try {
            purchaseIntents
        } catch (_: BillingStorageException) {
            return false
        }
        val next = list.filterNot { it in remove }
        if (next.size == list.size) return true
        clearAutomaticIntentAuthorizations(remove)
        return writeIntents(next)
    }

    @Synchronized fun offeringsBucketId(): String {
        store.getString("offerings_bucket")?.let { return it }
        val id = anonymousId()
        if (!store.putString("offerings_bucket", id)) throw BillingStorageException("Could not persist offerings bucket")
        return id
    }
    fun cachedOfferings(user: String): String? = try { store.getString("offerings:" + user) } catch (_: Exception) { null }
    fun saveOfferings(user: String, raw: String) { store.putString("offerings:" + user, raw, durable = false) }

    private fun writeIntents(list: List<PurchaseIntent>): Boolean {
        val arr = JSONArray()
        for (i in list) {
            arr.put(
                JSONObject()
                    .put("productId", i.productId)
                    .put("appUserId", i.appUserId)
                    .put("accountToken", i.accountToken ?: JSONObject.NULL)
                    .put("startedAtMs", i.startedAtMillis)
                    .put("presentedOfferingContext", i.presentedOfferingContext?.json())
                    .put("bucketId", i.bucketId),
            )
        }
        return store.putString(KEY_PURCHASE_INTENTS, if (list.isEmpty()) null else arr.toString())
    }

    private fun decodeIntent(o: JSONObject): PurchaseIntent? {
        val productId = o.opt("productId") as? String ?: return null
        val appUserId = o.opt("appUserId") as? String ?: return null
        val startedAt = (o.opt("startedAtMs") as? Number)?.toLong() ?: return null
        return PurchaseIntent(productId, appUserId, o.opt("accountToken") as? String, startedAt, o.optJSONObject("presentedOfferingContext")?.let(PresentedOfferingContext::parse), o.opt("bucketId") as? String)
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

    private fun automaticContextKey(user: String, key: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((user + "\u0000" + key).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun automaticContexts(): JSONObject = try {
        val file = File(automaticDirectory, "automatic-environments-v1.json")
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (error: Exception) { throw BillingStorageException("Automatic store context is unreadable", error) }
    private fun saveAutomaticContexts(value: JSONObject) {
        if (!writeAtomically(File(automaticDirectory, "automatic-environments-v1.json"), value.toString())) throw BillingStorageException("Automatic store context could not be saved")
    }
    @Synchronized
    private fun invalidateAutomaticIntents() {
        if (!File(automaticDirectory, "automatic-environments-v1.json").exists()) return
        val value = automaticContexts()
        value.remove("_pending")
        value.remove("_proofs")
        saveAutomaticContexts(value)
    }
    private fun intentContextKey(intent: PurchaseIntent, key: String): String = automaticContextKey(intent.appUserId + ":" + intent.productId + ":" + intent.startedAtMillis, key)
    @Synchronized
    fun authorizeAutomaticIntent(intent: PurchaseIntent, key: String, capability: String, token: String?) {
        val value = automaticContexts()
        val pending = value.optJSONObject("_pending") ?: JSONObject()
        pending.put(intentContextKey(intent, key), JSONObject().put("intent", intentContextKey(intent, "intent")).put("capability", capability).put("token", token).put("resetUser", resetUser ?: ""))
        value.put("_pending", pending)
        saveAutomaticContexts(value)
    }
    @Synchronized
    private fun clearAutomaticIntentAuthorizations(remove: Collection<PurchaseIntent>) {
        if (!File(automaticDirectory, "automatic-environments-v1.json").exists()) return
        val value = automaticContexts()
        val pending = value.optJSONObject("_pending") ?: return
        val fingerprints = remove.map { intentContextKey(it, "intent") }.toSet()
        val keys = pending.keys().asSequence().toList()
        for (key in keys) if (pending.optJSONObject(key)?.optString("intent") in fingerprints) pending.remove(key)
        if (pending.length() == 0) value.remove("_pending")
        saveAutomaticContexts(value)
    }
    private fun proofContextKey(user: String, key: String, token: String) = automaticContextKey(user + ":" + token, key)
    @Synchronized
    fun authorizeAutomaticProof(user: String, key: String, token: String, capability: String, intent: PurchaseIntent? = null) {
        val value = automaticContexts()
        val proofs = value.optJSONObject("_proofs") ?: JSONObject()
        val record = proofs.optJSONObject(proofContextKey(user, key, token)) ?: JSONObject()
        if (intent != null) {
            val id = intentContextKey(intent, key)
            val pending = value.optJSONObject("_pending")?.optJSONObject(id) ?: throw BillingStorageException("Automatic intent unavailable")
            val bound = pending.optString("token")
            if (bound.isNotEmpty() && bound != token) throw BillingStorageException("Automatic intent already bound")
            pending.put("token", token)
            record.put("intentKey", id)
        }
        proofs.put(proofContextKey(user, key, token), record.put("capability", capability).put("resetUser", resetUser ?: ""))
        value.put("_proofs", proofs)
        saveAutomaticContexts(value)
    }
    @Synchronized
    fun isAutomaticProof(user: String, key: String, token: String): Boolean {
        val record = automaticContexts().optJSONObject("_proofs")?.optJSONObject(proofContextKey(user, key, token)) ?: return false
        return record.optString("capability").isNotEmpty() && record.optString("resetUser") == (resetUser ?: "")
    }
    @Synchronized
    fun forgetAutomaticProof(user: String, key: String, token: String) {
        val value = automaticContexts()
        val proofs = value.optJSONObject("_proofs") ?: return
        proofs.remove(proofContextKey(user, key, token))
        if (proofs.length() == 0) value.remove("_proofs")
        saveAutomaticContexts(value)
    }
    @Synchronized
    fun isAutomaticIntent(intent: PurchaseIntent, key: String, purchase: StorePurchase): Boolean {
        val record = automaticContexts().optJSONObject("_pending")?.optJSONObject(intentContextKey(intent, key)) ?: return false
        if (record.optString("capability").isEmpty() || record.optString("resetUser") != (resetUser ?: "")) return false
        val token = record.optString("token")
        return if (token.isNotEmpty()) token == purchase.purchaseToken
        else intent.accountToken != null && purchase.obfuscatedAccountId.equals(intent.accountToken, ignoreCase = true) && purchase.purchaseTimeMillis >= intent.startedAtMillis - 300_000L
    }
    @Synchronized
    fun automaticEnvironment(user: String, key: String): BillingEnvironment? {
        val value = automaticContexts().optString(automaticContextKey(user, key))
        if (value.isEmpty()) return null
        return BillingEnvironment.entries.firstOrNull { it.raw == value } ?: throw BillingStorageException("Automatic store context is invalid")
    }
    @Synchronized
    fun setAutomaticEnvironment(user: String, key: String, environment: BillingEnvironment, acceptedToken: String? = null) {
        val known = automaticContexts().put(automaticContextKey(user, key), environment.raw)
        if (acceptedToken != null) {
            val proofs = known.optJSONObject("_proofs")
            val id = proofContextKey(user, key, acceptedToken)
            val intentKey = proofs?.optJSONObject(id)?.optString("intentKey")
            if (!intentKey.isNullOrEmpty()) known.optJSONObject("_pending")?.remove(intentKey)
            proofs?.remove(id)
        }
        saveAutomaticContexts(known)
    }

    fun purchaseEnvironment(token: String): BillingEnvironment? = BillingEnvironment.entries.firstOrNull { it.raw == readStringMap("billing_proof_environments")[token] }
    fun recordPurchaseEnvironment(token: String, environment: BillingEnvironment) {
        val known = readStringMap("billing_proof_environments")
        known[token] = environment.raw
        writeStringMap("billing_proof_environments", known)
    }

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
        /** Same key the attribution module writes on `AppRefer.reset()`. */
        const val KEY_RESET_USER = "apprefer_billing_reset_user"
        private const val KEY_ACCOUNT_TOKENS = "apprefer_billing_account_tokens"
        private const val KEY_PENDING_ATTRIBUTES = "apprefer_billing_pending_attributes"
        private const val KEY_LEARNED_ENTITLEMENTS = "apprefer_billing_learned_entitlements"
        /** Legacy single pending identify (read and migrated into the list). */
        private const val KEY_PENDING_IDENTIFY = "apprefer_billing_pending_identify"
        private const val KEY_PENDING_IDENTIFIES = "apprefer_billing_pending_identifies"
        const val KEY_PURCHASE_INTENTS = "apprefer_billing_purchase_intents"
        const val KEY_INTENTS_LOST_AT = "apprefer_billing_intents_lost_at"

        fun isAnonymous(appUserId: String) = appUserId.startsWith(ANONYMOUS_PREFIX)

        fun live(context: Context) = BillingStorage(
            SharedPreferencesKeyValueStore(context),
            File(context.applicationContext.filesDir, "apprefer/billing"),
            automaticDirectory = File(context.applicationContext.noBackupFilesDir, "apprefer/billing"),
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
