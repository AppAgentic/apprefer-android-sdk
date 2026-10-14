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
 * device once its retry copy is durable. A file that exists but can't be read is never
 * overwritten (that would lose its entries); it is re-read on the next change.
 */
internal class PendingPurchaseQueue(private val file: File) {

    /** True while the queue file exists but could not be read. */
    private var unreadable = false

    var entries: List<PendingPurchase> = load()
        private set

    val isEmpty: Boolean get() = entries.isEmpty()

    fun contains(appUserId: String): Boolean = entries.any { it.appUserId == appUserId }

    fun containsToken(purchaseToken: String): Boolean = entries.any { it.purchaseToken == purchaseToken }

    /** Purchase tokens queued for [appUserId]. */
    fun tokens(appUserId: String): Set<String> =
        entries.filter { it.appUserId == appUserId }.mapTo(HashSet()) { it.purchaseToken }

    /** Adds or refreshes an entry; one entry per purchase token. A restore stays a restore. */
    fun add(entry: PendingPurchase): Boolean = mutate {
        val index = entries.indexOfFirst { it.purchaseToken == entry.purchaseToken }
        entries = if (index >= 0) {
            val merged = entry.copy(restore = entry.restore || entries[index].restore)
            entries.toMutableList().also { it[index] = merged }
        } else {
            entries + entry
        }
    }

    /**
     * Queues a failed upload, or counts another attempt for one already queued (keeping its
     * original enqueue time and owner). [countAttempt] is false for back-off answers.
     */
    fun enqueue(entry: PendingPurchase, countAttempt: Boolean): Boolean = mutate {
        val step = if (countAttempt) 1 else 0
        val index = entries.indexOfFirst { it.purchaseToken == entry.purchaseToken }
        entries = if (index >= 0) {
            val old = entries[index]
            entries.toMutableList().also { it[index] = old.copy(attempts = old.attempts + step, restore = old.restore || entry.restore) }
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

    /** Applies [change] on top of the file's entries and saves. False if not on disk. */
    private inline fun mutate(change: () -> Unit): Boolean {
        if (unreadable) {
            val pending = entries
            val reloaded = load()
            if (unreadable) {
                change()
                return false
            }
            // Keep what was queued in memory while the file was unreadable.
            entries = reloaded + pending.filter { p -> reloaded.none { it.purchaseToken == p.purchaseToken } }
        }
        change()
        return save()
    }

    /**
     * Drops entries that have failed too often or are too old. The server still gets those
     * purchases from Google's real-time developer notifications.
     */
    fun prune(nowMillis: Long): List<PendingPurchase> {
        val dropped = entries.filter { it.attempts >= MAX_ATTEMPTS || nowMillis - it.enqueuedAtMillis > MAX_AGE_MS }
        if (dropped.isEmpty() || unreadable) return emptyList()
        entries = entries - dropped.toSet()
        save()
        return dropped
    }

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
        return BillingStorage.writeAtomically(file, arr.toString())
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
