package com.apprefer.sdk.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests for the second round of billing review fixes (Astra's verification). */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingReviewRound2Test {

    private val premium = "premium"

    private fun Harness.purchaseBodies() =
        server.requests.filter { it.url.contains("/purchases") }.map { JSONObject(it.body!!) }

    private fun ok(vararg purchases: StorePurchase) = StorePurchasesUpdate(BillingResponseCode.OK, null, purchases.toList())

    private suspend fun TestScope.runPurchase(h: Harness, productId: String, productType: String, update: StorePurchasesUpdate) =
        async {
            runCatching { h.engine.purchase(productId, productType) { BillingResponseCode.OK to null } }
        }.also {
            advanceUntilIdle()
            h.play.purchases += update.purchases.map { it.copy(isSubscription = productType == ProductTypes.SUBSCRIPTION) }
            h.play.listener!!.invoke(update)
        }

    /** Makes [file] unreadable (a non-empty directory) or readable again with [contents]. */
    private fun setUnreadable(file: File, unreadable: Boolean, contents: String? = null) {
        if (unreadable) {
            file.delete()
            File(file, "x").apply { parentFile!!.mkdirs(); writeText("x") }
        } else {
            File(file, "x").delete()
            file.delete()
            contents?.let(file::writeText)
        }
    }

    // region Durable vs provisional queue entries

    @Test
    fun provisionalQueueEntryIsNotAcknowledgedUntilSaved() = runTest {
        val dir = tempDir()
        val file = File(dir, "pending_purchases.json")
        setUnreadable(file, true)
        val h = Harness(this, "user_1", storage = newStorage(dir = dir))
        h.server.reachable = false
        h.play.purchases += storePurchase("t1", "pro_monthly")

        h.engine.syncPurchases()
        assertTrue(h.queue.containsToken("t1"))
        assertFalse(h.queue.isDurable("t1"))
        assertTrue(h.play.acknowledged.isEmpty())

        // Still unreadable: the in-memory copy alone never leads to an acknowledgement.
        h.engine.syncPurchases()
        assertTrue(h.play.acknowledged.isEmpty())

        // Readable again: the retry copy is saved first, then acknowledged.
        setUnreadable(file, false)
        h.engine.syncPurchases()
        assertEquals(listOf("t1"), h.play.acknowledged)
        assertTrue(h.queue.isDurable("t1"))
        assertEquals(listOf("t1"), PendingPurchaseQueue(file).entries.map { it.purchaseToken })
    }

    @Test
    fun queueUnreadableAtLaunchIsReReadAndDrained() = runTest {
        val dir = tempDir()
        val file = File(dir, "pending_purchases.json")
        PendingPurchaseQueue(file).add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 0))
        val saved = file.readText()
        setUnreadable(file, true)
        val h = Harness(this, "user_1", storage = newStorage(dir = dir))
        assertTrue(h.queue.isEmpty)

        setUnreadable(file, false, saved)
        h.engine.drainQueue()
        assertEquals(listOf("t1"), h.purchaseBodies().map { it.getString("purchaseToken") })
        assertTrue(h.queue.isEmpty)
    }

    @Test
    fun queueEntriesAddedWhileUnreadableKeepTheirNewerState() {
        val dir = tempDir()
        val file = File(dir, "q.json")
        PendingPurchaseQueue(file).add(PendingPurchase("a", "p", ProductTypes.SUBSCRIPTION, "u1", null, NOW, 0))
        val saved = file.readText()
        val q = PendingPurchaseQueue(file)
        setUnreadable(file, true)
        q.reloadIfNeeded() // still readable in memory; nothing to do
        // A failed save makes the change provisional.
        assertFalse(q.enqueue(PendingPurchase("a", "p", ProductTypes.SUBSCRIPTION, "u2", null, NOW, 0, restore = true), countAttempt = false))
        assertFalse(q.isDurable("a"))
        setUnreadable(file, false, saved)
        assertTrue(q.persist())
        val entry = PendingPurchaseQueue(file).entries.single()
        assertEquals("u2", entry.appUserId)
        assertTrue(entry.restore)
    }

    // endregion

    // region Restore ownership

    @Test
    fun explicitRestoreTakesOverAQueuedEntryForTheRestoringCustomer() = runTest {
        val h = Harness(this, "user_1")
        h.server.reachable = false
        h.play.purchases += storePurchase("t1", "pro_monthly")
        h.engine.syncPurchases() // ordinary upload fails: queued for user_1
        assertEquals("user_1", h.queue.entries.single().appUserId)

        h.engine.logIn("user_2")
        h.engine.restore()
        val entry = h.queue.entries.single()
        assertEquals("user_2", entry.appUserId)
        assertTrue(entry.restore)

        // An ordinary redelivery for user_1 never moves it back.
        h.queue.enqueue(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 0), countAttempt = true)
        h.queue.add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 0))
        assertEquals("user_2", h.queue.entries.single().appUserId)
        assertTrue(h.queue.entries.single().restore)

        h.server.reachable = true
        h.engine.drainQueue()
        val sent = h.purchaseBodies().last()
        assertEquals("user_2", sent.getString("appUserId"))
        assertEquals(true, sent.get("restore"))
        assertTrue(h.server.owned["user_2"]!!.contains("pro_monthly"))
    }

    @Test
    fun pausedRestoreQueuesTheRestOfItsBatchWithoutSending() = runTest {
        val h = Harness(this, "user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.play.purchases += storePurchase("a", "pro_monthly", acknowledged = true)
        h.play.purchases += storePurchase("b", "lifetime", isSubscription = false)
        h.play.purchases += storePurchase("c", "coins_100", isSubscription = false)
        h.server.purchasesStatus = 429
        h.engine.restore()

        // Only the first was sent; all three wait as restores for user_1, no attempts used.
        assertEquals(listOf("a"), h.purchaseBodies().map { it.getString("purchaseToken") })
        assertEquals(listOf("a", "b", "c"), h.queue.entries.map { it.purchaseToken })
        assertTrue(h.queue.entries.all { it.restore && it.appUserId == "user_1" && it.attempts == 0 })
        // Durable, so the unacknowledged ones are acknowledged (the consumable isn't consumed).
        assertEquals(listOf("b", "c"), h.play.acknowledged)
        assertTrue(h.play.consumed.isEmpty())

        h.server.purchasesStatus = null
        h.now += BillingEngine.MAX_BACKOFF_MS
        h.engine.drainQueue()
        assertTrue(h.queue.isEmpty)
        assertTrue(h.purchaseBodies().drop(1).all { it.get("restore") == true })
        assertEquals(listOf("c"), h.play.consumed)
    }

    // endregion

    // region Unrestricted fallback only in a total outage

    @Test
    fun rejectedUploadDoesNotUnlockAnotherUsersPurchaseWhileServerAnswers() = runTest {
        for (status in listOf(400, 409, 503)) {
            val h = Harness(this, "user_2")
            h.play.purchases += storePurchase("a1", "pro_monthly", acknowledged = true, accountId = h.server.tokenFor("user_1"))
            h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
            h.server.purchasesStatus = status
            val result = runPurchase(h, "coins_100", ProductTypes.CONSUMABLE, ok(storePurchase("c1", "coins_100", isSubscription = null)))
                .await().getOrThrow()
            assertEquals("status $status", AppReferCustomerInfo.Source.SERVER, result.customerInfo.source)
            assertNull("status $status", result.customerInfo.entitlements[premium])
        }
    }

    @Test
    fun totalOutageStillUnlocksEveryDevicePurchase() = runTest {
        val h = Harness(this, "user_2")
        h.play.purchases += storePurchase("a1", "pro_monthly", acknowledged = true, accountId = h.server.tokenFor("user_1"))
        h.server.reachable = false
        val result = runPurchase(h, "coins_100", ProductTypes.CONSUMABLE, ok(storePurchase("c1", "coins_100", isSubscription = null)))
            .await().getOrThrow()
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, result.customerInfo.source)
        assertTrue(result.customerInfo.entitlements[premium]!!.isActive)
    }

    @Test
    fun failedRestoreWithReachableCustomerEndpointMergesOnlyOwnedPurchases() = runTest {
        val h = Harness(this, "user_2")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.play.purchases += storePurchase("a1", "pro_monthly", acknowledged = true, accountId = h.server.tokenFor("user_1"))
        h.server.purchasesStatus = 400
        val info = h.engine.restore()
        assertEquals(AppReferCustomerInfo.Source.SERVER, info.source)
        assertNull(info.entitlements[premium])
    }

    // endregion

    // region Retry-After and legacy answers

    @Test
    fun retryAfterOn503PausesWithoutUsingAnAttempt() = runTest {
        val h = Harness(this, "user_1")
        h.queue.add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 3))
        h.server.purchasesStatus = 503
        h.server.retryAfter = "90"
        h.engine.drainQueue()
        assertEquals(3, h.queue.entries.single().attempts)
        val sent = h.purchaseBodies().size
        h.now += 89_000
        h.engine.drainQueue()
        assertEquals(sent, h.purchaseBodies().size)
        h.now += 1_000
        h.server.purchasesStatus = null
        h.engine.drainQueue()
        assertTrue(h.queue.isEmpty)
    }

    @Test
    fun legacyFailedConversionKeepsConsumableQueuedAndUnconsumed() = runTest {
        val h = Harness(this, "user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.server.legacyFailedConversion = true
        runPurchase(h, "coins_100", ProductTypes.CONSUMABLE, ok(storePurchase("c1", "coins_100", isSubscription = null)))
            .await().getOrThrow()
        assertTrue(h.play.consumed.isEmpty())
        assertTrue(h.queue.isDurable("c1"))

        h.server.legacyFailedConversion = false
        h.now += 30_000
        h.engine.drainQueue()
        assertEquals(listOf("c1"), h.play.consumed)
    }

    // endregion

    // region Identity

    @Test
    fun purchaseKeepsItsInitiatorWhenLogInRunsDuringTheAccountTokenFetch() = runTest {
        val h = Harness(this, "user_1", transportDelayMs = 100)
        val purchase = async {
            h.engine.purchase("pro_monthly", ProductTypes.SUBSCRIPTION) { BillingResponseCode.OK to null }
        }
        runCurrent() // fetching user_1's account token
        val login = async { h.engine.logIn("user_2") }
        advanceUntilIdle()
        login.await()
        val p = storePurchase("p1", "pro_monthly")
        h.play.purchases += p
        h.play.listener!!.invoke(ok(p.copy(isSubscription = null)))
        advanceUntilIdle()
        purchase.await()
        assertEquals("user_1", h.purchaseBodies().single().getString("appUserId"))
    }

    @Test
    fun unreadableAccountTokensLeaveATaggedPurchaseForLater() = runTest {
        val store = object : BillingKeyValueStore {
            val inner = InMemoryKeyValueStore()
            var failing = false
            override fun getString(key: String): String? {
                if (failing && key == "apprefer_billing_account_tokens") throw BillingStorageException("unreadable")
                return inner.getString(key)
            }
            override fun putString(key: String, value: String?, durable: Boolean) = inner.putString(key, value, durable)
        }
        val h = Harness(this, "user_1", storage = newStorage(store))
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.engine.logIn("user_2")
        store.failing = true
        h.play.purchases += storePurchase("p1", "pro_monthly", accountId = h.server.tokenFor("user_1"))
        h.engine.syncPurchases()
        assertTrue(h.purchaseBodies().isEmpty())
        assertTrue(h.play.acknowledged.isEmpty())

        store.failing = false
        h.engine.syncPurchases()
        assertEquals("user_1", h.purchaseBodies().single().getString("appUserId"))
    }

    @Test
    fun concurrentIdentifyFlushesShareOneRunAndPickUpNewEntries() = runTest {
        val h = Harness(this, transportDelayMs = 100)
        val anon = h.engine.currentAppUserId
        h.server.reachable = false
        h.engine.logIn("user_a")
        advanceUntilIdle()
        h.server.reachable = true
        h.server.requests.clear()

        val first = async { h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT) }
        val second = async { h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT) }
        runCurrent()
        advanceTimeBy(50) // first identify in flight
        h.storage.addPendingIdentify("\$ARAnon:other", "user_b")
        advanceUntilIdle()
        first.await()
        second.await()

        val identifies = h.server.requests.filter { it.url.contains("/identify") }.map { JSONObject(it.body!!) }
        assertEquals(listOf(anon to "user_a", "\$ARAnon:other" to "user_b"), identifies.map { it.getString("anonymousId") to it.getString("appUserId") })
        assertTrue(h.storage.pendingIdentifies.isEmpty())
    }

    // endregion
}
