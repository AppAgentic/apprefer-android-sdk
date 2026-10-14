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

@OptIn(ExperimentalCoroutinesApi::class)
class BillingEngineTest {

    private val premium = "premium"

    private fun TestScope.harness(appUserId: String? = null, storage: BillingStorage = newStorage()) =
        Harness(this, appUserId, storage)

    private fun Harness.purchaseBodies() =
        server.requests.filter { it.url.contains("/purchases") }.map { JSONObject(it.body!!) }

    // region Outage fallback

    @Test
    fun serverDownUnlocksFromGooglePlay() = runTest {
        val h = harness()
        h.server.reachable = false
        h.play.purchases += storePurchase("t1", "pro_monthly", acknowledged = true)
        val info = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, info.source)
        assertTrue(info.entitlements[premium]!!.isActive)
        assertEquals(setOf("pro_monthly"), info.activeSubscriptions)
    }

    @Test
    fun serverErrorStatusAlsoFallsBack() = runTest {
        val h = harness()
        h.server.status = 500
        h.play.purchases += storePurchase("t1", "pro_monthly", acknowledged = true)
        val info = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, info.source)
        assertTrue(info.entitlements[premium]!!.isActive)
    }

    @Test
    fun serverDownKeepsCachedEntitlementsForSameUserOnly() = runTest {
        val storage = newStorage()
        val h = harness("user_1", storage)
        h.server.owned["user_1"] = mutableSetOf("web_plan")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.server.reachable = false

        val same = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, same.source)
        assertTrue(same.entitlements[premium]!!.isActive)

        h.engine.logIn("user_2")
        val other = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals("user_2", other.appUserId)
        assertNull(other.entitlements[premium])
    }

    @Test
    fun cachedOrFetchedUsesFreshCacheWithoutNetwork() = runTest {
        val h = harness("user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        val calls = h.server.requests.size
        h.now += 60_000
        val cached = h.engine.customerInfo(AppReferBilling.FetchPolicy.CACHED_OR_FETCHED)
        assertEquals(calls, h.server.requests.size)
        assertEquals(AppReferCustomerInfo.Source.CACHE, cached.source)

        h.now += 10 * 60_000
        h.engine.customerInfo(AppReferBilling.FetchPolicy.CACHED_OR_FETCHED)
        assertEquals(calls + 1, h.server.requests.size)
    }

    @Test
    fun firstFetchRegistersCustomerThenUsesGet() = runTest {
        val h = harness("user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(listOf("POST /api/billing/v1/customers", "GET /api/billing/v1/customers"), h.server.paths())
        val register = JSONObject(h.server.requests[0].body!!)
        assertEquals("device-1", register.getString("appreferId"))
        assertEquals(h.server.tokenFor("user_1"), h.storage.accountToken("user_1"))
    }

    @Test
    fun attributesAreSentWithNextCall() = runTest {
        val h = harness("user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.engine.setAttributes(mapOf("\$email" to "a@b.com"))
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        val last = h.server.requests.last()
        assertEquals("POST", last.method)
        assertEquals("a@b.com", JSONObject(last.body!!).getJSONObject("attributes").getString("\$email"))
        assertTrue(h.storage.pendingAttributes.isEmpty())
    }

    @Test
    fun publishesOnlyWhenContentChanges() = runTest {
        val h = harness("user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(1, h.published.size)
        h.server.owned["user_1"] = mutableSetOf("pro_monthly")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(2, h.published.size)
    }

    // endregion

    // region Purchase flow

    /** Starts `purchase`, lets it reach Play, then delivers [update] like `onPurchasesUpdated`. */
    private fun TestScope.runPurchase(
        h: Harness,
        productId: String,
        productType: String,
        update: StorePurchasesUpdate?,
        launchCode: Int = BillingResponseCode.OK,
        onLaunch: (String?) -> Unit = {},
    ) = async {
        // runCatching: a failed async child would cancel runTest itself.
        runCatching {
            h.engine.purchase(productId, productType) { token ->
                onLaunch(token)
                launchCode to null
            }
        }
    }.also {
        advanceUntilIdle()
        if (update != null) {
            h.play.purchases += update.purchases.map { p ->
                p.copy(isSubscription = productType == ProductTypes.SUBSCRIPTION)
            }.filter { it.state == StorePurchase.State.PURCHASED }
            h.play.listener!!.invoke(update)
        }
    }

    private fun ok(vararg purchases: StorePurchase) = StorePurchasesUpdate(BillingResponseCode.OK, null, purchases.toList())

    @Test
    fun purchaseTagsAccountTokenAndRecordsWithAppRefer() = runTest {
        val h = harness("user_1")
        var launchedWith: String? = null
        val result = runPurchase(
            h, "pro_monthly", ProductTypes.SUBSCRIPTION,
            ok(storePurchase("tok-1", "pro_monthly", isSubscription = null)),
        ) { launchedWith = it }.await().getOrThrow()

        // The account token from POST customers is set as obfuscatedAccountId.
        assertEquals(h.server.tokenFor("user_1"), launchedWith)
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PURCHASED, result.outcome)
        assertEquals(AppReferCustomerInfo.Source.SERVER, result.customerInfo.source)
        assertTrue(result.customerInfo.entitlements[premium]!!.isActive)
        val body = h.purchaseBodies().single()
        assertEquals("tok-1", body.getString("purchaseToken"))
        assertEquals("pro_monthly", body.getString("productId"))
        assertEquals("subscription", body.getString("productType"))
        assertEquals("play_store", body.getString("store"))
        assertEquals("device-1", body.getString("appreferId"))
        assertTrue(h.queue.isEmpty)
        // The server acknowledges; the SDK neither acknowledges nor consumes a subscription.
        assertTrue(h.play.acknowledged.isEmpty())
        assertTrue(h.play.consumed.isEmpty())
    }

    @Test
    fun purchaseGoesAheadWithoutTokenWhenAppReferIsDown() = runTest {
        val h = harness("user_1")
        h.server.reachable = false
        var launched = false
        var launchedWith: String? = "unset"
        val result = runPurchase(
            h, "pro_monthly", ProductTypes.SUBSCRIPTION, ok(storePurchase("tok-1", "pro_monthly", isSubscription = null)),
        ) { launched = true; launchedWith = it }.await().getOrThrow()
        assertTrue(launched)
        assertNull(launchedWith)
        // Queued, still unlocked from Google Play.
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PURCHASED, result.outcome)
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, result.customerInfo.source)
        assertTrue(result.customerInfo.entitlements[premium]!!.isActive)
        assertEquals(listOf("tok-1"), h.queue.entries.map { it.purchaseToken })
        assertEquals(ProductTypes.SUBSCRIPTION, h.queue.entries[0].productType)
    }

    @Test
    fun userCancelledReturnsCancelledOutcome() = runTest {
        val h = harness("user_1")
        val result = runPurchase(
            h, "pro_monthly", ProductTypes.SUBSCRIPTION,
            StorePurchasesUpdate(BillingResponseCode.USER_CANCELED, null, emptyList()),
        ).await().getOrThrow()
        assertTrue(result.outcome == AppReferBilling.PurchaseResult.Outcome.USER_CANCELLED)
        assertTrue(h.purchaseBodies().isEmpty())
    }

    @Test
    fun launchErrorThrowsStoreError() = runTest {
        val h = harness("user_1")
        val deferred = runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, null, launchCode = BillingResponseCode.BILLING_UNAVAILABLE)
        try {
            deferred.await().getOrThrow()
            fail()
        } catch (e: AppReferBillingException.Store) {
            assertEquals(BillingResponseCode.BILLING_UNAVAILABLE, e.responseCode)
        }
        // The next purchase can start.
        val next = runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, ok(storePurchase("t2", "pro_monthly", isSubscription = null)))
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PURCHASED, next.await().getOrThrow().outcome)
    }

    @Test
    fun secondPurchaseWhileFirstIsOpenIsRejected() = runTest {
        val h = harness("user_1")
        val first = runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, null)
        val second = async { runCatching { h.engine.purchase("pro_yearly", ProductTypes.SUBSCRIPTION) { BillingResponseCode.OK to null } } }
        advanceUntilIdle()
        try {
            second.await().getOrThrow()
            fail()
        } catch (_: AppReferBillingException.PurchaseInProgress) {
        }
        h.play.listener!!.invoke(StorePurchasesUpdate(BillingResponseCode.USER_CANCELED, null, emptyList()))
        assertEquals(AppReferBilling.PurchaseResult.Outcome.USER_CANCELLED, first.await().getOrThrow().outcome)
    }

    @Test
    fun pendingPurchaseGrantsNothingUntilItCompletes() = runTest {
        val h = harness("user_1")
        val pending = storePurchase("tok-p", "pro_monthly", isSubscription = null, state = StorePurchase.State.PENDING)
        val result = runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, ok(pending)).await().getOrThrow()
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PENDING, result.outcome)
        assertNull(result.customerInfo.entitlements[premium])
        assertTrue(h.purchaseBodies().isEmpty())

        // Payment completes later: Play calls onPurchasesUpdated with no purchase() waiting.
        val purchased = storePurchase("tok-p", "pro_monthly")
        h.play.purchases += purchased
        h.play.listener!!.invoke(ok(purchased.copy(isSubscription = null)))
        advanceUntilIdle()
        assertEquals("tok-p", h.purchaseBodies().single().getString("purchaseToken"))
        assertTrue(h.published.last().entitlements[premium]!!.isActive)
    }

    @Test
    fun consumableIsConsumedOnlyAfterAppReferAcceptsIt() = runTest {
        val h = harness("user_1")
        h.server.reachable = false
        runPurchase(h, "coins_100", ProductTypes.CONSUMABLE, ok(storePurchase("c1", "coins_100", isSubscription = null))).await().getOrThrow()
        assertTrue(h.play.consumed.isEmpty())
        assertEquals(ProductTypes.CONSUMABLE, h.queue.entries.single().productType)

        h.server.reachable = true
        h.engine.drainQueue()
        assertEquals(listOf("c1"), h.play.consumed)
        assertTrue(h.queue.isEmpty)
        assertEquals("consumable", h.purchaseBodies().last().getString("productType"))
    }

    @Test
    fun consumablePurchaseIsConsumedWhenAccepted() = runTest {
        val h = harness("user_1")
        val result = runPurchase(h, "coins_100", ProductTypes.CONSUMABLE, ok(storePurchase("c1", "coins_100", isSubscription = null))).await().getOrThrow()
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PURCHASED, result.outcome)
        assertEquals(listOf("c1"), h.play.consumed)
    }

    @Test
    fun rejectedPurchaseIsNotQueued() = runTest {
        val h = harness("user_1")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT) // cache the account token
        h.server.status = 400
        val result = runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, ok(storePurchase("t1", "pro_monthly", isSubscription = null))).await().getOrThrow()
        assertTrue(h.queue.isEmpty)
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, result.customerInfo.source)
    }

    @Test
    fun queueDrainsWhenServerComesBack() = runTest {
        val h = harness("user_1")
        h.server.reachable = false
        runPurchase(h, "pro_monthly", ProductTypes.SUBSCRIPTION, ok(storePurchase("t1", "pro_monthly", isSubscription = null))).await().getOrThrow()
        assertEquals(1, h.queue.entries.size)

        h.server.reachable = true
        val info = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertTrue(h.queue.isEmpty)
        assertEquals(AppReferCustomerInfo.Source.SERVER, info.source)
        assertEquals("t1", h.purchaseBodies().last().getString("purchaseToken"))
        assertTrue(h.published.last().entitlements[premium]!!.isActive)
    }

    @Test
    fun serverAnswerIsMergedWithDeviceWhilePurchaseStillQueued() = runTest {
        val h = harness("user_1")
        // The purchases endpoint is failing, customers works: AppRefer doesn't know the purchase yet.
        h.server.purchasesStatus = 503
        h.queue.add(PendingPurchase("t1", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_1", null, NOW, 1))
        h.play.purchases += storePurchase("t1", "pro_monthly")
        val info = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(AppReferCustomerInfo.Source.SERVER, info.source)
        assertTrue(info.entitlements[premium]!!.isActive)
        assertEquals(1, h.queue.entries.size)
        assertTrue(h.queue.entries.single().attempts > 1)
    }

    // endregion

    // region Sync and restore

    @Test
    fun startSendsUnacknowledgedAndMigratesAcknowledgedPurchases() = runTest {
        val h = harness("user_1")
        h.play.purchases += storePurchase("new", "pro_monthly", acknowledged = false)
        h.play.purchases += storePurchase("old", "lifetime", isSubscription = false, acknowledged = true)
        h.engine.start()
        assertEquals(listOf("new", "old"), h.purchaseBodies().map { it.getString("purchaseToken") })

        // Already accepted this launch: not sent again even if Play's cache still says unacknowledged.
        h.play.purchases.replaceAll { it.copy(isAcknowledged = false) }
        h.engine.syncPurchases()
        assertEquals(listOf("new", "old"), h.purchaseBodies().map { it.getString("purchaseToken") })
        h.engine.syncPurchases()
        assertEquals(2, h.purchaseBodies().size)
    }

    @Test
    fun syncSkipsPendingPurchases() = runTest {
        val h = harness("user_1")
        h.play.purchases += storePurchase("p", "pro_monthly", state = StorePurchase.State.PENDING)
        h.engine.syncPurchases()
        assertTrue(h.purchaseBodies().isEmpty())
    }

    @Test
    fun unacceptedPurchaseIsAcknowledgedOnDeviceOnceQueued() = runTest {
        val h = harness("user_1")
        h.server.reachable = false
        h.play.purchases += storePurchase("sub", "pro_monthly")
        h.play.purchases += storePurchase("coins", "coins_100", isSubscription = false)
        h.engine.syncPurchases()
        // Acknowledged right away (no 48 h wait for a reopen); consumables are not consumed.
        assertEquals(listOf("sub", "coins"), h.play.acknowledged)
        assertTrue(h.play.consumed.isEmpty())
        // Still queued so AppRefer records them once reachable.
        assertEquals(setOf("sub", "coins"), h.queue.entries.map { it.purchaseToken }.toSet())
    }

    @Test
    fun repeatedSyncWhileOfflineCountsAttemptsInsteadOfResettingTheQueue() = runTest {
        val h = harness("user_1")
        h.server.reachable = false
        h.play.purchases += storePurchase("t1", "pro_monthly")
        h.engine.syncPurchases()
        h.now += DAY
        h.engine.syncPurchases()
        assertEquals(NOW, h.queue.entries.single().enqueuedAtMillis)
        assertTrue(h.queue.entries.single().attempts >= 1)
    }

    @Test
    fun restorePostsEveryPurchase() = runTest {
        val h = harness("user_1")
        h.play.purchases += storePurchase("a", "pro_monthly", acknowledged = true)
        h.play.purchases += storePurchase("b", "lifetime", isSubscription = false, acknowledged = true)
        h.play.purchases += storePurchase("p", "pro_yearly", state = StorePurchase.State.PENDING)
        val info = h.engine.restore()
        assertEquals(listOf("a" to "subscription", "b" to "non_consumable"), h.purchaseBodies().map { it.getString("purchaseToken") to it.getString("productType") })
        assertEquals(AppReferCustomerInfo.Source.SERVER, info.source)
        assertTrue(info.entitlements[premium]!!.isActive)
    }

    @Test
    fun restoreWithNothingOwnedFetchesCustomer() = runTest {
        val h = harness("user_1")
        val info = h.engine.restore()
        assertEquals(listOf("POST /api/billing/v1/customers"), h.server.paths())
        assertEquals(AppReferCustomerInfo.Source.SERVER, info.source)
    }

    @Test
    fun restoreThrowsWhenGooglePlayIsUnavailable() = runTest {
        val h = harness("user_1")
        h.play.queryCode = BillingResponseCode.BILLING_UNAVAILABLE
        try {
            h.engine.restore()
            fail()
        } catch (e: AppReferBillingException.Store) {
            assertEquals(BillingResponseCode.BILLING_UNAVAILABLE, e.responseCode)
        }
    }

    @Test
    fun restoreOfflineQueuesAndFallsBack() = runTest {
        val h = harness("user_1")
        h.server.reachable = false
        h.play.purchases += storePurchase("a", "pro_monthly", acknowledged = true)
        val info = h.engine.restore()
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, info.source)
        assertTrue(info.entitlements[premium]!!.isActive)
        assertEquals(listOf("a"), h.queue.entries.map { it.purchaseToken })
    }

    // endregion

    // region Identity

    @Test
    fun logInFromAnonymousCallsIdentify() = runTest {
        val h = harness()
        val anon = h.engine.currentAppUserId
        assertTrue(h.engine.isAnonymous)
        h.server.owned[anon] = mutableSetOf("pro_monthly")
        val info = h.engine.logIn("user_1")
        val identify = h.server.requests.first { it.url.contains("/identify") }
        assertEquals(anon, JSONObject(identify.body!!).getString("anonymousId"))
        assertEquals("user_1", info.appUserId)
        assertTrue(info.entitlements[premium]!!.isActive)
        assertFalse(h.engine.isAnonymous)
        assertTrue(h.storage.pendingIdentifies.isEmpty())
    }

    @Test
    fun logInWhileOfflineRetriesIdentifyLater() = runTest {
        val h = harness()
        val anon = h.engine.currentAppUserId
        h.server.reachable = false
        h.engine.logIn("user_1")
        assertEquals(listOf(anon to "user_1"), h.storage.pendingIdentifies)
        h.server.reachable = true
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertTrue(h.storage.pendingIdentifies.isEmpty())
    }

    @Test
    fun logInRejectsInvalidIds() = runTest {
        val h = harness()
        for (bad in listOf("", "has space", "\$ARAnon:0f8fad5b-d9cb-469f-a165-70867728950e", "x".repeat(257))) {
            try {
                h.engine.logIn(bad)
                fail(bad)
            } catch (_: AppReferBillingException.InvalidAppUserId) {
            }
        }
        assertTrue(h.server.requests.isEmpty())
    }

    @Test
    fun logOutMintsNewAnonymousId() = runTest {
        val h = harness("user_1")
        val before = h.storage.anonymousId()
        val info = h.engine.logOut()
        assertTrue(h.engine.isAnonymous)
        assertTrue(BillingStorage.isAnonymous(info.appUserId))
        assertFalse(info.appUserId == before)
        assertTrue(h.server.requests.none { it.url.contains("/identify") })
    }

    @Test
    fun configureWithAppUserIdAfterAnonymousUseQueuesIdentify() = runTest {
        val storage = newStorage()
        val anon = storage.anonymousId()
        harness("user_1", storage)
        assertEquals(listOf(anon to "user_1"), storage.pendingIdentifies)
        assertEquals("user_1", storage.currentAppUserId)
    }

    @Test
    fun configureWithAppUserIdOnFreshInstallSkipsIdentify() = runTest {
        val storage = newStorage()
        harness("user_1", storage)
        assertTrue(storage.pendingIdentifies.isEmpty())
        assertEquals("user_1", storage.loggedInAppUserId)
    }

    // endregion
}
