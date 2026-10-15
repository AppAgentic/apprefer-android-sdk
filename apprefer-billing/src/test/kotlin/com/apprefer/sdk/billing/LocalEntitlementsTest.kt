package com.apprefer.sdk.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEntitlementsTest {

    private val configured = mapOf("pro_monthly" to listOf("premium", "ads_free"))

    private fun compute(vararg purchases: StorePurchase, consumables: Set<String> = setOf("coins_100")) =
        LocalEntitlements.compute(purchases.toList(), configured, emptyMap(), null, consumables)

    @Test
    fun activeSubscriptionUnlocksMappedEntitlements() {
        val local = compute(storePurchase("t1", "pro_monthly"))
        assertEquals(setOf("premium", "ads_free"), local.entitlements.keys)
        assertEquals(setOf("pro_monthly"), local.activeSubscriptions)
        val premium = local.entitlements["premium"]!!
        assertTrue(premium.isActive)
        assertEquals(BillingStore.PLAY_STORE, premium.store)
        assertTrue(premium.willRenew)
        assertNull(premium.expirationDate)
        // Matches the server's key for a never-upgraded subscription: sha256(token).
        assertEquals(LocalEntitlements.sha256Hex("t1"), premium.subscriptionKey)
        assertEquals(64, premium.subscriptionKey.length)
    }

    @Test
    fun pendingAndConsumablesGrantNothing() {
        val local = compute(
            storePurchase("t1", "pro_monthly", state = StorePurchase.State.PENDING),
            storePurchase("t2", "coins_100", isSubscription = false),
        )
        assertTrue(local.entitlements.isEmpty())
        assertTrue(local.activeSubscriptions.isEmpty())
    }

    @Test
    fun lifetimePurchaseUnlocksWithoutExpiry() {
        val local = compute(storePurchase("t1", "lifetime", isSubscription = false, autoRenewing = false))
        val premium = local.entitlements["premium"]!!
        assertTrue(premium.isActive)
        assertFalse(premium.willRenew)
        assertTrue(local.activeSubscriptions.isEmpty())
    }

    @Test
    fun lifetimeBeatsSubscriptionForSameEntitlement() {
        val local = LocalEntitlements.compute(
            listOf(storePurchase("t1", "pro_yearly"), storePurchase("t2", "lifetime", isSubscription = false)),
            emptyMap(), emptyMap(), null, emptySet(),
        )
        assertEquals("lifetime", local.entitlements["premium"]!!.productIdentifier)
    }

    @Test
    fun mappingPrecedenceConfiguredThenLearnedThenDefault() {
        val learned = mapOf("pro_monthly" to listOf("learned"), "pro_yearly" to listOf("gold"))
        assertEquals(listOf("premium", "ads_free"), LocalEntitlements.entitlementIds("pro_monthly", configured, learned, "x"))
        assertEquals(listOf("gold"), LocalEntitlements.entitlementIds("pro_yearly", configured, learned, "x"))
        assertEquals(listOf("x"), LocalEntitlements.entitlementIds("other", configured, learned, "x"))
        assertEquals(listOf("premium"), LocalEntitlements.entitlementIds("other", emptyMap(), emptyMap(), null))
    }

    private fun serverInfo(active: Boolean, expires: Long?, extra: Map<String, AppReferEntitlementInfo> = emptyMap()) =
        AppReferCustomerInfo(
            appUserId = "u",
            entitlements = EntitlementInfos(
                mapOf(
                    "premium" to AppReferEntitlementInfo(
                        "premium", active, "pro_monthly", BillingStore.PLAY_STORE, expires, active,
                        BillingPeriodType.NORMAL, if (active) BillingSubscriptionState.ACTIVE else BillingSubscriptionState.EXPIRED, "k",
                    ),
                ) + extra,
            ),
            activeSubscriptions = if (active) setOf("pro_monthly") else emptySet(),
            requestDateMillis = NOW,
            source = AppReferCustomerInfo.Source.CACHE,
        )

    @Test
    fun mergeLocalActiveOverridesCachedInactive() {
        val merged = LocalEntitlements.merge(
            serverInfo(active = false, expires = NOW - DAY), compute(storePurchase("t1", "pro_monthly")),
            "u", NOW, AppReferCustomerInfo.Source.LOCAL_FALLBACK,
        )
        assertTrue(merged.entitlements["premium"]!!.isActive)
        assertEquals(AppReferCustomerInfo.Source.LOCAL_FALLBACK, merged.source)
        assertEquals(setOf("pro_monthly"), merged.activeSubscriptions)
    }

    @Test
    fun activeServerEntitlementKeepsItsExpiryOverDeviceSubscription() {
        val merged = LocalEntitlements.merge(
            serverInfo(active = true, expires = NOW + DAY), compute(storePurchase("t1", "pro_monthly")),
            "u", NOW, AppReferCustomerInfo.Source.LOCAL_FALLBACK,
        )
        assertEquals(NOW + DAY, merged.entitlements["premium"]!!.expirationDateMillis)
        assertEquals("k", merged.entitlements["premium"]!!.subscriptionKey)
    }

    @Test
    fun mergeKeepsCachedEntitlementsPlayDoesNotKnow() {
        val web = AppReferEntitlementInfo(
            "web_pro", true, "price_web", BillingStore.STRIPE, NOW + DAY, true,
            BillingPeriodType.NORMAL, BillingSubscriptionState.ACTIVE, "sub_1",
        )
        val merged = LocalEntitlements.merge(
            serverInfo(active = false, expires = NOW - DAY, extra = mapOf("web_pro" to web)),
            compute(), "u", NOW, AppReferCustomerInfo.Source.LOCAL_FALLBACK,
        )
        assertTrue(merged.entitlements["web_pro"]!!.isActive)
        assertFalse(merged.entitlements["premium"]!!.isActive)
    }

    @Test
    fun productTypeOfQueriedPurchases() {
        val consumables = setOf("coins_100")
        assertEquals(ProductTypes.SUBSCRIPTION, LocalEntitlements.productType(storePurchase("a", "pro"), consumables))
        assertEquals(ProductTypes.CONSUMABLE, LocalEntitlements.productType(storePurchase("b", "coins_100", isSubscription = false), consumables))
        assertEquals(ProductTypes.NON_CONSUMABLE, LocalEntitlements.productType(storePurchase("c", "lifetime", isSubscription = false), consumables))
    }
}
