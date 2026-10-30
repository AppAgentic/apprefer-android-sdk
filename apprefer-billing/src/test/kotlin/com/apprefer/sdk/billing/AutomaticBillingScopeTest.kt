package com.apprefer.sdk.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticBillingScopeTest {
    private fun TestScope.setup(explicit: BillingEnvironment? = null, key: () -> String? = { "pk_live_review" }, appUserId: String? = "review-user"): Pair<Harness, AutomaticBillingScope> {
        val storage = newStorage()
        val automatic = AutomaticBillingScope(storage, key, explicit)
        return Harness(this, appUserId, storage, options = BillingEngine.Options(defaultEntitlement = "premium", environment = automatic::current, automaticScope = automatic), apiKey = key) to automatic
    }
    private suspend fun TestScope.buy(h: Harness): BillingEngine.PurchaseOutcome {
        val purchase = storePurchase("test-token", "premium.plan")
        h.play.purchases += purchase
        h.server.purchaseEnvironments[purchase.purchaseToken] = BillingEnvironment.SANDBOX
        val pending = async {
            h.engine.purchase("premium.plan", ProductTypes.SUBSCRIPTION) {
                h.engine.onPurchasesUpdated(StorePurchasesUpdate(BillingResponseCode.OK, null, listOf(purchase)))
                BillingResponseCode.OK to null
            }
        }
        advanceUntilIdle()
        return pending.await()
    }
    @Test fun freshLiveKeySandboxPurchaseRetriesLocallyThenCommitsOwnedContext() = runTest {
        val (h, automatic) = setup()
        assertEquals(BillingEnvironment.PRODUCTION, automatic.current())
        val outcome = buy(h)
        assertEquals(BillingEnvironment.SANDBOX, outcome.customerInfo.environment)
        assertTrue(outcome.customerInfo.hasActiveEntitlement)
        assertEquals(BillingEnvironment.SANDBOX, automatic.current())
        val calls = h.server.requests.filter { it.url.contains("/purchases") }
        assertEquals(listOf("production", "sandbox"), calls.map { it.headers["X-AppRefer-Billing-Environment"] })
        assertEquals(BillingEnvironment.SANDBOX, h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT).environment)
        // A new controller/process keeps context only for this install, key and user.
        val restartedStorage = BillingStorage(InMemoryKeyValueStore(), h.storage.directory)
        restartedStorage.loggedInAppUserId = "review-user"
        assertEquals(BillingEnvironment.SANDBOX, AutomaticBillingScope(restartedStorage, { "pk_live_review" }).current())
        assertEquals(BillingEnvironment.PRODUCTION, AutomaticBillingScope(restartedStorage, { "pk_live_other" }).current())
        restartedStorage.loggedInAppUserId = "different-user"
        assertEquals(BillingEnvironment.PRODUCTION, AutomaticBillingScope(restartedStorage, { "pk_live_review" }).current())
        val otherInstall = newStorage()
        otherInstall.loggedInAppUserId = "review-user"
        assertEquals(BillingEnvironment.PRODUCTION, AutomaticBillingScope(otherInstall, { "pk_live_review" }).current())
    }
    @Test fun explicitProductionNeverAdoptsSandbox() = runTest {
        val (h, automatic) = setup(BillingEnvironment.PRODUCTION)
        val result = buy(h)
        assertFalse(result.customerInfo.hasActiveEntitlement)
        assertEquals(BillingEnvironment.PRODUCTION, automatic.current())
        assertTrue(h.queue.entries.any { it.purchaseToken == "test-token" })
        assertFalse(h.server.requests.any { it.headers["X-AppRefer-Billing-Environment"] == "sandbox" })
    }
    @Test fun oldQueuedProofCannotSwitchAutomaticContext() = runTest {
        val (h, automatic) = setup()
        h.server.purchaseEnvironments["legacy"] = BillingEnvironment.SANDBOX
        h.queue.add(PendingPurchase("legacy", "premium.plan", ProductTypes.SUBSCRIPTION, "review-user", null, NOW, 0, migrationOnly = true))
        h.engine.drainQueue()
        assertEquals(BillingEnvironment.PRODUCTION, automatic.current())
        assertTrue(h.queue.entries.any { it.purchaseToken == "legacy" })
        assertFalse(h.server.requests.any { it.headers["X-AppRefer-Billing-Environment"] == "sandbox" })
    }
    @Test fun failedOwnershipNeverCommitsOrGrantsSandbox() = runTest {
        val (h, automatic) = setup()
        h.server.rejectedTokens += "test-token"
        val result = buy(h)
        assertEquals(BillingEnvironment.PRODUCTION, automatic.current())
        assertFalse(result.customerInfo.hasActiveEntitlement)
    }
    @Test fun identityChangeDuringRetryCannotCommitOldContext() = runTest {
        val (h, automatic) = setup()
        h.server.onPurchase = { r -> if (r.headers["X-AppRefer-Billing-Environment"] == "sandbox") h.storage.loggedInAppUserId = "next-user" }
        val result = buy(h)
        assertEquals(BillingEnvironment.PRODUCTION, automatic.current())
        assertFalse(result.customerInfo.hasActiveEntitlement)
        assertNull(h.storage.automaticEnvironment("review-user", "pk_live_review"))
    }
    @Test fun keyChangeDuringRetryCannotCommitOldContext() = runTest {
        var key = "pk_live_review"
        val (h, automatic) = setup(key = { key })
        h.server.onPurchase = { r -> if (r.headers["X-AppRefer-Billing-Environment"] == "sandbox") key = "pk_live_next" }
        val result = buy(h)
        assertEquals(BillingEnvironment.PRODUCTION, automatic.current())
        assertFalse(result.customerInfo.hasActiveEntitlement)
        assertNull(h.storage.automaticEnvironment("review-user", "pk_live_review"))
    }
    @Test fun identityRoundTripAndResetInvalidateCapture() = runTest {
        val storage = newStorage()
        storage.loggedInAppUserId = "same-user"
        var epoch = 0
        val automatic = AutomaticBillingScope(storage, { "pk_live_a" }, resetEpoch = { epoch })
        val first = automatic.capture("same-user")!!
        storage.loggedInAppUserId = "other-user"
        storage.loggedInAppUserId = "same-user"
        assertFalse(automatic.commit(first, BillingEnvironment.SANDBOX))
        val second = automatic.capture("same-user")!!
        epoch++
        assertFalse(automatic.commit(second, BillingEnvironment.SANDBOX))
    }
    @Test fun newlyInitiatedPendingPurchaseCanCompleteAfterRelaunch() = runTest {
        val kv = InMemoryKeyValueStore()
        val storage = newStorage(kv)
        val automatic = AutomaticBillingScope(storage, { "pk_live_review" })
        val h = Harness(this, "review-user", storage, options = BillingEngine.Options(defaultEntitlement = "premium", environment = automatic::current, automaticScope = automatic), apiKey = { "pk_live_review" })
        val pendingStore = storePurchase("pending-test", "premium.plan", state = StorePurchase.State.PENDING)
        h.play.purchases += pendingStore
        h.server.purchaseEnvironments["pending-test"] = BillingEnvironment.SANDBOX
        val result = async { h.engine.purchase("premium.plan", ProductTypes.SUBSCRIPTION) {
            h.engine.onPurchasesUpdated(StorePurchasesUpdate(BillingResponseCode.OK, null, listOf(pendingStore)))
            BillingResponseCode.OK to null
        } }
        advanceUntilIdle()
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PENDING, result.await().outcome)
        assertEquals(BillingEnvironment.PRODUCTION, automatic.current())
        val reopened = BillingStorage(kv, storage.directory)
        val nextScope = AutomaticBillingScope(reopened, { "pk_live_review" })
        val play = FakePlay().apply { purchases += pendingStore.copy(state = StorePurchase.State.PURCHASED) }
        val restarted = Harness(this, "review-user", reopened, options = BillingEngine.Options(defaultEntitlement = "premium", environment = nextScope::current, automaticScope = nextScope), play = play, server = h.server, apiKey = { "pk_live_review" })
        restarted.engine.syncPurchases()
        assertEquals(BillingEnvironment.SANDBOX, nextScope.current())
        assertTrue(restarted.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT).hasActiveEntitlement)
    }
    @Test fun confirmedAnonymousIdentifyRetainsItsSandboxContext() = runTest {
        val (h, automatic) = setup(appUserId = null)
        val anon = h.storage.currentAppUserId
        assertTrue(BillingStorage.isAnonymous(anon))
        assertTrue(buy(h).customerInfo.hasActiveEntitlement)
        val member = h.engine.logIn("member")
        assertEquals(BillingEnvironment.SANDBOX, automatic.current())
        assertEquals(BillingEnvironment.SANDBOX, member.environment)
        assertTrue(member.hasActiveEntitlement)
        val other = h.engine.logIn("other-member")
        assertEquals(BillingEnvironment.PRODUCTION, automatic.current())
        assertFalse(other.hasActiveEntitlement)
    }
    @Test fun firstPurchaseWaitsForTheAttributionKeyBeforeCapturingAutomaticScope() = runTest {
        var key: String? = null
        val storage = newStorage()
        val automatic = AutomaticBillingScope(storage, { key })
        val h = Harness(this, "review-user", storage, options = BillingEngine.Options(defaultEntitlement = "premium", environment = automatic::current, automaticScope = automatic), apiKey = { key }, keyWaitAttempts = 5)
        launch { delay(10); key = "pk_live_review" }
        val result = buy(h)
        assertEquals(BillingEnvironment.SANDBOX, automatic.current())
        assertTrue(result.customerInfo.hasActiveEntitlement)
    }

    @Test fun authorizedQueueRecoversAfterOutageAndRelaunchWithoutAuthorizingLegacyQueues() = runTest {
        for (afterClassification in listOf(false, true)) {
            val kv = InMemoryKeyValueStore()
            val storage = newStorage(kv)
            val automatic = AutomaticBillingScope(storage, { "pk_live_review" })
            val h = Harness(this, "review-user", storage, options = BillingEngine.Options(defaultEntitlement = "premium", environment = automatic::current, automaticScope = automatic), apiKey = { "pk_live_review" })
            if (afterClassification) h.server.onPurchase = { r ->
                if (r.headers["X-AppRefer-Billing-Environment"] == "sandbox") throw AppReferBillingException.Network("outage")
            } else h.server.reachable = false
            val result = buy(h)
            assertFalse(result.customerInfo.hasActiveEntitlement)
            assertTrue(h.queue.entries.any { it.purchaseToken == "test-token" })
            assertTrue(storage.isAutomaticProof("review-user", "pk_live_review", "test-token"))
            assertEquals(BillingEnvironment.PRODUCTION, automatic.current())
            h.server.reachable = true
            h.server.onPurchase = null
            val reopened = BillingStorage(kv, storage.directory)
            val next = AutomaticBillingScope(reopened, { "pk_live_review" })
            val restarted = Harness(this, "review-user", reopened, options = BillingEngine.Options(defaultEntitlement = "premium", environment = next::current, automaticScope = next), play = h.play, server = h.server, apiKey = { "pk_live_review" })
            restarted.engine.drainQueue()
            assertEquals(BillingEnvironment.SANDBOX, next.current())
            assertFalse(restarted.queue.entries.any { it.purchaseToken == "test-token" })
            assertTrue(restarted.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT).hasActiveEntitlement)
            assertFalse(reopened.isAutomaticProof("review-user", "pk_live_review", "test-token"))
        }
    }
    @Test fun acceptedAcknowledgedPurchaseRecoversAfterCrashBeforeLocalCommit() = runTest {
        val kv = InMemoryKeyValueStore()
        val storage = newStorage(kv)
        val automatic = AutomaticBillingScope(storage, { "pk_live_review" })
        val h = Harness(this, "review-user", storage, options = BillingEngine.Options(defaultEntitlement = "premium", environment = automatic::current, automaticScope = automatic), apiKey = { "pk_live_review" })
        val accountToken = h.engine.accountToken()
        val intent = PurchaseIntent("premium.plan", "review-user", accountToken, NOW)
        assertTrue(storage.addPurchaseIntent(intent))
        val ticket = automatic.capture("review-user")!!
        automatic.authorizePending(ticket, intent, "crash-token")
        automatic.authorizeProof(ticket, "crash-token")
        val purchased = storePurchase("crash-token", "premium.plan", accountId = accountToken)
        h.play.purchases += purchased
        h.server.purchaseEnvironments["crash-token"] = BillingEnvironment.SANDBOX
        val body = org.json.JSONObject().put("appUserId", "review-user").put("purchaseToken", "crash-token").put("productId", "premium.plan").put("productType", "subscription")
        assertEquals(200, h.server.handle(BillingRequest("POST", "https://trk.test/api/billing/v1/purchases", mapOf("X-AppRefer-Billing-Environment" to "sandbox"), body.toString())).status)
        assertTrue(h.play.purchases.single().isAcknowledged)
        // The app died here: neither the automatic context nor CustomerInfo committed.
        storage.recordPurchaseEnvironment("crash-token", BillingEnvironment.SANDBOX)
        val reopened = BillingStorage(kv, storage.directory)
        val next = AutomaticBillingScope(reopened, { "pk_live_review" })
        val restarted = Harness(this, "review-user", reopened, options = BillingEngine.Options(defaultEntitlement = "premium", environment = next::current, automaticScope = next), play = h.play, server = h.server, apiKey = { "pk_live_review" })
        assertEquals(BillingEnvironment.PRODUCTION, next.current())
        restarted.engine.syncPurchases()
        assertEquals(BillingEnvironment.SANDBOX, next.current())
        assertTrue(restarted.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT).hasActiveEntitlement)
    }
    @Test fun resetMarkerRoundTripCannotRevivePendingOrExactProofAuthorization() = runTest {
        val kv = InMemoryKeyValueStore()
        val storage = newStorage(kv)
        storage.loggedInAppUserId = "same-user"
        val scope = AutomaticBillingScope(storage, { "pk_live_a" })
        val intent = PurchaseIntent("plan", "same-user", null, NOW)
        val ticket = scope.capture("same-user")!!
        scope.authorizePending(ticket, intent, "pending-token")
        scope.authorizeProof(ticket, "pending-token")
        kv.putString(BillingStorage.KEY_RESET_USER, "same-user")
        assertTrue(storage.clearResetUser())
        val reopened = BillingStorage(kv, storage.directory)
        val next = AutomaticBillingScope(reopened, { "pk_live_a" })
        assertNull(next.recoverProof("same-user", "pending-token"))
        assertNull(next.recoverPending(intent, storePurchase("pending-token", "plan")))
    }

}
