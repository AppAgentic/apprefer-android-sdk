package com.apprefer.sdk.billing

import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfferingsTest {
    private val response = """{"currentOfferingId":"default","offerings":[{"id":"default","metadata":{"nested":[true,null]},"packages":[{"id":"${'$'}rc_annual","packageType":"annual","productId":"year"},{"id":"future","packageType":"future","productId":"missing"},{"id":"no-platform"}]}],"placements":{"onboarding":"default","settings":null},"context":{"experimentId":"exp","variantId":"b"},"requestDateMs":1700000000000,"future":true}"""
    private fun product(): ProductDetails = ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance("""{"productId":"year","type":"inapp","title":"Year","name":"Year","description":"Year","oneTimePurchaseOfferDetails":{"priceAmountMicros":1000000,"priceCurrencyCode":"USD","formattedPrice":"$1"}}""")
    @Test fun aliasesAndUnknownTypes() {
        assertEquals(PackageType.ANNUAL, PackageType.from("\$rc_annual"))
        assertEquals(PackageType.TWO_MONTH, PackageType.from("\$ar_two_month"))
        assertEquals(PackageType.CUSTOM, PackageType.from("future", "future"))
    }
    @Test fun batchFilteringPlacementsAndContext() = runTest {
        var batches = 0
        val offerings = OfferingsJson.resolve(response) { ids -> batches++; assertEquals(listOf("year", "missing"), ids); listOf(product()) }
        assertEquals(1, batches)
        assertEquals(1, offerings.current!!.availablePackages.size)
        assertEquals("default", offerings.currentOffering("unknown")?.identifier)
        assertNull(offerings.currentOffering("settings"))
        assertEquals("onboarding", offerings.currentOffering("onboarding")!!.annual!!.presentedOfferingContext.placementId)
        assertNull(offerings.current!!.annual!!.presentedOfferingContext.placementId)
    }
    private fun engine(scope: TestScope, storage: BillingStorage, transport: BillingTransport): BillingEngine = BillingEngine(
        BillingApiClient(listOf("https://example.test"), { "pk_test" }, transport, keyWaitAttempts = 0),
        storage, PendingPurchaseQueue(File(storage.directory, "pending.json")), RejectedPurchaseLog(File(storage.directory,"rejected.json")),
        object : PlayBillingGateway by FakePlay() { override suspend fun queryProductDetails(productIds: List<String>) = listOf(product()) },
        BillingEngine.Options(), null, { null }, BillingLogger(), StandardTestDispatcher(scope.testScheduler), scope, { NOW },
    )
    @Test fun concurrentCallsShareOneRequestAndFreshCache() = runTest {
        val storage = newStorage(); var requests = 0
        val engine = engine(this, storage) { requests++; delay(10); BillingHttpResponse(200, response) }
        val a = async { engine.offerings() }; val b = async { engine.offerings() }
        assertEquals(a.await(), b.await()); engine.offerings(); assertEquals(1, requests)
    }
    @Test fun diskFallbackReresolvesAndIncludesKnown() = runTest {
        val storage = newStorage(); val first = engine(this, storage) { BillingHttpResponse(200, response) }
        first.offerings()
        var body: JSONObject? = null
        val restarted = engine(this, storage) { body = JSONObject(it.body!!); throw AppReferBillingException.Network("offline") }
        val cached = restarted.offerings(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals("year", cached.current!!.annual!!.storeProduct.productId)
        assertEquals("exp", body!!.getJSONObject("known").getString("experimentId"))
    }
    @Test fun noCacheThrowsSdkError() = runTest {
        try { engine(this, newStorage()) { throw AppReferBillingException.Network("offline") }.offerings(); fail("expected error") } catch (_: AppReferBillingException.Network) {}
    }
    @Test fun contextAndBucketArePostedAndPersistedInQueue() {
        val context = PresentedOfferingContext("default", "\$ar_annual", "onboarding", experimentId="exp", variantId="b")
        val request = BillingApiClient.makeRequest(BillingEndpoint.Purchase("alice", null, "token", "year", "subscription", emptyMap(), presentedOfferingContext=context, bucketId="\$ARAnon:bucket"), "https://example.test", "pk")
        val body = JSONObject(request.body!!)
        assertEquals("onboarding", body.getJSONObject("presentedOfferingContext").getString("placementId"))
        assertEquals("\$ARAnon:bucket", body.getString("bucketId"))
        val file = File(tempDir(), "queue.json")
        val queue = PendingPurchaseQueue(file)
        queue.add(PendingPurchase("token","year","subscription","alice",null,NOW,0,presentedOfferingContext=context,bucketId="bucket"))
        assertEquals(context, PendingPurchaseQueue(file).entries.single().presentedOfferingContext)
    }
    @Test fun installBucketRemainsAfterLoginAndLogout() {
        val storage = newStorage(); val bucket = storage.offeringsBucketId()
        storage.loggedInAppUserId = "alice"; assertEquals(bucket, storage.offeringsBucketId())
        storage.resetAnonymousId(); storage.loggedInAppUserId = null
        assertEquals(bucket, storage.offeringsBucketId())
    }
    @Test fun playBasePlanSelection() = runTest {
        val details = ProductDetails::class.java.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance("""{"productId":"sub","type":"subs","title":"Sub","name":"Sub","description":"Sub","subscriptionOfferDetails":[{"basePlanId":"month","offerIdToken":"month-token","pricingPhases":[{"billingPeriod":"P1M","priceAmountMicros":1000000,"priceCurrencyCode":"USD","formattedPrice":"$1","recurrenceMode":1}]},{"basePlanId":"year","offerIdToken":"year-token","pricingPhases":[{"billingPeriod":"P1Y","priceAmountMicros":10000000,"priceCurrencyCode":"USD","formattedPrice":"$10","recurrenceMode":1}]}]}""")
        val raw = """{"currentOfferingId":"default","offerings":[{"id":"default","packages":[{"id":"${'$'}ar_annual","productId":"sub"},{"id":"explicit","productId":"sub:month"},{"id":"missing","productId":"sub:absent"}]}]}"""
        val offerings = OfferingsJson.resolve(raw) { listOf(details) }
        assertEquals("year-token", offerings.current!!.annual!!.offerToken)
        assertEquals("month-token", offerings.current!!.`package`("explicit")!!.offerToken)
        assertNull(offerings.current!!.`package`("missing"))
    }
    @Test fun emptyEntitlementMapsUnlockNothing() {
        assertEquals(emptyList<String>(), LocalEntitlements.entitlementIds("p", mapOf("p" to emptyList()), mapOf("p" to listOf("other")), null))
        assertEquals(emptyList<String>(), LocalEntitlements.entitlementIds("p", emptyMap(), mapOf("p" to emptyList()), null))
        assertEquals(listOf("premium"), LocalEntitlements.entitlementIds("p", emptyMap(), emptyMap(), null))
    }
}
