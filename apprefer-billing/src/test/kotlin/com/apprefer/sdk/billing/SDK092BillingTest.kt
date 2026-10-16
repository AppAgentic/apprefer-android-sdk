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

    @Test
    fun sendableDropsOnlyDeviceSignals() {
        val all = mapOf("\$email" to "e", "\$displayName" to "d", "\$phoneNumber" to "p", "\$gpsAdId" to "g",
            "\$fbAnonId" to "f", "\$deviceVersion" to "v", "\$ip" to "i")
        assertEquals(all, BillingEngine.sendable(all, linked = true))
        assertEquals(mapOf("\$email" to "e", "\$displayName" to "d", "\$phoneNumber" to "p"), BillingEngine.sendable(all, linked = false))
    }
}
