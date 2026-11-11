package com.apprefer.sdk.billing

import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TrialOfferSelectionTest {
    private fun phase(price: Long, period: String, cycles: Int = 1, recurrence: Int = 2) =
        """{"billingPeriod":"$period","priceAmountMicros":$price,"priceCurrencyCode":"USD","formattedPrice":"price","billingCycleCount":$cycles,"recurrenceMode":$recurrence}"""
    private fun offer(id: String?, phases: String, base: String = "annual") =
        """{"basePlanId":"$base",${if (id == null) "" else "\"offerId\":\"$id\","}"offerIdToken":"${id ?: base}-token","pricingPhases":[$phases]}"""
    private val regular = phase(40_000_000, "P1Y", 0, 1)
    private fun details(vararg offers: String): ProductDetails = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
        .apply { isAccessible = true }.newInstance("""{"productId":"pro","type":"subs","title":"Pro","name":"Pro","description":"Pro","subscriptionOfferDetails":[${offers.joinToString(",")}]}""")
    private suspend fun resolve(product: ProductDetails, id: String = "pro:annual"): AppReferPackage? {
        val raw = """{"currentOfferingId":"main","offerings":[{"id":"main","packages":[{"id":"${'$'}ar_annual","productId":"$id"}]}]}"""
        return OfferingsJson.resolve(raw) { listOf(product) }.current!!.annual
    }
    @Test fun longestTrialWinsRegardlessOfBaseFirstOrdering() = runTest {
        val product = details(offer(null, regular), offer("three-day", phase(0,"P3D")+","+regular), offer("week",phase(0,"P1W")+","+regular))
        assertEquals("week-token", resolve(product)!!.offerToken)
        assertEquals("week", resolve(product)!!.offerId)
        assertEquals("week", resolve(product, "pro")!!.offerId)
    }
    @Test fun cheapestIntroAndBaseFallback() = runTest {
        val product = details(offer(null,regular),offer("intro10",phase(10_000_000,"P1Y")+","+regular),offer("intro5",phase(5_000_000,"P1Y")+","+regular))
        assertEquals("intro5", resolve(product)!!.offerId)
        assertEquals("annual-token", resolve(details(offer(null,regular)))!!.offerToken)
        assertNull(resolve(details(offer(null,regular)))!!.offerId)
    }
    @Test fun pinsDoNotSilentlyFallBackToImmediateCharge() = runTest {
        val product=details(offer(null,regular),offer("trial",phase(0,"P3D")+","+regular))
        assertEquals("trial",resolve(product,"pro:annual:trial")!!.offerId)
        assertNull(resolve(product,"pro:annual:missing"))
        assertNull(resolve(product,"pro:annual:"))
    }
    @Test fun otherBasePlansNeverSupplyTrialToken() = runTest {
        val product=details(offer(null,regular),offer("trial",phase(0,"P1M")+","+regular,"monthly"))
        assertEquals("annual-token",resolve(product)!!.offerToken)
    }
}
