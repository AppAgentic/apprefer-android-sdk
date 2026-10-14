package com.apprefer.sdk.billing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A Google Play purchase AppRefer has not accepted yet. */
internal data class PendingPurchase(
    /** Secret: never log. */
    val purchaseToken: String,
    val productId: String,
    /** [ProductTypes] value. */
    val productType: String,
    val appUserId: String,
    val appreferId: String?,
    val enqueuedAtMillis: Long,
    val attempts: Int,
    /** Queued by Restore Purchases, so the retry is still sent as a restore. */
    val restore: Boolean = false,
) {
    override fun toString() =
        "PendingPurchase(productId=$productId, appUserId=$appUserId, attempts=$attempts, restore=$restore)"
}

/**
 * Disk-backed retry queue. The purchase token stays here until AppRefer accepts it; the
 * user keeps access meanwhile through the local fallback. Not thread-safe; owned by
 * `BillingEngine`.
 *
 * Mutations return whether the queue is on disk: callers only acknowledge a purchase on
 * device once its retry copy is durable. Entries changed in memory but not saved yet are
 * provisional ([isDurable] is false) until a later save succeeds. A file that exists but
 * can't be read is never overwritten (that would lose its entries); it is re-read on the
 * next change or [reloadIfNeeded].
 */
internal class PendingPurchaseQueue(private val file: File) {

    /** True while the queue file exists but could not be read. */
    private var unreadable = false

    /** The entries as last read from or written to disk, by purchase token. */
    private var onDisk: Map<String, PendingPurchase> = emptyMap()

    var entries: List<PendingPurchase> = load().also { onDisk = it.associateBy(PendingPurchase::purchaseToken) }
        private set

    val isEmpty: Boolean get() = entries.isEmpty()

    fun contains(appUserId: String): Boolean = entries.any { it.appUserId == appUserId }

    fun containsToken(purchaseToken: String): Boolean = entries.any { it.purchaseToken == purchaseToken }

    /** Whether [purchaseToken]'s retry copy, as held in memory, is on disk. */
    fun isDurable(purchaseToken: String): Boolean {
        val entry = entries.firstOrNull { it.purchaseToken == purchaseToken } ?: return false
        return onDisk[purchaseToken] == entry
    }

    /** Purchase tokens durably queued for [appUserId]. */
    fun tokens(appUserId: String): Set<String> =
        onDisk.values.filter { it.appUserId == appUserId }.mapTo(HashSet()) { it.purchaseToken }

    /**
     * Adds or refreshes an entry; one entry per purchase token. A restore stays a restore,
     * and an ordinary entry never takes over a restore's owner.
     */
    fun add(entry: PendingPurchase): Boolean = mutate {
        val index = entries.indexOfFirst { it.purchaseToken == entry.purchaseToken }
        entries = if (index >= 0) {
            val old = entries[index]
            val merged = if (old.restore && !entry.restore) {
                entry.copy(appUserId = old.appUserId, appreferId = old.appreferId, restore = true)
            } else {
                entry
            }
            entries.toMutableList().also { it[index] = merged }
        } else {
            entries + entry
        }
    }

    /**
     * Queues a failed upload, or counts another attempt for one already queued (keeping its
     * original enqueue time). An explicit restore takes the entry over for the restoring
     * customer, owner and restore intent together; an ordinary redelivery keeps the queued
     * owner, so it can never move a restore back. [countAttempt] is false for back-off
     * answers and `Retry-After`.
     */
    fun enqueue(entry: PendingPurchase, countAttempt: Boolean): Boolean = mutate {
        val step = if (countAttempt) 1 else 0
        val index = entries.indexOfFirst { it.purchaseToken == entry.purchaseToken }
        entries = if (index >= 0) {
            val old = entries[index]
            val updated = if (entry.restore) {
                old.copy(appUserId = entry.appUserId, appreferId = entry.appreferId, restore = true, attempts = old.attempts + step)
            } else {
                old.copy(attempts = old.attempts + step)
            }
            entries.toMutableList().also { it[index] = updated }
        } else {
            entries + entry.copy(attempts = step)
        }
    }

    fun remove(purchaseToken: String): Boolean = mutate {
        entries = entries.filterNot { it.purchaseToken == purchaseToken }
    }

    fun recordAttempt(purchaseToken: String): Boolean = mutate {
        entries = entries.map { if (it.purchaseToken == purchaseToken) it.copy(attempts = it.attempts + 1) else it }
    }

    /** Saves provisional entries. True when everything in memory is on disk. */
    fun persist(): Boolean {
        if (!unreadable && entries.associateBy(PendingPurchase::purchaseToken) == onDisk) return true
        return mutate {}
    }

    /**
     * Re-reads a file that couldn't be read earlier (entries queued meanwhile are kept and
     * saved with it), so a transient read failure never hides the queue for the session.
     */
    fun reloadIfNeeded() {
        if (unreadable) persist()
    }

    /** Applies [change] on top of the file's entries and saves. False if not on disk. */
    private inline fun mutate(change: () -> Unit): Boolean {
        if (unreadable) {
            val pending = entries
            val reloaded = load()
            if (unreadable) {
                change()
                return false
            }
            onDisk = reloaded.associateBy(PendingPurchase::purchaseToken)
            // What changed in memory while the file was unreadable is newer than the file.
            val inMemory = pending.associateBy(PendingPurchase::purchaseToken)
            entries = reloaded.map { inMemory[it.purchaseToken] ?: it } +
                pending.filter { p -> reloaded.none { it.purchaseToken == p.purchaseToken } }
        }
        change()
        return save()
    }

    /**
     * Entries that have failed too often or are too old. The engine moves each to the
     * rejected list and removes it from the queue only once that copy is on disk.
     */
    fun expired(nowMillis: Long): List<PendingPurchase> =
        if (unreadable) emptyList()
        else entries.filter { it.attempts >= MAX_ATTEMPTS || nowMillis - it.enqueuedAtMillis > MAX_AGE_MS }

    private fun load(): List<PendingPurchase> {
        if (!file.exists()) {
            unreadable = false
            return emptyList()
        }
        val text = try {
            file.readText()
        } catch (_: Exception) {
            // Missing and unreadable are different: don't start an empty queue that would
            // overwrite the file.
            unreadable = true
            return emptyList()
        }
        unreadable = false
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decode) }
        } catch (_: Exception) {
            // Corrupt (not unreadable): keep a copy for support and start over.
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
                    .put("productType", e.productType)
                    .put("appUserId", e.appUserId)
                    .put("appreferId", e.appreferId ?: JSONObject.NULL)
                    .put("enqueuedAtMs", e.enqueuedAtMillis)
                    .put("attempts", e.attempts)
                    .apply { if (e.restore) put("restore", true) },
            )
        }
        if (!BillingStorage.writeAtomically(file, arr.toString())) return false
        onDisk = entries.associateBy(PendingPurchase::purchaseToken)
        return true
    }

    private fun decode(o: JSONObject): PendingPurchase? {
        val token = o.opt("purchaseToken") as? String ?: return null
        val productId = o.opt("productId") as? String ?: return null
        val productType = o.opt("productType") as? String ?: return null
        val user = o.opt("appUserId") as? String ?: return null
        return PendingPurchase(
            purchaseToken = token,
            productId = productId,
            productType = productType,
            appUserId = user,
            appreferId = o.opt("appreferId") as? String,
            enqueuedAtMillis = (o.opt("enqueuedAtMs") as? Number)?.toLong() ?: 0L,
            attempts = (o.opt("attempts") as? Number)?.toInt() ?: 0,
            restore = o.optBoolean("restore", false),
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 50
        const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
    }
}
