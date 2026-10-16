package com.apprefer.sdk.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files

internal const val NOW = 1_800_000_000_000L
internal const val DAY = 24 * 3600 * 1000L

internal fun tempDir(): File = Files.createTempDirectory("apprefer-billing-test").toFile().apply { deleteOnExit() }

internal fun newStorage(
    store: BillingKeyValueStore = InMemoryKeyValueStore(),
    dir: File = tempDir(),
    now: () -> Long = { NOW },
) = BillingStorage(store, dir, now)

internal fun storePurchase(
    token: String,
    productId: String,
    isSubscription: Boolean? = true,
    state: StorePurchase.State = StorePurchase.State.PURCHASED,
    acknowledged: Boolean = false,
    autoRenewing: Boolean = true,
    purchaseTime: Long = NOW - 60_000,
    accountId: String? = null,
) = StorePurchase(
    purchaseToken = token,
    productIds = listOf(productId),
    isSubscription = isSubscription,
    state = state,
    isAcknowledged = acknowledged,
    isAutoRenewing = autoRenewing,
    purchaseTimeMillis = purchaseTime,
    orderId = "GPA.$token",
    obfuscatedAccountId = accountId,
)

/** Fake Google Play: owned purchases, acknowledge/consume bookkeeping. */
internal class FakePlay : PlayBillingGateway {
    val purchases = mutableListOf<StorePurchase>()
    var queryCode = BillingResponseCode.OK
    var queryCount = 0
    var onQuery: (() -> Unit)? = null
    val acknowledged = mutableListOf<String>()
    val consumed = mutableListOf<String>()
    var acknowledgeWorks = true
    var consumeWorks = true
    var listener: ((StorePurchasesUpdate) -> Unit)? = null

    override fun setPurchasesUpdatedListener(listener: (StorePurchasesUpdate) -> Unit) {
        this.listener = listener
    }

    override suspend fun queryPurchases(): StorePurchasesResult {
        queryCount++
        onQuery?.invoke()
        return if (queryCode == BillingResponseCode.OK) StorePurchasesResult(queryCode, null, purchases.toList())
        else StorePurchasesResult(queryCode, "unavailable", emptyList())
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        acknowledged += purchaseToken
        if (!acknowledgeWorks) return false
        purchases.replaceAll { if (it.purchaseToken == purchaseToken) it.copy(isAcknowledged = true) else it }
        return true
    }

    override suspend fun consume(purchaseToken: String): Boolean {
        consumed += purchaseToken
        if (!consumeWorks) return false
        purchases.removeAll { it.purchaseToken == purchaseToken }
        return true
    }
}

/**
 * Minimal AppRefer server: purchases grant `premium` (and acknowledge, like the real
 * one), identify merges anonymous purchases, POST customers hands out account tokens.
 */
internal class FakeServer(private val play: FakePlay? = null) {
    var reachable = true
    /** When set, every call answers with this status. */
    var status: Int? = null
    /** When set, only the purchases endpoint answers with this status. */
    var purchasesStatus: Int? = null
    /** When true, only the purchases endpoint is unreachable (network error). */
    var purchasesUnreachable = false
    /** `Retry-After` sent with error statuses. */
    var retryAfter: String? = null
    /** Answer purchases like older servers did when the ledger write failed (200, conversion "failed"). */
    var legacyFailedConversion = false
    val owned = mutableMapOf<String, MutableSet<String>>()
    val requests = mutableListOf<BillingRequest>()

    fun handle(request: BillingRequest): BillingHttpResponse {
        requests += request
        if (!reachable) throw AppReferBillingException.Network("offline")
        status?.let { return BillingHttpResponse(it, """{"error":"nope"}""", retryAfter) }
        if (purchasesUnreachable && request.url.contains("/purchases")) throw AppReferBillingException.Network("purchases down")
        val body = request.body?.let(::JSONObject) ?: JSONObject()
        return when {
            request.url.contains("/purchases") && purchasesStatus != null ->
                BillingHttpResponse(purchasesStatus!!, """{"error":"down"}""", retryAfter)
            request.url.contains("/purchases") -> {
                val user = body.getString("appUserId")
                owned.getOrPut(user) { mutableSetOf() }.add(body.getString("productId"))
                play?.purchases?.replaceAll {
                    if (it.purchaseToken == body.getString("purchaseToken")) it.copy(isAcknowledged = true) else it
                }
                val answer = respond(user, withToken = true)
                if (legacyFailedConversion) {
                    BillingHttpResponse(200, JSONObject(answer.body).put("purchase", JSONObject().put("conversion", "failed")).toString())
                } else {
                    answer
                }
            }
            request.url.contains("/identify") -> {
                val user = body.getString("appUserId")
                owned.remove(body.getString("anonymousId"))?.let { owned.getOrPut(user) { mutableSetOf() }.addAll(it) }
                respond(user, withToken = true)
            }
            request.method == "GET" -> {
                val user = URLDecoder.decode(request.url.substringAfter("appUserId="), "UTF-8")
                respond(user, withToken = false)
            }
            else -> respond(body.getString("appUserId"), withToken = true)
        }
    }

    fun respond(user: String, withToken: Boolean): BillingHttpResponse {
        val ents = JSONObject()
        owned[user]?.firstOrNull()?.let { product ->
            ents.put(
                "premium",
                JSONObject()
                    .put("id", "premium").put("isActive", true).put("productId", product)
                    .put("store", "play_store").put("expiresAtMs", NOW + 30 * DAY).put("willRenew", true)
                    .put("periodType", "normal").put("state", "active").put("subscriptionKey", "key-$product"),
            )
        }
        val obj = JSONObject()
            .put("appUserId", user)
            .put("entitlements", ents)
            .put("activeSubscriptions", org.json.JSONArray(owned[user].orEmpty().toList()))
            .put("requestDateMs", NOW)
        if (withToken) obj.put("accountToken", tokenFor(user))
        return BillingHttpResponse(200, obj.toString())
    }

    fun tokenFor(user: String): String = java.util.UUID.nameUUIDFromBytes(user.toByteArray()).toString()

    fun paths() = requests.map { "${it.method} ${it.url.substringAfter(".test").substringBefore("?")}" }
}

/** A BillingEngine wired to fakes, running on the test scheduler. */
internal class Harness(
    scope: TestScope,
    appUserId: String? = null,
    val storage: BillingStorage = newStorage(),
    options: BillingEngine.Options = BillingEngine.Options(
        productEntitlements = mapOf("pro_monthly" to listOf("premium")),
        consumableProductIds = setOf("coins_100"),
    ),
    val play: FakePlay = FakePlay(),
    val server: FakeServer = FakeServer(play),
    /** Virtual delay before each server answer, so concurrent calls can interleave. */
    transportDelayMs: Long = 0,
    /** The attribution gate: null while the device id must not be sent. */
    appreferId: () -> String? = { "device-1" },
    /** `AppReferInternal.installLinkAllowed()`; defaults to the [appreferId] gate being open. */
    linkAllowed: (() -> Boolean)? = null,
    /** `AppReferInternal.accountEpoch()`: advances on every `AppRefer.reset()`. */
    accountEpoch: () -> Int = { 0 },
) {
    var now = NOW
    val published = mutableListOf<AppReferCustomerInfo>()
    val queue = PendingPurchaseQueue(File(storage.directory, "pending_purchases.json"))
    val rejected = RejectedPurchaseLog(File(storage.directory, "rejected_purchases.json"))
    val engine = BillingEngine(
        api = BillingApiClient(
            baseUrls = listOf("https://trk.test", "https://main.test"),
            apiKey = { "pk_test" },
            transport = {
                if (transportDelayMs > 0) kotlinx.coroutines.delay(transportDelayMs)
                server.handle(it)
            },
            keyWaitAttempts = 0,
        ),
        storage = storage,
        queue = queue,
        rejected = rejected,
        play = play,
        options = options,
        appUserId = appUserId,
        appreferId = appreferId,
        logger = BillingLogger(),
        dispatcher = StandardTestDispatcher(scope.testScheduler),
        scope = scope,
        now = { now },
        onChange = { published += it },
        linkAllowed = linkAllowed ?: { appreferId() != null },
        accountEpoch = accountEpoch,
    )
}
