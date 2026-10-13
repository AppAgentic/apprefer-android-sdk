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
) {
    override fun toString() = "PendingPurchase(productId=$productId, appUserId=$appUserId, attempts=$attempts)"
}

/**
 * Disk-backed retry queue. The purchase token stays here until AppRefer accepts it; the
 * user keeps access meanwhile through the local fallback. Not thread-safe; owned by
 * `BillingEngine`.
 */
internal class PendingPurchaseQueue(private val file: File) {

    var entries: List<PendingPurchase> = load()
        private set

    val isEmpty: Boolean get() = entries.isEmpty()

    fun contains(appUserId: String): Boolean = entries.any { it.appUserId == appUserId }

    fun containsToken(purchaseToken: String): Boolean = entries.any { it.purchaseToken == purchaseToken }

    /** Adds or refreshes an entry; one entry per purchase token. */
    fun add(entry: PendingPurchase) {
        val index = entries.indexOfFirst { it.purchaseToken == entry.purchaseToken }
        entries = if (index >= 0) entries.toMutableList().also { it[index] = entry } else entries + entry
        save()
    }

    fun remove(purchaseToken: String) {
        entries = entries.filterNot { it.purchaseToken == purchaseToken }
        save()
    }

    fun recordAttempt(purchaseToken: String) {
        entries = entries.map { if (it.purchaseToken == purchaseToken) it.copy(attempts = it.attempts + 1) else it }
        save()
    }

    /**
     * Drops entries that have failed too often or are too old. The server still gets those
     * purchases from Google's real-time developer notifications.
     */
    fun prune(nowMillis: Long): List<PendingPurchase> {
        val dropped = entries.filter { it.attempts >= MAX_ATTEMPTS || nowMillis - it.enqueuedAtMillis > MAX_AGE_MS }
        if (dropped.isEmpty()) return emptyList()
        entries = entries - dropped.toSet()
        save()
        return dropped
    }

    private fun load(): List<PendingPurchase> = try {
        if (!file.exists()) emptyList()
        else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decode) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save() {
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
                    .put("attempts", e.attempts),
            )
        }
        BillingStorage.writeAtomically(file, arr.toString())
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
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 50
        const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
    }
}
