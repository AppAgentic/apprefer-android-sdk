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

/** 0.9.2: Billing sends no device id or device attribute while the attribution gate is closed. */
@OptIn(ExperimentalCoroutinesApi::class)
class SDK092BillingTest {
    /** Stands in for `AppReferInternal.linkableDeviceId()`: null with collection off or after reset. */
    private class Gate { @Volatile var open = true; fun deviceId(): String? = if (open) "device-1" else null }

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

    @Test
    fun normalSendsTheDeviceIdAndDeviceAttributes() = runTest {
        val gate = Gate()
        val h = Harness(this, "user_1", appreferId = gate::deviceId)
        h.engine.setAttributes(mapOf("\$email" to "a@b.com", "\$gpsAdId" to "gaid"))
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        buy(h, "tok-1").await().getOrThrow()
        val customer = h.bodies("/customers").single()
        assertEquals("device-1", customer.getString("appreferId"))
        assertEquals("gaid", customer.getJSONObject("attributes").getString("\$gpsAdId"))
        assertEquals("device-1", h.bodies("/purchases").single().getString("appreferId"))
    }

    @Test
    fun collectionOffSendsNoDeviceIdYetPurchasesAndEntitlementsWork() = runTest {
        val gate = Gate().apply { open = false }
        val h = Harness(this, "user_1", appreferId = gate::deviceId)
        h.engine.setAttributes(mapOf("\$email" to "a@b.com", "\$gpsAdId" to "gaid", "\$fbAnonId" to "fb", "\$ip" to "1.2.3.4"))
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        val result = buy(h, "tok-1").await().getOrThrow()
        val customer = h.bodies("/customers").single()
        val purchase = h.bodies("/purchases").single()
        assertFalse(customer.has("appreferId"))
        assertEquals(setOf("\$email"), customer.getJSONObject("attributes").keys().asSequence().toSet())
        assertFalse(purchase.has("appreferId"))
        assertEquals("tok-1", purchase.getString("purchaseToken"))
        assertEquals(AppReferBilling.PurchaseResult.Outcome.PURCHASED, result.outcome)
        assertTrue(result.customerInfo.entitlements["premium"]!!.isActive)
        // Withheld device attributes are dropped, never sent later.
        gate.open = true
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertTrue(h.storage.pendingAttributes.isEmpty())
        assertTrue(h.bodies("/customers").none { it.optJSONObject("attributes")?.has("\$gpsAdId") == true })
    }

    @Test
    fun afterResetNoDeviceIdUntilConfigureThenLinkedOnce() = runTest {
        val gate = Gate()
        val h = Harness(this, "user_b", appreferId = gate::deviceId)
        // Queued before reset, with the device id on disk.
        h.queue.add(PendingPurchase("tok-q", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_b", "device-1", NOW, 0))
        gate.open = false // reset(): closed until configure
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        buy(h, "tok-2").await().getOrThrow()
        assertTrue(h.queue.isEmpty)
        val beforeConfigure = h.bodies("/customers") + h.bodies("/purchases")
        assertEquals(3, beforeConfigure.size)
        assertTrue(beforeConfigure.none { it.has("appreferId") })
        assertTrue(h.bodies("/purchases").any { it.getString("purchaseToken") == "tok-q" })

        gate.open = true // configure for the next user
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        buy(h, "tok-3").await().getOrThrow()
        val customers = h.bodies("/customers")
        assertEquals("one re-registration with the device id, then GETs", 2, customers.size)
        assertEquals("device-1", customers.last().getString("appreferId"))
        assertEquals("device-1", h.bodies("/purchases").last().getString("appreferId"))
    }

    // region Review fixes: the device id follows the account (reset epoch); identify sends link

    @Test
    fun afterResetTheEarlierBillingUserIsNeverLinkedUntilTheNextAccountIsSet() = runTest {
        val gate = Gate().apply { open = false }
        var epoch = 0
        val h = Harness(this, "user_a", appreferId = gate::deviceId, accountEpoch = { epoch })
        // user_a registered while the gate was closed: normally re-linked once it opens.
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        // AppRefer.reset(), then AppRefer.configure(userId: "B"), while Billing is still on user_a.
        epoch++; gate.open = true
        h.queue.add(PendingPurchase("tok-q", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_a", "device-1", NOW, 0))
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        buy(h, "tok-2").await().getOrThrow()
        h.engine.restore()
        val forA = h.bodies("/customers") + h.bodies("/purchases")
        assertTrue(forA.all { it.getString("appUserId") == "user_a" })
        assertTrue("no device id and no re-link for the earlier account", forA.none { it.has("appreferId") })
        assertEquals("registered once, never re-linked", 1, h.bodies("/customers").size)
        assertTrue(h.bodies("/purchases").any { it.getString("purchaseToken") == "tok-q" })

        // The next account is set: linked once, then its purchases carry the device id.
        h.engine.logIn("user_b")
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        buy(h, "tok-3").await().getOrThrow()
        val forB = h.bodies("/customers").filter { it.getString("appUserId") == "user_b" }
        assertEquals(1, forB.size)
        assertEquals("device-1", forB.single().getString("appreferId"))
        val purchaseB = h.bodies("/purchases").last()
        assertEquals("tok-3", purchaseB.getString("purchaseToken"))
        assertEquals("device-1", purchaseB.getString("appreferId"))
    }

    @Test
    fun logOutAfterResetSetsTheNextAccount() = runTest {
        val gate = Gate()
        var epoch = 0
        val h = Harness(this, "user_a", appreferId = gate::deviceId, accountEpoch = { epoch })
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals("device-1", h.bodies("/customers").single().getString("appreferId"))
        epoch++ // reset() + configure
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(1, h.bodies("/customers").size)
        h.engine.logOut()
        val anon = h.bodies("/customers").last()
        assertTrue(anon.getString("appUserId").startsWith("\$ARAnon:"))
        assertEquals("device-1", anon.getString("appreferId"))
    }

    @Test
    fun aPurchaseOwnedByAnotherUserGetsNoDeviceId() = runTest {
        val h = Harness(this, "user_1")
        h.queue.add(PendingPurchase("tok-old", "pro_monthly", ProductTypes.SUBSCRIPTION, "user_old", "device-1", NOW, 0))
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        buy(h, "tok-1").await().getOrThrow()
        val purchases = h.bodies("/purchases").associateBy { it.getString("purchaseToken") }
        assertFalse("stored id never resent for someone else", purchases.getValue("tok-old").has("appreferId"))
        assertEquals("device-1", purchases.getValue("tok-1").getString("appreferId"))
    }

    @Test
    fun identifySendsLinkFalseWhileTheGateIsClosed() = runTest {
        for (open in listOf(false, true)) {
            val gate = Gate().apply { this.open = open }
            val h = Harness(this, appreferId = gate::deviceId)
            h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
            h.engine.logIn("user_1")
            val identify = JSONObject(h.server.requests.single { it.url.contains("/identify") }.body!!)
            assertEquals(open, identify.getBoolean("link"))
        }
    }

    @Test
    fun identifyLinkIsReadAtSendTime() = runTest {
        var allowed = true
        val h = Harness(this, linkAllowed = { allowed })
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        h.server.reachable = false
        runCatching { h.engine.logIn("user_1") }
        allowed = false // e.g. setCollectionEnabled(false) or reset() before the retry
        h.server.reachable = true
        h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        val identify = h.server.requests.filter { it.url.contains("/identify") }.last()
        assertFalse(JSONObject(identify.body!!).getBoolean("link"))
    }

    // endregion

    @Test
    fun sendableDropsOnlyDeviceSignals() {
        val all = mapOf("\$email" to "e", "\$displayName" to "d", "\$phoneNumber" to "p", "\$gpsAdId" to "g",
            "\$fbAnonId" to "f", "\$deviceVersion" to "v", "\$ip" to "i")
        assertEquals(all, BillingEngine.sendable(all, linked = true))
        assertEquals(mapOf("\$email" to "e", "\$displayName" to "d", "\$phoneNumber" to "p"), BillingEngine.sendable(all, linked = false))
    }
}
