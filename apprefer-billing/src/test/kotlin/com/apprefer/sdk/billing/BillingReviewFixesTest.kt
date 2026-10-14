package com.apprefer.sdk.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Tests for the fixes from the October 2026 billing review (Astra). */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingReviewFixesTest {

    private val premium = "premium"

    private fun Harness.purchaseBodies() =
        server.requests.filter { it.url.contains("/purchases") }.map { JSONObject(it.body!!) }

    private fun ok(vararg purchases: StorePurchase) = StorePurchasesUpdate(BillingResponseCode.OK, null, purchases.toList())

    private suspend fun TestScope.runPurchase(h: Harness, productId: String, productType: String, beforeUpdate: suspend () -> Unit = {}, update: StorePurchasesUpdate) =
        async {
            runCatching { h.engine.purchase(productId, productType) { BillingResponseCode.OK to null } }
        }.also {
            advanceUntilIdle()
            beforeUpdate()
            h.play.purchases += update.purchases.map { it.copy(isSubscription = productType == ProductTypes.SUBSCRIPTION) }
            h.play.listener!!.invoke(update)
        }

    // region Device purchases merged into server answers

    @Test
    fun untaggedPurchaseUnlocksEvenWhenServerReachableAndNotQueued() = runTest {
        val h = Harness(this, "user_1")
        h.play.purchases += storePurchase("t1", "pro_monthly", acknowledged = true, accountId = null)
        val info = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(AppReferCustomerInfo.Source.SERVER, info.source)
        assertTrue(info.entitlements[premium]!!.isActive)
    }

    @Test
    fun ownTokenPurchaseUnlocksAfterUploadWasRejected() = runTest {
        val h = Harness(this, "user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT) // learn the account token
        h.play.purchases += storePurchase("t1", "pro_monthly", acknowledged = true, accountId = h.server.tokenFor("user_1"))
        h.server.purchasesStatus = 400
        h.engine.syncPurchases()
        h.play.purchases.replaceAll { it.copy(isAcknowledged = true) }
        assertTrue(h.queue.isEmpty)
        val info = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(AppReferCustomerInfo.Source.SERVER, info.source)
        assertTrue(info.entitlements[premium]!!.isActive)
    }

    @Test
    fun ownTokenPurchaseUnlocksAfterQueueGaveUp() = runTest {
        val h = Harness(this, "user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.play.purchases += storePurchase("t1", "pro_monthly", acknowledged = true, accountId = h.server.tokenFor("user_1"))
        h.queue.add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, PendingPurchaseQueue.MAX_ATTEMPTS))
        h.server.purchasesStatus = 503
        val info = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertTrue(h.queue.isEmpty) // pruned
        assertTrue(info.entitlements[premium]!!.isActive)
    }

    @Test
    fun anotherUsersPurchaseIsNotMergedWhileServerReachableButUnlocksInOutage() = runTest {
        val h = Harness(this, "user_2")
        h.play.purchases += storePurchase("t1", "pro_monthly", acknowledged = true, accountId = h.server.tokenFor("user_1"))
        val reachable = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(AppReferCustomerInfo.Source.SERVER, reachable.source)
        assertNull(reachable.entitlements[premium])

        // Total outage: every verified Play purchase on the device unlocks.
        h.server.reachable = false
        val outage = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, outage.source)
        assertTrue(outage.entitlements[premium]!!.isActive)
    }

    @Test
    fun anotherUsersPurchaseStillQueuedForThisUserIsMerged() = runTest {
        val h = Harness(this, "user_2")
        h.server.purchasesStatus = 503
        h.play.purchases += storePurchase("t1", "pro_monthly", acknowledged = true, accountId = h.server.tokenFor("user_1"))
        h.queue.add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_2", null, NOW, 1, restore = true))
        val info = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(AppReferCustomerInfo.Source.SERVER, info.source)
        assertTrue(info.entitlements[premium]!!.isActive)
    }

    // endregion

    // region Retry classification

    @Test
    fun backoffStatusesKeepPurchaseWithoutCountingAttempts() = runTest {
        for (status in listOf(401, 403, 404, 409, 429)) {
            val h = Harness(this, "user_1")
            h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
            h.server.purchasesStatus = status
            h.play.purchases += storePurchase("t1", "pro_monthly")
            h.engine.syncPurchases()
            assertEquals("status $status", 0, h.queue.entries.single().attempts)
            // Acknowledged on device: the retry copy is on disk.
            assertEquals(listOf("t1"), h.play.acknowledged)

            // Paused: draining again within 30 s sends nothing and counts nothing.
            val sent = h.purchaseBodies().size
            h.now += 10_000
            h.engine.drainQueue()
            assertEquals(sent, h.purchaseBodies().size)
            assertEquals(0, h.queue.entries.single().attempts)
        }
    }

    @Test
    fun backoffDoublesFromThirtySecondsAndCapsAtOneHour() = runTest {
        val h = Harness(this, "user_1")
        h.queue.add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 0))
        h.server.purchasesStatus = 429
        h.engine.drainQueue()
        val waits = mutableListOf<Long>()
        repeat(9) {
            // Step a second at a time until the next drain sends again.
            val sent = h.purchaseBodies().size
            var waited = 0L
            while (h.purchaseBodies().size == sent) {
                waited += 1_000
                h.now += 1_000
                h.engine.drainQueue()
            }
            waits += waited
        }
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L, 3_600_000L, 3_600_000L).let { listOf(30_000L) + it.dropLast(1) }, waits)
        assertEquals(0, h.queue.entries.single().attempts)
    }

    @Test
    fun retryAfterIsHonoured() = runTest {
        val h = Harness(this, "user_1")
        h.queue.add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 0))
        h.server.purchasesStatus = 429
        h.server.retryAfter = "120"
        h.engine.drainQueue()
        val sent = h.purchaseBodies().size
        h.now += 119_000
        h.engine.drainQueue()
        assertEquals(sent, h.purchaseBodies().size)
        h.now += 1_000
        h.engine.drainQueue()
        assertEquals(sent + 1, h.purchaseBodies().size)
    }

    @Test
    fun verifiedButNotRecordedKeepsPurchaseQueuedAndUnconsumed() = runTest {
        val h = Harness(this, "user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.server.purchasesStatus = 503
        h.server.retryAfter = "30"
        val result = runPurchase(h, "coins_100", ProductTypes.CONSUMABLE, update = ok(storePurchase("c1", "coins_100", isSubscription = null)))
            .await().getOrThrow()
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PURCHASED, result.outcome)
        assertTrue(h.play.consumed.isEmpty())
        assertEquals(listOf("c1"), h.play.acknowledged)
        assertEquals(1, h.queue.entries.single().attempts)

        // Paused for Retry-After, then recorded and consumed.
        h.server.purchasesStatus = null
        h.now += 10_000
        h.engine.drainQueue()
        assertTrue(h.play.consumed.isEmpty())
        h.now += 20_000
        h.engine.drainQueue()
        assertEquals(listOf("c1"), h.play.consumed)
        assertTrue(h.queue.isEmpty)
    }

    @Test
    fun malformedRequestIsFinalInDrain() = runTest {
        val h = Harness(this, "user_1")
        h.queue.add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 1))
        for (status in listOf(400, 413, 422)) {
            h.queue.add(PendingPurchase("t$status", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 1))
        }
        h.server.purchasesStatus = 422
        h.engine.drainQueue()
        assertTrue(h.queue.isEmpty)
    }

    @Test
    fun otherServerErrorsCountAnAttempt() = runTest {
        val h = Harness(this, "user_1")
        h.queue.add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 1))
        h.server.purchasesStatus = 500
        h.engine.drainQueue()
        assertEquals(2, h.queue.entries.single().attempts)
    }

    // endregion

    // region Restore flag and identity binding

    @Test
    fun onlyRestoreSendsRestoreTrue() = runTest {
        val h = Harness(this, "user_1")
        runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, update = ok(storePurchase("p1", "pro_monthly", isSubscription = null)))
            .await().getOrThrow()
        // Purchase made outside purchase(): picked up by the listener.
        h.play.purchases += storePurchase("p2", "lifetime", isSubscription = false)
        h.play.listener!!.invoke(ok(storePurchase("p2", "lifetime", isSubscription = null)))
        advanceUntilIdle()
        h.engine.restore()

        val bodies = h.purchaseBodies()
        val restores = bodies.filter { it.has("restore") }
        assertEquals(listOf("p1", "p2"), bodies.filterNot { it.has("restore") }.map { it.getString("purchaseToken") })
        assertEquals(setOf("p1", "p2"), restores.map { it.getString("purchaseToken") }.toSet())
        // A JSON boolean, not a string.
        assertTrue(restores.all { it.get("restore") == true })
    }

    @Test
    fun queuedRestoreIsRetriedAsRestore() = runTest {
        val h = Harness(this, "user_1")
        h.play.purchases += storePurchase("a", "pro_monthly", acknowledged = true)
        h.server.reachable = false
        h.engine.restore()
        assertTrue(h.queue.entries.single().restore)

        // Survives a restart.
        val reloaded = PendingPurchaseQueue(File(h.storage.directory, "pending_purchases.json"))
        assertTrue(reloaded.entries.single().restore)

        h.server.reachable = true
        h.engine.drainQueue()
        assertTrue(h.queue.isEmpty)
        assertEquals(true, h.purchaseBodies().last().get("restore"))
    }

    @Test
    fun laterNonRestoreFailureKeepsQueuedRestoreFlag() {
        val queue = PendingPurchaseQueue(File(tempDir(), "q.json"))
        queue.enqueue(PendingPurchase("a", "p", ProductTypes.SUBSCRIPTION, "u", null, NOW, 0, restore = true), countAttempt = true)
        queue.enqueue(PendingPurchase("a", "p", ProductTypes.SUBSCRIPTION, "u", null, NOW + 5, 0, restore = false), countAttempt = false)
        val entry = queue.entries.single()
        assertTrue(entry.restore)
        assertEquals(1, entry.attempts)
        assertEquals(NOW, entry.enqueuedAtMillis)
    }

    @Test
    fun purchaseIsRecordedForTheUserWhoStartedIt() = runTest {
        val h = Harness(this, "user_1")
        runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, beforeUpdate = { h.engine.logIn("user_2") },
            update = ok(storePurchase("p1", "pro_monthly", isSubscription = null)))
            .await().getOrThrow()
        assertEquals("user_1", h.purchaseBodies().single().getString("appUserId"))
        assertTrue(h.server.owned["user_1"]!!.contains("pro_monthly"))
        assertNull(h.server.owned["user_2"])
    }

    @Test
    fun listenerPurchaseIsRecordedForTheUserItsTokenBelongsTo() = runTest {
        val h = Harness(this, "user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT) // token for user_1
        h.engine.logIn("user_2")
        val p = storePurchase("p1", "pro_monthly", accountId = h.server.tokenFor("user_1"))
        h.play.purchases += p
        h.play.listener!!.invoke(ok(p.copy(isSubscription = null)))
        advanceUntilIdle()
        val body = h.purchaseBodies().single()
        assertEquals("user_1", body.getString("appUserId"))
        assertFalse(body.has("restore"))
    }

    // endregion

    // region Pending identifies

    @Test
    fun pendingIdentifiesSurviveLogOutAndFlushInOrder() = runTest {
        val h = Harness(this)
        val anon1 = h.engine.currentAppUserId
        h.server.reachable = false
        h.engine.logIn("user_a")
        h.engine.logOut()
        val anon2 = h.engine.currentAppUserId
        h.engine.logIn("user_b")
        assertEquals(listOf(anon1 to "user_a", anon2 to "user_b"), h.storage.pendingIdentifies)

        h.server.reachable = true
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        val identifies = h.server.requests.filter { it.url.contains("/identify") }
            .map { JSONObject(it.body!!) }.takeLast(2)
        assertEquals(listOf(anon1, anon2), identifies.map { it.getString("anonymousId") })
        assertTrue(h.storage.pendingIdentifies.isEmpty())
    }

    @Test
    fun removingACompletedIdentifyKeepsNewerEntries() {
        val storage = newStorage()
        storage.addPendingIdentify("\$ARAnon:1", "a")
        storage.addPendingIdentify("\$ARAnon:2", "b")
        storage.removePendingIdentify("\$ARAnon:9" to "z") // not (or no longer) in the list
        storage.removePendingIdentify("\$ARAnon:1" to "a")
        assertEquals(listOf("\$ARAnon:2" to "b"), storage.pendingIdentifies)
    }

    @Test
    fun legacySinglePendingIdentifyIsMigrated() {
        val store = InMemoryKeyValueStore()
        store.putString("apprefer_billing_pending_identify", """{"anonymousId":"${'$'}ARAnon:old","appUserId":"u"}""")
        val storage = newStorage(store)
        assertEquals(listOf("\$ARAnon:old" to "u"), storage.pendingIdentifies)
        storage.addPendingIdentify("\$ARAnon:new", "v")
        assertEquals(listOf("\$ARAnon:old" to "u", "\$ARAnon:new" to "v"), newStorage(store).pendingIdentifies)
        assertNull(store.getString("apprefer_billing_pending_identify"))
    }

    // endregion

    // region Storage failures

    /** Throws on reads of [failingKey] while [failing]; writes fail while [writesFail]. */
    private class FlakyStore(private val failingKey: String? = null) : BillingKeyValueStore {
        val inner = InMemoryKeyValueStore()
        var failing = true
        var writesFail = false
        override fun getString(key: String): String? {
            if (failing && key == failingKey) throw BillingStorageException("unreadable")
            return inner.getString(key)
        }

        override fun putString(key: String, value: String?, durable: Boolean): Boolean {
            if (writesFail) return false
            return inner.putString(key, value, durable)
        }
    }

    @Test
    fun unreadableIdentityNeverMintsANewAnonymousId() = runTest {
        val store = FlakyStore("apprefer_billing_anonymous_id")
        store.inner.putString("apprefer_billing_anonymous_id", "\$ARAnon:0f8fad5b-d9cb-469f-a165-70867728950e")
        val h = Harness(this, storage = newStorage(store))
        h.play.purchases += storePurchase("t1", "pro_monthly", acknowledged = true)

        val info = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals("", info.appUserId)
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, info.source)
        assertTrue(info.entitlements[premium]!!.isActive)
        assertTrue(h.server.requests.isEmpty())
        assertEquals("\$ARAnon:0f8fad5b-d9cb-469f-a165-70867728950e", store.inner.getString("apprefer_billing_anonymous_id"))
        try {
            h.engine.logIn("user_1")
            fail()
        } catch (_: AppReferBillingException.Storage) {
        }

        // Readable again: the same identity is used.
        store.failing = false
        val after = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals("\$ARAnon:0f8fad5b-d9cb-469f-a165-70867728950e", after.appUserId)
    }

    @Test
    fun malformedAnonymousIdIsNotReplaced() {
        val store = InMemoryKeyValueStore()
        store.putString("apprefer_billing_anonymous_id", "garbage")
        try {
            newStorage(store).anonymousId()
            fail()
        } catch (_: BillingStorageException) {
        }
        assertEquals("garbage", store.getString("apprefer_billing_anonymous_id"))
    }

    @Test
    fun anonymousIdThatCannotBeSavedIsNotUsed() {
        val store = FlakyStore()
        store.writesFail = true
        try {
            newStorage(store).anonymousId()
            fail()
        } catch (_: BillingStorageException) {
        }
    }

    @Test
    fun purchaseWhoseIdentityIsUnreadableIsLeftUnacknowledged() = runTest {
        val store = FlakyStore("apprefer_billing_app_user_id")
        val h = Harness(this, storage = newStorage(store))
        h.play.purchases += storePurchase("t1", "pro_monthly")
        h.engine.syncPurchases()
        assertTrue(h.purchaseBodies().isEmpty())
        assertTrue(h.play.acknowledged.isEmpty())

        store.failing = false
        h.engine.syncPurchases()
        assertEquals("t1", h.purchaseBodies().single().getString("purchaseToken"))
    }

    @Test
    fun purchaseIsNotAcknowledgedWhenItsRetryCopyCannotBeSaved() = runTest {
        val dir = tempDir()
        // The queue file exists but can't be read (a non-empty directory).
        File(dir, "pending_purchases.json/x").apply { parentFile!!.mkdirs(); writeText("x") }
        val h = Harness(this, "user_1", storage = newStorage(dir = dir))
        h.server.reachable = false
        h.play.purchases += storePurchase("t1", "pro_monthly")
        h.engine.syncPurchases()
        assertTrue(h.play.acknowledged.isEmpty())
        // Not overwritten.
        assertTrue(File(dir, "pending_purchases.json/x").exists())
    }

    @Test
    fun unreadableQueueFileIsNotOverwrittenAndIsReloaded() {
        val dir = tempDir()
        val file = File(dir, "q.json")
        val good = PendingPurchaseQueue(file)
        good.add(PendingPurchase("a", "p", ProductTypes.SUBSCRIPTION, "u", null, NOW, 1))
        val saved = file.readText()

        // Make it unreadable, then load.
        file.delete()
        file.mkdirs()
        File(file, "child").writeText("x")
        val q = PendingPurchaseQueue(file)
        assertFalse(q.add(PendingPurchase("b", "p", ProductTypes.SUBSCRIPTION, "u", null, NOW, 1)))

        // Readable again: the next change merges both.
        File(file, "child").delete()
        file.delete()
        file.writeText(saved)
        assertTrue(q.recordAttempt("a"))
        assertEquals(setOf("a", "b"), PendingPurchaseQueue(file).entries.map { it.purchaseToken }.toSet())
    }

    // endregion

    // region Acknowledge / consume retries

    @Test
    fun failedAcknowledgeIsRetriedOnForeground() = runTest {
        val h = Harness(this, "user_1")
        h.server.reachable = false
        h.play.acknowledgeWorks = false
        h.play.purchases += storePurchase("t1", "pro_monthly")
        h.engine.syncPurchases()
        assertEquals(listOf("t1"), h.play.acknowledged)

        h.play.acknowledgeWorks = true
        h.engine.onForeground()
        assertEquals(listOf("t1", "t1"), h.play.acknowledged)
        assertTrue(h.play.purchases.single().isAcknowledged)
    }

    @Test
    fun failedConsumeIsRetriedOnForeground() = runTest {
        val h = Harness(this, "user_1")
        h.play.consumeWorks = false
        runPurchase(h, "coins_100", ProductTypes.CONSUMABLE, update = ok(storePurchase("c1", "coins_100", isSubscription = null)))
            .await().getOrThrow()
        assertEquals(1, h.play.purchases.size)

        h.play.consumeWorks = true
        h.engine.onForeground()
        assertTrue(h.play.purchases.isEmpty())
        // Not uploaded twice.
        assertEquals(1, h.purchaseBodies().size)
    }

    @Test
    fun rejectedPurchaseIsStillAcknowledgedSoPlayDoesNotRefundIt() = runTest {
        val h = Harness(this, "user_1")
        h.server.purchasesStatus = 400
        h.play.purchases += storePurchase("t1", "pro_monthly")
        h.engine.syncPurchases()
        assertEquals(listOf("t1"), h.play.acknowledged)
    }

    // endregion
}
