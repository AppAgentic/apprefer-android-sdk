package com.apprefer.sdk.billing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerInfoTest {

    private val serverJson = """
        {
          "appUserId": "user_1",
          "entitlements": {
            "premium": {
              "id": "premium", "isActive": true, "productId": "pro_yearly", "store": "play_store",
              "expiresAtMs": 1900000000000, "willRenew": true, "periodType": "trial",
              "state": "active", "subscriptionKey": "abc"
            },
            "lifetime": {
              "id": "lifetime", "isActive": true, "productId": "forever", "store": "stripe",
              "expiresAtMs": null, "willRenew": false, "periodType": "normal",
              "state": "active", "subscriptionKey": "pi_1"
            }
          },
          "activeSubscriptions": ["pro_yearly"],
          "requestDateMs": 1800000000000,
          "accountToken": "0f8fad5b-d9cb-469f-a165-70867728950e",
          "purchase": {"productId": "pro_yearly", "state": "active", "acknowledged": true, "conversion": "sent"}
        }
    """.trimIndent()

    @Test
    fun decodesServerResponse() {
        val response = CustomerInfoJson.parseResponse(serverJson)
        val info = response.info
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", response.accountToken)
        assertEquals("user_1", info.appUserId)
        assertEquals(AppReferCustomerInfo.Source.SERVER, info.source)
        assertEquals(1_800_000_000_000L, info.requestDateMillis)
        assertEquals(setOf("pro_yearly"), info.activeSubscriptions)
        val premium = info.entitlements["premium"]!!
        assertTrue(premium.isActive)
        assertEquals("pro_yearly", premium.productIdentifier)
        assertEquals(BillingStore.PLAY_STORE, premium.store)
        assertEquals(1_900_000_000_000L, premium.expirationDate!!.time)
        assertEquals(BillingPeriodType.TRIAL, premium.periodType)
        assertEquals(BillingSubscriptionState.ACTIVE, premium.state)
        assertTrue(premium.willRenew)
        assertNull(info.entitlements["lifetime"]!!.expirationDate)
        assertEquals(BillingStore.STRIPE, info.entitlements["lifetime"]!!.store)
        assertEquals(2, info.entitlements.active.size)
        assertTrue(info.hasActiveEntitlement)
    }

    @Test
    fun unknownEnumValuesAndMissingIdDoNotFail() {
        val info = CustomerInfoJson.parseResponse(
            """{"appUserId":"u","entitlements":{"pro":{"isActive":true,"store":"amazon","periodType":"weird","state":"new"}}}""",
        ).info
        val pro = info.entitlements["pro"]!!
        assertEquals("pro", pro.identifier)
        assertEquals(BillingStore.UNKNOWN, pro.store)
        assertEquals(BillingPeriodType.UNKNOWN, pro.periodType)
        assertEquals(BillingSubscriptionState.UNKNOWN, pro.state)
        assertTrue(info.activeSubscriptions.isEmpty())
    }

    @Test(expected = AppReferBillingException.InvalidResponse::class)
    fun missingAppUserIdIsInvalid() {
        CustomerInfoJson.parseResponse("""{"error":"x"}""")
    }

    @Test
    fun encodeDecodeRoundTripKeepsSource() {
        val info = CustomerInfoJson.parseResponse(serverJson).info.withSource(AppReferCustomerInfo.Source.LOCAL_FALLBACK)
        val decoded = CustomerInfoJson.decode(JSONObject(CustomerInfoJson.encode(info).toString()))
        assertEquals(info, decoded)
    }

    @Test
    fun reevaluatedExpiresStaleCacheButKeepsGracePeriod() {
        val base = CustomerInfoJson.parseResponse(serverJson).info
        val grace = base.entitlements["premium"]!!.copy(identifier = "grace", productIdentifier = "pro_monthly", state = BillingSubscriptionState.IN_GRACE_PERIOD)
        val info = base.copy(
            entitlements = EntitlementInfos(base.entitlements.all + ("grace" to grace)),
            activeSubscriptions = setOf("pro_yearly", "pro_monthly"),
        )
        val later = info.reevaluated(1_950_000_000_000L)
        assertEquals(AppReferCustomerInfo.Source.CACHE, later.source)
        assertFalse(later.entitlements["premium"]!!.isActive)
        assertEquals(BillingSubscriptionState.EXPIRED, later.entitlements["premium"]!!.state)
        assertTrue(later.entitlements["grace"]!!.isActive)
        assertTrue(later.entitlements["lifetime"]!!.isActive)
        assertEquals(setOf("pro_monthly"), later.activeSubscriptions)
    }

    @Test
    fun publicTypealiasesResolveToSameTypes() {
        val info: CustomerInfo = CustomerInfoJson.parseResponse(serverJson).info
        val ent: EntitlementInfo? = info.entitlements["premium"]
        assertTrue(ent is AppReferEntitlementInfo)
    }
}
