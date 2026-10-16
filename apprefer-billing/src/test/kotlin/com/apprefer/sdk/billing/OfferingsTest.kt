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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    private fun engine(scope: TestScope, storage: BillingStorage, play: PlayBillingGateway = object : PlayBillingGateway by FakePlay() { override suspend fun queryProductDetails(productIds: List<String>) = listOf(product()) }, transport: BillingTransport): BillingEngine = BillingEngine(
        BillingApiClient(listOf("https://example.test"), { "pk_test" }, transport, keyWaitAttempts = 0),
        storage, PendingPurchaseQueue(File(storage.directory, "pending.json")), RejectedPurchaseLog(File(storage.directory,"rejected.json")),
        play,
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
    @Test fun productFailurePreservesCachedCatalogue() = runTest {
        val storage = newStorage()
        engine(this, storage) { BillingHttpResponse(200, response) }.offerings()
        val play = object : PlayBillingGateway by FakePlay() {
            override suspend fun queryProductDetails(productIds: List<String>): List<ProductDetails> = throw IllegalStateException("Play disconnected")
        }
        val result = engine(this, storage, play) { throw AppReferBillingException.Network("offline") }.offerings(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals("default", result.current?.identifier)
        assertTrue(result.current!!.availablePackages.isEmpty())
    }
    @Test fun joinedCallersReceiveMappedStorageError() = runTest {
        val kv = object : BillingKeyValueStore {
            override fun getString(key: String): String? = if (key == "offerings_bucket") throw BillingStorageException("bucket unreadable") else null
            override fun putString(key: String, value: String?, durable: Boolean) = true
        }
        var requests = 0
        val engine = engine(this, newStorage(kv)) { requests++; BillingHttpResponse(200, response) }
        val a = async { runCatching { engine.offerings() }.exceptionOrNull() }
        val b = async { runCatching { engine.offerings() }.exceptionOrNull() }
        assertTrue(a.await() is AppReferBillingException.Storage)
        assertTrue(b.await() is AppReferBillingException.Storage)
        assertEquals(0, requests)
    }
    @Test fun purchaseBucketFailureIsMappedAndDoesNotOpenStore() = runTest {
        val backing = InMemoryKeyValueStore()
        val kv = object : BillingKeyValueStore {
            override fun getString(key: String): String? = if (key == "offerings_bucket") throw BillingStorageException("bucket unreadable") else backing.getString(key)
            override fun putString(key: String, value: String?, durable: Boolean) = backing.putString(key, value, durable)
        }
        val server = FakeServer()
        val engine = engine(this, newStorage(kv)) { server.handle(it) }
        var launched = false
        val error = runCatching { engine.purchase("year", "subscription", PresentedOfferingContext("default", "annual")) { launched = true; 0 to null } }.exceptionOrNull()
        assertTrue(error is AppReferBillingException.Storage)
        assertFalse(launched)
    }
    @Test fun countryTimeoutOmitsCountryAndStillReturnsOfferings() = runTest {
        val play = object : PlayBillingGateway by FakePlay() {
            override suspend fun storefrontCountry(): String? = kotlinx.coroutines.awaitCancellation()
        }
        var body: JSONObject? = null
        val engine = engine(this, newStorage(), play) { body = JSONObject(it.body!!); BillingHttpResponse(200, response) }
        val result = engine.offerings()
        assertEquals("default", result.current?.identifier)
        assertFalse(body!!.has("country"))
        assertEquals(3000L, testScheduler.currentTime)
    }
    @Test fun fetchCurrentDoesNotJoinCachedResolution() = runTest {
        var block = false
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val play = object : PlayBillingGateway by FakePlay() {
            override suspend fun queryProductDetails(productIds: List<String>): List<ProductDetails> {
                if (block) { block = false; entered.complete(Unit); release.await() }
                return listOf(product())
            }
        }
        var requests = 0
        val engine = engine(this, newStorage(), play) { requests++; BillingHttpResponse(200, response) }
        engine.offerings(); block = true
        val cached = async { engine.offerings() }; entered.await()
        engine.offerings(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(2, requests)
        release.complete(Unit); cached.await()
    }
    @Test fun cachedCallerJoinsFetchCurrent() = runTest {
        var requests = 0
        val engine = engine(this, newStorage()) { requests++; delay(10); BillingHttpResponse(200, response) }
        val fresh = async { engine.offerings(AppReferBilling.FetchPolicy.FETCH_CURRENT) }
        val cached = async { engine.offerings() }
        assertEquals(fresh.await(), cached.await())
        assertEquals(1, requests)
    }
    @Test fun foregroundWithoutOfferingsCacheNeverRequestsOfferings() = runTest {
        val server = FakeServer()
        val engine = engine(this, newStorage()) { server.handle(it) }
        engine.onForeground()
        assertTrue(server.requests.none { it.url.endsWith("/offerings") })
    }
    @Test fun emptyEntitlementMapsUnlockNothing() {
        assertEquals(emptyList<String>(), LocalEntitlements.entitlementIds("p", mapOf("p" to emptyList()), mapOf("p" to listOf("other")), null))
        assertEquals(emptyList<String>(), LocalEntitlements.entitlementIds("p", emptyMap(), mapOf("p" to emptyList()), null))
        assertEquals(listOf("premium"), LocalEntitlements.entitlementIds("p", emptyMap(), emptyMap(), null))
    }
}
