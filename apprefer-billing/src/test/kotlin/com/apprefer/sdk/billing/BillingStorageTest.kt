package com.apprefer.sdk.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BillingStorageTest {

    /** Same pattern as the server's ANON_ID in web/lib/billing/sdk-request.ts. */
    private val serverAnonId =
        Regex("^\\\$ARAnon:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    @Test
    fun anonymousIdFormatMatchesServer() {
        assertTrue(serverAnonId.matches(newStorage().anonymousId()))
    }

    @Test
    fun anonymousIdIsMintedOnceAndPersists() {
        val store = InMemoryKeyValueStore()
        val first = newStorage(store).anonymousId()
        assertEquals(first, newStorage(store).anonymousId())
        assertTrue(newStorage(store).hasAnonymousId)
    }

    @Test
    fun resetAnonymousIdMintsNewOne() {
        val storage = newStorage()
        val first = storage.anonymousId()
        assertNotEquals(first, storage.resetAnonymousId())
        assertEquals(storage.anonymousId(), storage.currentAppUserId)
    }

    @Test
    fun currentAppUserIdPrefersLoggedInUser() {
        val storage = newStorage()
        assertTrue(BillingStorage.isAnonymous(storage.currentAppUserId))
        storage.loggedInAppUserId = "user_1"
        assertEquals("user_1", storage.currentAppUserId)
        storage.loggedInAppUserId = null
        assertTrue(BillingStorage.isAnonymous(storage.currentAppUserId))
    }

    @Test
    fun accountTokensArePerUserAndValidated() {
        val storage = newStorage()
        storage.setAccountToken("0f8fad5b-d9cb-469f-a165-70867728950e", "a")
        storage.setAccountToken("not-a-uuid", "b")
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", storage.accountToken("a"))
        assertNull(storage.accountToken("b"))
    }

    @Test
    fun pendingAttributesClearOnlyWhatWasSent() {
        val storage = newStorage()
        storage.mergePendingAttributes(mapOf("\$email" to "a@b.com", "\$displayName" to "A"))
        val sent = storage.pendingAttributes
        storage.mergePendingAttributes(mapOf("\$email" to "new@b.com"))
        storage.clearSentAttributes(sent)
        assertEquals(mapOf("\$email" to "new@b.com"), storage.pendingAttributes)
    }

    @Test
    fun learnsEntitlementsFromServer() {
        val storage = newStorage()
        val info = CustomerInfoJson.parseResponse(FakeServer().run { owned["u"] = mutableSetOf("pro_yearly"); respond("u", false).body }).info
        storage.learnEntitlements(info)
        assertEquals(mapOf("pro_yearly" to listOf("premium")), storage.learnedEntitlements)
    }

    @Test
    fun cachedCustomerInfoPersists() {
        val dir = tempDir()
        val info = CustomerInfoJson.parseResponse(FakeServer().run { owned["u"] = mutableSetOf("p"); respond("u", false).body }).info
        newStorage(dir = dir).setCachedCustomerInfo(info)
        assertEquals(info, newStorage(dir = dir).cachedCustomerInfo())
    }

    @Test
    fun pendingIdentifyPersists() {
        val store = InMemoryKeyValueStore()
        newStorage(store).pendingIdentify = "\$ARAnon:x" to "user_1"
        assertEquals("\$ARAnon:x" to "user_1", newStorage(store).pendingIdentify)
        newStorage(store).pendingIdentify = null
        assertNull(newStorage(store).pendingIdentify)
    }

    // region Queue

    private fun entry(token: String, user: String = "u", at: Long = NOW, attempts: Int = 1) =
        PendingPurchase(token, "pro_monthly", ProductTypes.SUBSCRIPTION, user, "device-1", at, attempts)

    @Test
    fun queuePersistsAcrossLaunches() {
        val file = File(tempDir(), "q.json")
        PendingPurchaseQueue(file).add(entry("t1"))
        val reloaded = PendingPurchaseQueue(file)
        assertEquals(listOf(entry("t1")), reloaded.entries)
        assertTrue(reloaded.contains("u"))
        assertTrue(reloaded.containsToken("t1"))
    }

    @Test
    fun queueDedupesByTokenAndTracksAttempts() {
        val queue = PendingPurchaseQueue(File(tempDir(), "q.json"))
        queue.add(entry("t1"))
        queue.add(entry("t1", attempts = 3))
        queue.recordAttempt("t1")
        assertEquals(1, queue.entries.size)
        assertEquals(4, queue.entries[0].attempts)
        queue.remove("t1")
        assertTrue(queue.isEmpty)
    }

    @Test
    fun queuePrunesTooManyAttemptsAndTooOld() {
        val queue = PendingPurchaseQueue(File(tempDir(), "q.json"))
        queue.add(entry("tooMany", attempts = PendingPurchaseQueue.MAX_ATTEMPTS))
        queue.add(entry("tooOld", at = NOW - 91 * DAY))
        queue.add(entry("fine"))
        val dropped = queue.prune(NOW)
        assertEquals(setOf("tooMany", "tooOld"), dropped.map { it.purchaseToken }.toSet())
        assertEquals(listOf("fine"), queue.entries.map { it.purchaseToken })
    }

    @Test
    fun corruptQueueFileStartsEmpty() {
        val file = File(tempDir(), "q.json").apply { writeText("{not json") }
        assertTrue(PendingPurchaseQueue(file).isEmpty)
    }

    @Test
    fun queueEntryToStringHidesToken() {
        assertFalse(entry("secret-token").toString().contains("secret-token"))
        assertFalse(storePurchase("secret-token", "p").copy(orderId = "GPA.1").toString().contains("secret-token"))
    }

    // endregion
}
