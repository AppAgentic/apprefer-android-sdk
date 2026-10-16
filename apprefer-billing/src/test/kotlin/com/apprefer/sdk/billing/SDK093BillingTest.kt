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
import org.junit.Test

/**
 * 0.9.3: the `AppRefer.reset()` account gate survives a relaunch. reset() persists the Billing
 * user it ran for (`apprefer_billing_reset_user`); while that is still the current Billing user,
 * no device id, no re-link and identify sends `link: false`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SDK093BillingTest {
    private val kv = InMemoryKeyValueStore()
    private val storage = newStorage(kv)

    private fun Harness.bodies(path: String) =
        server.requests.filter { it.url.contains(path) && it.method == "POST" }.map { JSONObject(it.body!!) }

    private fun TestScope.buy(h: Harness, token: String) = async {
        runCatching { h.engine.purchase("pro_monthly", ProductTypes.SUBSCRIPTION) { BillingResponseCode.OK to null } }
    }.also {
        advanceUntilIdle()
        val p = storePurchase(token, "pro_monthly")
        h.play.purchases += p
        h.play.listener!!.invoke(StorePurchasesUpdate(BillingResponseCode.OK, null, listOf(p)))
    }

    /** What `AppRefer.reset()` writes into Billing's storage (attribution module, `BillingResetMarker`). */
    private fun resetMarker(user: String) { kv.putString(BillingStorage.KEY_RESET_USER, user) }

    @Test
    fun markerKeyMatchesTheAttributionModule() {
        assertEquals("apprefer_billing_reset_user", BillingStorage.KEY_RESET_USER)
    }

    @Test
    fun afterARelaunchThePreviousUserStillGetsNoDeviceId() = runTest {
        // Launch 1: user_a registered with the device id.
        val first = Harness(this, "user_a", storage = storage)
        first.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals("device-1", first.bodies("/customers").single().getString("appreferId"))
        // reset() without AppReferBilling.logOut(), then the app relaunches: the in-process epoch
        // starts again at 0 and configure runs (gate open) while Billing still serves user_a.
        resetMarker("user_a")
        val h = Harness(this, "user_a", storage = storage)
        h.engine.setAttributes(mapOf("\$email" to "a@sparkly.test", "\$gpsAdId" to "gaid"))
        h.queue.add(PendingPurchase("tok-q", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_a", "device-1", NOW, 0))
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        buy(h, "tok-2").await().getOrThrow()
        h.engine.restore()
        val sent = h.bodies("/customers") + h.bodies("/purchases")
        assertTrue(sent.isNotEmpty())
        assertTrue("no device id for the account current at reset()", sent.none { it.has("appreferId") })
        assertTrue(h.bodies("/customers").none { it.optJSONObject("attributes")?.has("\$gpsAdId") == true })
        assertEquals("registered once, never re-linked", 1, h.bodies("/customers").size)
        assertTrue(h.bodies("/purchases").any { it.getString("purchaseToken") == "tok-q" })
        assertEquals("user_a", storage.resetUser)
    }

    @Test
    fun anonymousUserCurrentAtResetIsHeldToo() = runTest {
        val anon = storage.anonymousId()
        resetMarker(anon)
        val h = Harness(this, storage = storage)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        val customer = h.bodies("/customers").single()
        assertEquals(anon, customer.getString("appUserId"))
        assertFalse(customer.has("appreferId"))
    }

    @Test
    fun identifySendsLinkFalseWhileTheMarkerNamesTheCurrentUser() = runTest {
        // An anonymous -> user_a merge queued before reset(), sent after the relaunch.
        val anon = storage.anonymousId()
        storage.addPendingIdentify(anon, "user_a")
        storage.loggedInAppUserId = "user_a"
        resetMarker("user_a")
        val h = Harness(this, "user_a", storage = storage)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        val identify = JSONObject(h.server.requests.single { it.url.contains("/identify") }.body!!)
        assertFalse(identify.getBoolean("link"))
    }

    @Test
    fun logInWithTheSameIdClearsTheMarkerAndLinksOnce() = runTest {
        storage.loggedInAppUserId = "user_a"
        resetMarker("user_a")
        val h = Harness(this, "user_a", storage = storage)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertFalse(h.bodies("/customers").single().has("appreferId"))
        h.engine.logIn("user_a") // the app says who the user is now
        assertNull(storage.resetUser)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        buy(h, "tok-1").await().getOrThrow()
        val customers = h.bodies("/customers")
        assertEquals("re-linked once now", 2, customers.size)
        assertEquals("device-1", customers.last().getString("appreferId"))
        assertEquals("device-1", h.bodies("/purchases").last().getString("appreferId"))
    }

    @Test
    fun logOutClearsTheMarker() = runTest {
        storage.loggedInAppUserId = "user_a"
        resetMarker("user_a")
        val h = Harness(this, "user_a", storage = storage)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertFalse(h.bodies("/customers").single().has("appreferId"))
        h.engine.logOut()
        assertNull(storage.resetUser)
        val anon = h.bodies("/customers").last()
        assertTrue(anon.getString("appUserId").startsWith("\$ARAnon:"))
        assertEquals("device-1", anon.getString("appreferId"))
    }

    @Test
    fun aDifferentBillingUserClearsTheMarker() = runTest {
        storage.loggedInAppUserId = "user_a"
        resetMarker("user_a")
        // configure(appUserId = "user_b") on the next launch.
        val h = Harness(this, "user_b", storage = storage)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertNull(storage.resetUser)
        assertEquals("device-1", h.bodies("/customers").single().getString("appreferId"))
        val identify = h.server.requests.filter { it.url.contains("/identify") }
        assertTrue(identify.all { JSONObject(it.body!!).getBoolean("link") })
    }

    @Test
    fun aMarkerForSomeoneElseIsClearedWhenSeen() = runTest {
        storage.loggedInAppUserId = "user_b"
        resetMarker("user_a")
        val h = Harness(this, "user_b", storage = storage)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals("device-1", h.bodies("/customers").single().getString("appreferId"))
        assertNull(storage.resetUser)
    }

    // region Round 2: logOut's fresh anonymous id is never the previous account

    @Test
    fun logOutReportsTheIdItMintedAndLogInReportsNone() = runTest {
        storage.loggedInAppUserId = "user_a"
        val h = Harness(this, "user_a", storage = storage)
        h.engine.logOut()
        val minted = storage.currentAppUserId
        assertTrue(minted.startsWith("\$ARAnon:"))
        h.engine.logIn("user_b")
        h.engine.logOut()
        assertEquals(listOf(minted, null, storage.currentAppUserId), h.identityReports)
    }

    @Test
    fun theMarkerIsClearedInsideTheIdentityReportAfterTheNewUserIsStored() = runTest {
        storage.loggedInAppUserId = "user_a"
        resetMarker("user_a")
        val seen = mutableListOf<Pair<String?, String?>>()
        val h = Harness(this, "user_a", storage = storage, identityChanged = { _, clear ->
            seen += storage.loggedInAppUserId to storage.resetUser
            clear()
            seen += storage.loggedInAppUserId to storage.resetUser
        })
        h.engine.logIn("user_b")
        assertEquals(listOf<Pair<String?, String?>>("user_b" to "user_a", "user_b" to null), seen)
    }

    // endregion
}
