package com.apprefer.sdk.billing

import com.android.billingclient.api.ProductDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Google Play prices for Support offer cards: the store's own formatted regular price. */
class SupportOfferPricesTest {
    private fun details(json: String): ProductDetails =
        ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(json)
    private val lifetime = details("""{"productId":"life","type":"inapp","title":"Lifetime (App)","name":"Lifetime","description":"d","oneTimePurchaseOfferDetails":{"priceAmountMicros":49990000,"priceCurrencyCode":"GBP","formattedPrice":"£49.99"}}""")
    private val pro = details("""{"productId":"pro","type":"subs","title":"Pro (App)","name":"Pro","description":"d","subscriptionOfferDetails":[
        {"basePlanId":"annual","offerIdToken":"tok_trial","offerId":"trial","offerTags":[],"pricingPhases":[
            {"priceAmountMicros":0,"priceCurrencyCode":"GBP","formattedPrice":"Free","billingPeriod":"P1W","recurrenceMode":2,"billingCycleCount":1},
            {"priceAmountMicros":29990000,"priceCurrencyCode":"GBP","formattedPrice":"£29.99","billingPeriod":"P1Y","recurrenceMode":1}]},
        {"basePlanId":"monthly","offerIdToken":"tok_month","offerTags":[],"pricingPhases":[
            {"priceAmountMicros":4990000,"priceCurrencyCode":"GBP","formattedPrice":"£4.99","billingPeriod":"P1M","recurrenceMode":1}]}]}""")
    private fun pkg(id: String, d: ProductDetails, token: String? = null, base: String? = null) =
        AppReferPackage(id, PackageType.from(id), d, "main", PresentedOfferingContext("main", id), token, base)

    @Test fun regularPriceNeverTheTrialPhase() {
        assertEquals("£29.99", AppReferBilling.playPrice(pkg("\$ar_annual", pro, "tok_trial", "annual")))
        assertEquals("£4.99", AppReferBilling.playPrice(pkg("\$ar_monthly", pro, "tok_month", "monthly")))
        assertEquals("£49.99", AppReferBilling.playPrice(pkg("\$ar_lifetime", lifetime)))
    }

    @Test fun offeringsMapToPackagesWithStoreNames() {
        val offering = AppReferOffering("main", "Main", emptyMap(), listOf(pkg("\$ar_annual", pro, "tok_trial", "annual"), pkg("\$ar_lifetime", lifetime)))
        val offerings = AppReferOfferings(offering, mapOf("main" to offering), OfferingContext(), emptyMap())
        val prices = AppReferBilling.offerPrices(offerings, setOf("main", "missing"))
        assertEquals(setOf("main"), prices.keys)
        assertEquals("£29.99" to "Pro", prices["main"]!!["\$ar_annual"])
        assertEquals("£49.99" to "Lifetime", prices["main"]!!["\$ar_lifetime"])
        assertNull(prices["main"]!!["\$ar_weekly"])
    }
}
