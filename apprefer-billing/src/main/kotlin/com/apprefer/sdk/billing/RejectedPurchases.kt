package com.apprefer.sdk.billing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A Google Play purchase AppRefer refused for good (400/413/422), that had no usable store
 * data, or that the retry queue gave up on. Saved on the device before the purchase is
 * acknowledged (it is never consumed), so support can recover it and
 * `AppReferBilling.restorePurchases()` sends it again.
 */
data class RejectedPurchase(
    /** The Play purchase token; empty when Play gave none. Secret: never log. */
    val purchaseToken: String,
    val productId: String,
    /** `subscription`, `non_consumable` or `consumable`; null when unknown. */
    val productType: String?,
    val orderId: String?,
    /** The customer it was sent for; null when it never got that far. */
    val appUserId: String?,
    val appreferId: String?,
    /** The purchase's `obfuscatedAccountId`. */
    val accountToken: String?,
    /** Why AppRefer did not take it. */
    val reason: String,
    val rejectedAtMillis: Long,
    /** Play's purchase JSON, kept when the purchase had no usable store data. */
    val originalJson: String? = null,
    /** Set when the owner was a fallback (who started it was lost), for support to check. */
    val supportNote: String? = null,
    val migrationOnly: Boolean = false,
) {
    internal val key: String get() = if (migrationOnly) "migration:${appUserId?.length}:$appUserId:$purchaseToken" else purchaseToken.ifEmpty { "$productId@$orderId" }

    override fun toString() =
        "RejectedPurchase(orderId=$orderId, productId=$productId, appUserId=$appUserId, reason=$reason)"
}

/**
 * Disk-backed list of rejected purchases. Like [PendingPurchaseQueue], it tracks which
 * entries are on disk: a purchase is acknowledged only once its copy is durable
 * ([isDurable]), never from an entry held only in memory. A file that exists but can't be
 * read is never overwritten; it is re-read on the next change. Not thread-safe; owned by
 * `BillingEngine`.
 */
internal class RejectedPurchaseLog(private val file: File) {

    private var unreadable = false

    /** Entries as last read from or written to disk, by key. */
    private var onDisk: Map<String, RejectedPurchase> = emptyMap()

    /** Keys removed while the file was unreadable, kept out of the later merge. */
    private val removedWhileUnreadable = HashSet<String>()

    var entries: List<RejectedPurchase> = load().also { onDisk = it.associateBy(RejectedPurchase::key) }
        private set

    fun containsToken(purchaseToken: String): Boolean =
        purchaseToken.isNotEmpty() && entries.any { !it.migrationOnly && it.purchaseToken == purchaseToken }

    /** Whether [purchaseToken]'s entry, as held in memory, is on disk. */
    fun isDurable(purchaseToken: String): Boolean {
        val entry = entries.firstOrNull { !it.migrationOnly && it.purchaseToken == purchaseToken } ?: return false
        return onDisk[entry.key] == entry
    }

    /** Adds (or replaces) the entry. True only once the list holding it is on disk. */
    fun add(entry: RejectedPurchase): Boolean {
        mutate {
            // No cap: an entry leaves only once AppRefer records its purchase.
            entries = entries.filterNot { it.key == entry.key } + entry
        }
        return onDisk[entry.key] == entry
    }

    /** Drops the entry once AppRefer has recorded the purchase. */
    fun remove(purchaseToken: String): Boolean {
        if (purchaseToken.isEmpty()) return true
        if (unreadable) removedWhileUnreadable.add(purchaseToken)
        if (entries.none { it.purchaseToken == purchaseToken } && !unreadable) return true
        return mutate { entries = entries.filterNot { it.purchaseToken == purchaseToken } }
    }

    /** Saves provisional entries. True when everything in memory is on disk. */
    fun persist(): Boolean {
        if (!unreadable && entries.associateBy(RejectedPurchase::key) == onDisk) return true
        return mutate {}
    }

    /** Re-reads a file that couldn't be read earlier. */
    fun reloadIfNeeded() {
        if (unreadable) persist()
    }

    private inline fun mutate(change: () -> Unit): Boolean {
        if (unreadable) {
            val pending = entries
            val reloaded = load()
            if (unreadable) {
                change()
                return false
            }
            onDisk = reloaded.associateBy(RejectedPurchase::key)
            val inMemory = pending.associateBy(RejectedPurchase::key)
            entries = reloaded.filterNot { it.key in removedWhileUnreadable }.map { inMemory[it.key] ?: it } +
                pending.filter { p -> reloaded.none { it.key == p.key } }
            removedWhileUnreadable.clear()
        }
        change()
        return save()
    }

    private fun load(): List<RejectedPurchase> {
        if (!file.exists()) {
            unreadable = false
            return emptyList()
        }
        val text = try {
            file.readText()
        } catch (_: Exception) {
            unreadable = true
            return emptyList()
        }
        unreadable = false
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decode) }
        } catch (_: Exception) {
            // Corrupt: keep a copy for support and start over.
            file.renameTo(File(file.parentFile, file.name + ".corrupt"))
            emptyList()
        }
    }

    private fun save(): Boolean {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("purchaseToken", e.purchaseToken)
                    .put("productId", e.productId)
                    .put("productType", e.productType ?: JSONObject.NULL)
                    .put("orderId", e.orderId ?: JSONObject.NULL)
                    .put("appUserId", e.appUserId ?: JSONObject.NULL)
                    .put("appreferId", e.appreferId ?: JSONObject.NULL)
                    .put("accountToken", e.accountToken ?: JSONObject.NULL)
                    .put("reason", e.reason)
                    .put("rejectedAtMs", e.rejectedAtMillis)
                    .put("originalJson", e.originalJson ?: JSONObject.NULL)
                    .put("supportNote", e.supportNote ?: JSONObject.NULL)
                    .put("migrationOnly", e.migrationOnly),
            )
        }
        if (!BillingStorage.writeAtomically(file, arr.toString())) return false
        onDisk = entries.associateBy(RejectedPurchase::key)
        return true
    }

    private fun decode(o: JSONObject): RejectedPurchase? {
        fun str(key: String) = o.opt(key) as? String
        return RejectedPurchase(
            purchaseToken = str("purchaseToken") ?: return null,
            productId = str("productId") ?: return null,
            productType = str("productType"),
            orderId = str("orderId"),
            appUserId = str("appUserId"),
            appreferId = str("appreferId"),
            accountToken = str("accountToken"),
            reason = str("reason") ?: "",
            rejectedAtMillis = (o.opt("rejectedAtMs") as? Number)?.toLong() ?: 0L,
            originalJson = str("originalJson"),
            supportNote = str("supportNote"),
            migrationOnly = o.optBoolean("migrationOnly", false),
        )
    }
}
