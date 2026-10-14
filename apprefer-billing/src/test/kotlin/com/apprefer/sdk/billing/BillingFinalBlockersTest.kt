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
import org.junit.Test

/**
 * Final release blockers (shared spec across all four SDKs): renewals never use an intent on
 * any path, intents go only on definitive store outcomes, the oldest intent is chosen by
 * start time, and partly malformed intent storage keeps what still decodes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingFinalBlockersTest {

    private fun Harness.purchaseBodies() =
        server.requests.filter { it.url.contains("/purchases") }.map { JSONObject(it.body!!) }

    private fun ok(vararg purchases: StorePurchase) = StorePurchasesUpdate(BillingResponseCode.OK, null, purchases.toList())

    /** Starts a purchase whose launch returns [launchCode] (or throws [launchThrows]); returns its result. */
    private suspend fun TestScope.purchaseWithLaunch(
        h: Harness,
        launchCode: Int = BillingResponseCode.OK,
        launchThrows: Throwable? = null,
        update: StorePurchasesUpdate? = null,
    ): Result<BillingEngine.PurchaseOutcome> {
        val call = async {
            runCatching {
                h.engine.purchase("pro_monthly", ProductTypes.SUBSCRIPTION) {
                    launchThrows?.let { throw it }
                    launchCode to "test"
                }
            }
        }
        advanceUntilIdle()
        if (update != null) {
            h.play.purchases += update.purchases
            h.play.listener!!.invoke(update)
            advanceUntilIdle()
        }
        return call.await()
    }

    // region 1. Renewal guard on every path

    @Test
    fun renewalSettlesThePurchaseSheetButNeverConsumesItsIntent() = runTest {
        val h = Harness(this, "user_1")
        val call = async { runCatching { h.engine.purchase("pro_monthly", ProductTypes.SUBSCRIPTION) { BillingResponseCode.OK to null } } }
        advanceUntilIdle()
        val intent = h.storage.purchaseIntents.single()

        // The sheet's store update carries a renewal (e.g. an upgrade/crossgrade order `..N`).
        val renewal = storePurchase("old..2", "pro_monthly")
        h.play.purchases += renewal
        h.play.listener!!.invoke(ok(renewal))
        advanceUntilIdle()

        // Settled, not hung.
        assertTrue(call.isCompleted)
        val result = call.await().getOrThrow()
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PURCHASED, result.outcome)
        assertEquals("old..2", result.purchase!!.purchaseToken)
        // Recorded for whoever opened the sheet, not via the intent, which stays saved.
        assertEquals("user_1", h.purchaseBodies().single().getString("appUserId"))
        assertEquals(listOf(intent), h.storage.purchaseIntents)

        // The real purchase arriving later still matches and uses the intent.
        h.engine.logIn("user_2")
        h.play.purchases += storePurchase("new", "pro_monthly")
        h.engine.syncPurchases()
        assertEquals("user_1", h.purchaseBodies().single { it.getString("purchaseToken") == "new" }.getString("appUserId"))
        assertTrue(h.storage.purchaseIntents.isEmpty())
    }

    @Test
    fun sheetUpdateCarryingARenewalAndTheNewPurchaseUsesTheIntentOnlyForTheNewPurchase() = runTest {
        val h = Harness(this, "user_1")
        val result = purchaseWithLaunch(
            h,
            update = ok(storePurchase("old..5", "pro_monthly"), storePurchase("new", "pro_monthly")),
        ).getOrThrow()
        assertEquals("new", result.purchase!!.purchaseToken)
        assertTrue(h.storage.purchaseIntents.isEmpty())
    }

    // endregion

    // region 2. Intents removed only on definitive store outcomes

    @Test
    fun ambiguousLaunchFailuresKeepTheIntent() = runTest {
        val h = Harness(this, "user_1")
        val ambiguous = listOf(
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_DISCONNECTED,
            BillingResponseCode.NETWORK_ERROR,
            BillingResponseCode.ERROR,
            BillingResponseCode.ITEM_NOT_OWNED,
        )
        ambiguous.forEachIndexed { i, code ->
            h.now = NOW + i
            assertTrue(purchaseWithLaunch(h, launchCode = code).isFailure)
        }
        assertEquals(ambiguous.size, h.storage.purchaseIntents.size)
    }

    @Test
    fun unknownExceptionStartingThePurchaseKeepsTheIntent() = runTest {
        val h = Harness(this, "user_1")
        assertTrue(purchaseWithLaunch(h, launchThrows = IllegalStateException("boom")).isFailure)
        assertEquals(listOf("user_1"), h.storage.purchaseIntents.map { it.appUserId })
        // A store exception with a definitive code drops it.
        h.now = NOW + 1
        assertTrue(purchaseWithLaunch(h, launchThrows = AppReferBillingException.Store(BillingResponseCode.ITEM_UNAVAILABLE, "gone")).isFailure)
        assertEquals(listOf(NOW), h.storage.purchaseIntents.map { it.startedAtMillis })
    }

    @Test
    fun definitiveLaunchFailuresDropTheIntent() = runTest {
        val h = Harness(this, "user_1")
        listOf(
            BillingResponseCode.USER_CANCELED,
            BillingResponseCode.ITEM_UNAVAILABLE,
            BillingResponseCode.DEVELOPER_ERROR,
            BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingResponseCode.BILLING_UNAVAILABLE,
            BillingResponseCode.ITEM_ALREADY_OWNED,
        ).forEach { code ->
            purchaseWithLaunch(h, launchCode = code)
            assertTrue("code $code", h.storage.purchaseIntents.isEmpty())
        }
    }

    @Test
    fun purchaseUpdateErrorsKeepOrDropTheIntentByTheSameRule() = runTest {
        val h = Harness(this, "user_1")
        purchaseWithLaunch(h, update = StorePurchasesUpdate(BillingResponseCode.SERVICE_UNAVAILABLE, null, emptyList()))
        assertEquals(1, h.storage.purchaseIntents.size)

        h.now = NOW + 1
        purchaseWithLaunch(h, update = StorePurchasesUpdate(BillingResponseCode.ITEM_UNAVAILABLE, null, emptyList()))
        // Only the definitive failure's own intent went.
        assertEquals(listOf(NOW), h.storage.purchaseIntents.map { it.startedAtMillis })
    }

    @Test
    fun aSecondPurchaseStartForTheSameUserAndProductKeepsBothIntents() = runTest {
        val h = Harness(this, "user_1")
        purchaseWithLaunch(h, launchCode = BillingResponseCode.SERVICE_UNAVAILABLE)
        h.now = NOW + 1000
        purchaseWithLaunch(h, launchCode = BillingResponseCode.SERVICE_UNAVAILABLE)
        assertEquals(listOf(NOW, NOW + 1000), h.storage.purchaseIntents.map { it.startedAtMillis })
        assertTrue(h.storage.purchaseIntents.all { it.appUserId == "user_1" && it.productId == "pro_monthly" })
    }

    @Test
    fun definitiveFailureClassification() {
        assertTrue(BillingEngine.isDefinitiveFailure(BillingResponseCode.USER_CANCELED))
        assertTrue(BillingEngine.isDefinitiveFailure(BillingResponseCode.ITEM_ALREADY_OWNED))
        assertFalse(BillingEngine.isDefinitiveFailure(BillingResponseCode.ERROR))
        assertFalse(BillingEngine.isDefinitiveFailure(BillingResponseCode.NETWORK_ERROR))
        assertFalse(BillingEngine.isDefinitiveFailure(BillingResponseCode.SERVICE_DISCONNECTED))
        assertFalse(BillingEngine.isDefinitiveFailure(BillingResponseCode.SERVICE_UNAVAILABLE))
        assertFalse(BillingEngine.isDefinitiveFailure(BillingResponseCode.ITEM_NOT_OWNED))
        assertFalse(BillingEngine.isDefinitiveFailure(12345))
    }

    // endregion

    // region 3. Oldest intent by startedAt

    @Test
    fun oldestIntentByStartTimeWinsEvenWhenStoredOutOfOrder() = runTest {
        val store = InMemoryKeyValueStore()
        store.putString(
            BillingStorage.KEY_PURCHASE_INTENTS,
            """[{"productId":"pro_monthly","appUserId":"user_new","accountToken":null,"startedAtMs":${NOW - 1000}},""" +
                """{"productId":"pro_monthly","appUserId":"user_old","accountToken":null,"startedAtMs":${NOW - 5000}}]""",
        )
        val h = Harness(this, "user_3", storage = newStorage(store))
        h.play.purchases += storePurchase("p1", "pro_monthly")
        h.engine.syncPurchases()
        assertEquals("user_old", h.purchaseBodies().single().getString("appUserId"))
        assertEquals(listOf("user_new"), h.storage.purchaseIntents.map { it.appUserId })
    }

    // endregion

    // region 4. Partial corruption recovery

    @Test
    fun partlyMalformedIntentsFileKeepsParseableEntriesQuarantinesTheRawOriginalAndSetsTheMarker() {
        val store = InMemoryKeyValueStore()
        val raw = """[{"productId":"pro_monthly","appUserId":"user_1","accountToken":null,"startedAtMs":$NOW},""" +
            """{"productId":1},"junk",{"productId":"coins_100","appUserId":"user_2","startedAtMs":${NOW + 1}}]"""
        store.putString(BillingStorage.KEY_PURCHASE_INTENTS, raw)
        val storage = newStorage(store)

        assertEquals(
            listOf(PurchaseIntent("pro_monthly", "user_1", null, NOW), PurchaseIntent("coins_100", "user_2", null, NOW + 1)),
            storage.purchaseIntents,
        )
        assertEquals(raw, storage.directory.listFiles()!!.single { it.name.endsWith(".corrupt") }.readText())
        assertEquals(NOW, storage.intentsLostAtMillis)
        // Rewritten with only the good entries: a second read finds nothing malformed.
        assertEquals(2, storage.purchaseIntents.size)
        assertEquals(1, storage.directory.listFiles()!!.count { it.name.endsWith(".corrupt") })
    }

    @Test
    fun whollyUnparseableIntentsFileKeepsNothingQuarantinesAndSetsTheMarker() {
        val store = InMemoryKeyValueStore()
        store.putString(BillingStorage.KEY_PURCHASE_INTENTS, "{not json")
        val storage = newStorage(store)
        assertTrue(storage.purchaseIntents.isEmpty())
        assertEquals("{not json", storage.directory.listFiles()!!.single { it.name.endsWith(".corrupt") }.readText())
        assertEquals(NOW, storage.intentsLostAtMillis)
    }

    // endregion
}
