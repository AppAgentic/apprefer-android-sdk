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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Rejected-purchase archive and persisted purchase intents (Astra's final check):
 * nothing is acknowledged before its copy is on disk, and a purchase delivered after a
 * restart or user switch goes to whoever started it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingRecoveryTest {

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

    /** Makes [file] unreadable (a non-empty directory) or readable again. */
    private fun setUnreadable(file: File, unreadable: Boolean) {
        if (unreadable) {
            file.delete()
            File(file, "x").apply { parentFile!!.mkdirs(); writeText("x") }
        } else {
            File(file, "x").delete()
            file.delete()
        }
    }

    /** Fails reads of [key] while [readsFail], writes of it while [writesFail]. */
    private class FlakyStore(private val key: String) : BillingKeyValueStore {
        val inner = InMemoryKeyValueStore()
        var readsFail = false
        var writesFail = false
        override fun getString(key: String): String? {
            if (readsFail && key == this.key) throw BillingStorageException("unreadable")
            return inner.getString(key)
        }

        override fun putString(key: String, value: String?, durable: Boolean): Boolean {
            if (writesFail && key == this.key) return false
            return inner.putString(key, value, durable)
        }
    }

    private fun rejectedFile(h: Harness) = File(h.storage.directory, "rejected_purchases.json")

    // region Rejected-purchase archive

    @Test
    fun rejectedPurchaseIsArchivedBeforeItIsAcknowledged() = runTest {
        val h = Harness(this, "user_1")
        h.server.purchasesStatus = 400
        h.play.purchases += storePurchase("t1", "pro_monthly")
        h.engine.syncPurchases()

        assertEquals(listOf("t1"), h.play.acknowledged)
        val saved = RejectedPurchaseLog(rejectedFile(h)).entries.single()
        assertEquals("t1", saved.purchaseToken)
        assertEquals("pro_monthly", saved.productId)
        assertEquals(ProductTypes.SUBSCRIPTION, saved.productType)
        assertEquals("user_1", saved.appUserId)
        assertEquals("device-1", saved.appreferId)
        assertEquals("GPA.t1", saved.orderId)
        assertTrue(saved.reason.contains("400"))
        assertEquals(listOf(saved), h.engine.rejectedPurchases())

        // Not resent on the next sync.
        h.engine.syncPurchases()
        assertEquals(1, h.purchaseBodies().size)
    }

    @Test
    fun rejectedConsumableIsAcknowledgedButNeverConsumed() = runTest {
        val h = Harness(this, "user_1")
        h.server.purchasesStatus = 422
        h.play.purchases += storePurchase("c1", "coins_100", isSubscription = false)
        h.engine.syncPurchases()
        h.engine.syncPurchases()
        h.engine.onForeground()

        assertEquals(listOf("c1"), h.play.acknowledged)
        assertTrue(h.play.consumed.isEmpty())
        assertEquals(1, h.purchaseBodies().size)
        assertEquals(ProductTypes.CONSUMABLE, h.engine.rejectedPurchases().single().productType)
    }

    @Test
    fun rejectedPurchaseStaysOpenUntilItsCopyIsOnDisk() = runTest {
        val dir = tempDir()
        val file = File(dir, "rejected_purchases.json")
        setUnreadable(file, true)
        val h = Harness(this, "user_1", storage = newStorage(dir = dir))
        h.server.purchasesStatus = 400
        h.play.purchases += storePurchase("t1", "pro_monthly")

        h.engine.syncPurchases()
        assertTrue(h.play.acknowledged.isEmpty())
        assertFalse(h.rejected.isDurable("t1"))

        // A copy held only in memory never leads to an acknowledgement.
        h.engine.syncPurchases()
        assertTrue(h.play.acknowledged.isEmpty())

        setUnreadable(file, false)
        h.engine.syncPurchases()
        assertEquals(listOf("t1"), h.play.acknowledged)
        assertEquals(listOf("t1"), RejectedPurchaseLog(file).entries.map { it.purchaseToken })
        assertEquals(1, h.purchaseBodies().size)
    }

    @Test
    fun purchaseWithoutUsableStoreDataIsArchivedAndNotSent() = runTest {
        val h = Harness(this, "user_1")
        h.play.purchases += storePurchase("", "pro_monthly")
        h.engine.syncPurchases()
        assertTrue(h.purchaseBodies().isEmpty())
        val saved = RejectedPurchaseLog(rejectedFile(h)).entries.single()
        assertEquals("pro_monthly", saved.productId)
        assertEquals("no usable store data", saved.reason)
    }

    @Test
    fun queueGiveUpAndFinalRejectionAreArchivedBeforeLeavingTheQueue() = runTest {
        val h = Harness(this, "user_1")
        h.queue.add(PendingPurchase("old", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", "device-1", NOW, PendingPurchaseQueue.MAX_ATTEMPTS))
        h.queue.add(PendingPurchase("bad", "coins_100", ProductTypes.CONSUMABLE, "user_1", "device-1", NOW, 1))
        h.server.purchasesStatus = 422
        h.engine.drainQueue()

        assertTrue(h.queue.isEmpty)
        val saved = RejectedPurchaseLog(rejectedFile(h)).entries.associateBy { it.purchaseToken }
        assertEquals(setOf("old", "bad"), saved.keys)
        assertTrue(saved.getValue("old").reason.contains("attempts"))
        assertEquals(ProductTypes.CONSUMABLE, saved.getValue("bad").productType)
        assertTrue(h.play.consumed.isEmpty())
    }

    @Test
    fun queuedPurchaseStaysQueuedWhenItsRejectedCopyCannotBeSaved() = runTest {
        val dir = tempDir()
        setUnreadable(File(dir, "rejected_purchases.json"), true)
        val h = Harness(this, "user_1", storage = newStorage(dir = dir))
        h.queue.add(PendingPurchase("old", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, PendingPurchaseQueue.MAX_ATTEMPTS))
        h.queue.add(PendingPurchase("bad", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 1))
        h.server.purchasesStatus = 400
        h.engine.drainQueue()
        assertEquals(setOf("old", "bad"), PendingPurchaseQueue(File(dir, "pending_purchases.json")).entries.map { it.purchaseToken }.toSet())
    }

    @Test
    fun restoreResendsArchivedPurchasesAndClearsThem() = runTest {
        val h = Harness(this, "user_1")
        // One Play no longer lists (expired), one consumable still owned.
        h.rejected.add(RejectedPurchase("gone", "pro_monthly", ProductTypes.SUBSCRIPTION, "GPA.gone", "user_1", null, null, "400", NOW - DAY))
        h.server.purchasesStatus = 400
        h.play.purchases += storePurchase("c1", "coins_100", isSubscription = false)
        h.engine.syncPurchases()
        assertEquals(setOf("gone", "c1"), h.engine.rejectedPurchases().map { it.purchaseToken }.toSet())

        h.server.purchasesStatus = null
        h.engine.restore()
        val restored = h.purchaseBodies().filter { it.optBoolean("restore") }
        assertEquals(setOf("gone", "c1"), restored.map { it.getString("purchaseToken") }.toSet())
        assertTrue(h.engine.rejectedPurchases().isEmpty())
        assertTrue(RejectedPurchaseLog(rejectedFile(h)).entries.isEmpty())
        // Recorded now, so the consumable can finally be consumed.
        assertEquals(listOf("c1"), h.play.consumed)
    }

    @Test
    fun restoreKeepsArchivedPurchaseThatIsRejectedAgain() = runTest {
        val h = Harness(this, "user_1")
        h.rejected.add(RejectedPurchase("gone", "pro_monthly", ProductTypes.SUBSCRIPTION, null, "user_1", null, null, "400", NOW - DAY))
        h.server.purchasesStatus = 400
        h.engine.restore()
        assertEquals(listOf("gone"), h.engine.rejectedPurchases().map { it.purchaseToken })
    }

    // endregion

    // region Purchase intents

    @Test
    fun purchaseSheetIsNotOpenedWhenTheIntentCannotBeSaved() = runTest {
        val store = FlakyStore(BillingStorage.KEY_PURCHASE_INTENTS)
        val h = Harness(this, "user_1", storage = newStorage(store))
        store.writesFail = true
        var launched = false
        try {
            h.engine.purchase("pro_monthly", ProductTypes.SUBSCRIPTION) {
                launched = true
                BillingResponseCode.OK to null
            }
            fail()
        } catch (e: AppReferBillingException.Storage) {
            assertTrue(e.message!!.contains("purchase not started"))
        }
        assertFalse(launched)

        // The next attempt can run once the intent saves.
        store.writesFail = false
        val result = runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, ok(storePurchase("t1", "pro_monthly", isSubscription = null)))
            .await().getOrThrow()
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PURCHASED, result.outcome)
        assertTrue(h.storage.purchaseIntents.isEmpty())
    }

    @Test
    fun cancelledPurchaseDropsItsIntent() = runTest {
        val h = Harness(this, "user_1")
        val result = runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, StorePurchasesUpdate(BillingResponseCode.USER_CANCELED, null, emptyList()))
            .await().getOrThrow()
        assertEquals(AppReferBilling.PurchaseResult.Outcome.USER_CANCELLED, result.outcome)
        assertTrue(h.storage.purchaseIntents.isEmpty())
    }

    @Test
    fun pendingPurchaseCompletingAfterAUserSwitchGoesToWhoStartedIt() = runTest {
        val h = Harness(this, "user_1")
        // AppRefer down at purchase time: no account token, so the purchase is untagged.
        h.server.reachable = false
        val pending = storePurchase("p1", "pro_monthly", isSubscription = null, state = StorePurchase.State.PENDING)
        val result = runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, ok(pending)).await().getOrThrow()
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PENDING, result.outcome)
        assertEquals(listOf("user_1"), h.storage.purchaseIntents.map { it.appUserId })

        h.server.reachable = true
        h.engine.logIn("user_2")
        h.play.purchases.replaceAll { it.copy(state = StorePurchase.State.PURCHASED) }
        h.play.listener!!.invoke(ok(h.play.purchases.single()))
        advanceUntilIdle()

        assertEquals("user_1", h.purchaseBodies().single().getString("appUserId"))
        assertTrue(h.storage.purchaseIntents.isEmpty())
    }

    @Test
    fun pendingPurchaseCompletingAfterARestartAndLogOutGoesToWhoStartedIt() = runTest {
        val first = Harness(this, "user_1")
        first.server.reachable = false
        val pending = storePurchase("p1", "pro_monthly", isSubscription = null, state = StorePurchase.State.PENDING)
        runPurchase(first, "pro_monthly", ProductTypes.SUBSCRIPTION, ok(pending)).await().getOrThrow()
        first.server.reachable = true
        first.engine.logOut()

        // Process restarted; the payment cleared meanwhile.
        val play = FakePlay().apply { purchases += first.play.purchases.map { it.copy(state = StorePurchase.State.PURCHASED) } }
        val second = Harness(this, storage = first.storage, play = play)
        second.engine.syncPurchases()
        assertEquals("user_1", second.purchaseBodies().single().getString("appUserId"))
        assertTrue(second.storage.purchaseIntents.isEmpty())
    }

    @Test
    fun untaggedDeliveryWaitsWhileIntentsAreUnreadable() = runTest {
        val store = FlakyStore(BillingStorage.KEY_PURCHASE_INTENTS)
        val h = Harness(this, "user_1", storage = newStorage(store))
        assertTrue(h.storage.addPurchaseIntent(PurchaseIntent("pro_monthly", "user_1", null, NOW)))
        h.engine.logIn("user_2")
        store.readsFail = true
        h.play.purchases += storePurchase("p1", "pro_monthly")
        h.engine.syncPurchases()
        assertTrue(h.purchaseBodies().isEmpty())
        assertTrue(h.play.acknowledged.isEmpty())

        store.readsFail = false
        h.engine.syncPurchases()
        assertEquals("user_1", h.purchaseBodies().single().getString("appUserId"))
    }

    @Test
    fun taggedPurchaseWithUnknownTokenUsesTheIntentCarryingThatToken() = runTest {
        val h = Harness(this, "user_2")
        val token = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertTrue(h.storage.addPurchaseIntent(PurchaseIntent("pro_monthly", "user_1", token, NOW)))
        h.play.purchases += storePurchase("p1", "pro_monthly", accountId = token)
        h.engine.syncPurchases()
        assertEquals("user_1", h.purchaseBodies().single().getString("appUserId"))
        assertTrue(h.storage.purchaseIntents.isEmpty())
    }

    @Test
    fun intentsAreNeverDroppedByAgeOrAbsenceFromPlay() = runTest {
        val h = Harness(this, "user_1")
        val old = PurchaseIntent("pro_monthly", "user_1", null, NOW)
        val fresh = PurchaseIntent("coins_100", "user_1", null, NOW + 59 * DAY)
        assertTrue(h.storage.addPurchaseIntent(old))
        assertTrue(h.storage.addPurchaseIntent(fresh))
        h.now = NOW + 400 * DAY
        h.play.purchases += storePurchase("p1", "pro_monthly", state = StorePurchase.State.PENDING)
        h.engine.syncPurchases()
        assertEquals(listOf(old, fresh), h.storage.purchaseIntents)

        // Play lists nothing for either product: still kept.
        h.play.purchases.clear()
        h.engine.syncPurchases()
        assertEquals(listOf(old, fresh), h.storage.purchaseIntents)
    }

    @Test
    fun storeErrorThatMayStillCompleteKeepsTheIntent() = runTest {
        val h = Harness(this, "user_1")
        runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, StorePurchasesUpdate(BillingResponseCode.ERROR, "flaky", emptyList()))
            .await()
        assertEquals(listOf("user_1"), h.storage.purchaseIntents.map { it.appUserId })
    }

    @Test
    fun renewalNeverMatchesOrConsumesAnIntent() = runTest {
        val h = Harness(this, "user_2")
        val intent = PurchaseIntent("pro_monthly", "user_1", null, NOW)
        assertTrue(h.storage.addPurchaseIntent(intent))
        // Renewal order ids carry a `..N` suffix.
        h.play.purchases += storePurchase("r1..3", "pro_monthly")
        h.engine.syncPurchases()
        assertEquals("user_2", h.purchaseBodies().single().getString("appUserId"))
        assertEquals(listOf(intent), h.storage.purchaseIntents)
        assertFalse(storePurchase("r1", "pro_monthly").isRenewal)
        assertTrue(storePurchase("r1..0", "pro_monthly").isRenewal)
    }

    @Test
    fun untaggedPurchaseMatchesTheOldestIntentEvenIfThatIntentWasTagged() = runTest {
        val h = Harness(this, "user_3")
        val tagged = PurchaseIntent("pro_monthly", "user_1", "0f8fad5b-d9cb-469f-a165-70867728950e", NOW)
        val untagged = PurchaseIntent("pro_monthly", "user_2", null, NOW + 1)
        assertTrue(h.storage.addPurchaseIntent(tagged))
        assertTrue(h.storage.addPurchaseIntent(untagged))
        // Delivered without its tag.
        h.play.purchases += storePurchase("p1", "pro_monthly")
        h.engine.syncPurchases()
        assertEquals("user_1", h.purchaseBodies().single().getString("appUserId"))
        assertEquals(listOf(untagged), h.storage.purchaseIntents)
    }

    @Test
    fun malformedIntentsAreQuarantinedAndUntaggedPurchasesWaitThenGoToTheCurrentUser() = runTest {
        var clock = NOW
        val store = InMemoryKeyValueStore()
        val storage = newStorage(store, now = { clock })
        store.putString(BillingStorage.KEY_PURCHASE_INTENTS, "{not json")
        val h = Harness(this, "user_2", storage = storage)
        h.play.purchases += storePurchase("p1", "pro_monthly")
        h.engine.syncPurchases()

        // Quarantined, marker saved, purchase left open.
        assertTrue(h.purchaseBodies().isEmpty())
        assertTrue(h.play.acknowledged.isEmpty())
        assertEquals(NOW, h.storage.intentsLostAtMillis)
        assertEquals("{not json", h.storage.directory.listFiles()!!.single { it.name.endsWith(".corrupt") }.readText())
        assertEquals(null, store.getString(BillingStorage.KEY_PURCHASE_INTENTS))

        // Foreground a day later: still waiting.
        h.now = NOW + DAY
        clock = h.now
        h.engine.onForeground()
        assertTrue(h.purchaseBodies().isEmpty())

        // After 48 hours: sent for the current user, and the marker clears.
        h.now = NOW + 49 * 3600 * 1000L
        clock = h.now
        h.engine.syncPurchases()
        assertEquals("user_2", h.purchaseBodies().single().getString("appUserId"))
        assertEquals(null, h.storage.intentsLostAtMillis)
    }

    @Test
    fun lostIntentMarkerStaysWhileAnUntaggedPurchaseIsPending() = runTest {
        val store = InMemoryKeyValueStore()
        store.putString(BillingStorage.KEY_PURCHASE_INTENTS, "[{\"productId\":1}]")
        val h = Harness(this, "user_2", storage = newStorage(store))
        h.play.purchases += storePurchase("p1", "pro_monthly", state = StorePurchase.State.PENDING)
        h.engine.syncPurchases()
        assertEquals(NOW, h.storage.intentsLostAtMillis)

        h.play.purchases.clear()
        h.engine.syncPurchases()
        assertEquals(null, h.storage.intentsLostAtMillis)
    }

    @Test
    fun purchaseAssignedAfterLostIntentsCarriesASupportNoteWhenArchived() = runTest {
        val store = InMemoryKeyValueStore()
        store.putString(BillingStorage.KEY_PURCHASE_INTENTS, "garbage")
        val h = Harness(this, "user_2", storage = newStorage(store))
        h.play.purchases += storePurchase("p1", "pro_monthly")
        h.engine.syncPurchases()
        assertTrue(h.purchaseBodies().isEmpty())

        h.now = NOW + 3 * DAY
        h.server.purchasesStatus = 400
        h.engine.syncPurchases()
        val entry = h.engine.rejectedPurchases().single()
        assertEquals("user_2", entry.appUserId)
        assertTrue(entry.supportNote!!.contains("lost"))
        // Saved with the note.
        assertEquals(entry.supportNote, RejectedPurchaseLog(rejectedFile(h)).entries.single().supportNote)
    }

    @Test
    fun taggedPurchaseIsNotHeldByTheLostIntentMarker() = runTest {
        val store = InMemoryKeyValueStore()
        store.putString(BillingStorage.KEY_PURCHASE_INTENTS, "garbage")
        val h = Harness(this, "user_1", storage = newStorage(store))
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.play.purchases += storePurchase("p1", "pro_monthly", accountId = h.server.tokenFor("user_1"))
        h.engine.syncPurchases()
        assertEquals("user_1", h.purchaseBodies().single().getString("appUserId"))
    }

    @Test
    fun rejectedArchiveHasNoCapAndNeverEvicts() = runTest {
        val h = Harness(this, "user_1")
        repeat(500) { i ->
            assertTrue(h.rejected.add(RejectedPurchase("t$i", "pro_monthly", ProductTypes.SUBSCRIPTION, null, "user_1", null, null, "400", NOW)))
        }
        assertEquals(500, h.engine.rejectedPurchases().size)
        assertEquals(500, RejectedPurchaseLog(rejectedFile(h)).entries.size)
        // Only a recorded purchase leaves.
        assertTrue(h.rejected.remove("t0"))
        assertEquals(499, RejectedPurchaseLog(rejectedFile(h)).entries.size)
    }

    // endregion
}
