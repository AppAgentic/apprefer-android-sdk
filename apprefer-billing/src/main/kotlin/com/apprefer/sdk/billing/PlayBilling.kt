package com.apprefer.sdk.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * The parts of a Play `Purchase` billing needs, so the logic is testable without Google
 * Play (whose `Purchase` needs a signed JSON blob).
 */
internal data class StorePurchase(
    /** Secret: never log. */
    val purchaseToken: String,
    val productIds: List<String>,
    /** True/false when queried by type (SUBS/INAPP); null when delivered by `onPurchasesUpdated`. */
    val isSubscription: Boolean?,
    val state: State,
    val isAcknowledged: Boolean,
    val isAutoRenewing: Boolean,
    val purchaseTimeMillis: Long,
    val orderId: String?,
    /**
     * `obfuscatedAccountId` set at purchase time: the AppRefer account token of the app
     * user who bought it, or null (bought outside the SDK or while AppRefer was down).
     */
    val obfuscatedAccountId: String? = null,
    /** The Play object, when there is one; returned to the app in `PurchaseResult`. */
    val raw: Purchase? = null,
) {
    enum class State { PURCHASED, PENDING, UNSPECIFIED }

    val productId: String get() = productIds.firstOrNull() ?: ""

    /**
     * A subscription renewal: Google gives renewal orders a `..N` suffix
     * (`GPA.1234-5678-9012-34567..0`). Renewals never match a saved purchase intent.
     */
    val isRenewal: Boolean get() = orderId?.let(RENEWAL_ORDER_ID::containsMatchIn) == true

    override fun toString() =
        "StorePurchase(orderId=$orderId, products=$productIds, state=$state, acknowledged=$isAcknowledged)"

    companion object {
        private val RENEWAL_ORDER_ID = Regex("""\.\.\d+$""")

        fun from(p: Purchase, isSubscription: Boolean?) = StorePurchase(
            purchaseToken = p.purchaseToken,
            productIds = p.products,
            isSubscription = isSubscription,
            state = when (p.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> State.PURCHASED
                Purchase.PurchaseState.PENDING -> State.PENDING
                else -> State.UNSPECIFIED
            },
            isAcknowledged = p.isAcknowledged,
            isAutoRenewing = p.isAutoRenewing,
            purchaseTimeMillis = p.purchaseTime,
            orderId = p.orderId,
            obfuscatedAccountId = p.accountIdentifiers?.obfuscatedAccountId,
            raw = p,
        )
    }
}

/** Result of `queryPurchasesAsync` for both product types. */
internal data class StorePurchasesResult(
    val responseCode: Int,
    val debugMessage: String?,
    val purchases: List<StorePurchase>,
) {
    val isOk: Boolean get() = responseCode == BillingClient.BillingResponseCode.OK
}

/** One `onPurchasesUpdated` callback. */
internal data class StorePurchasesUpdate(
    val responseCode: Int,
    val debugMessage: String?,
    val purchases: List<StorePurchase>,
)

/** Google Play Billing behind an interface so the engine can be tested with fakes. */
internal interface PlayBillingGateway {
    /** Receives every `onPurchasesUpdated` (purchase flows, pending purchases completing). */
    fun setPurchasesUpdatedListener(listener: (StorePurchasesUpdate) -> Unit)

    /** Active subscriptions and owned one-time products (`queryPurchasesAsync`, SUBS + INAPP). */
    suspend fun queryPurchases(): StorePurchasesResult

    /** True when acknowledged (or already acknowledged). */
    suspend fun acknowledge(purchaseToken: String): Boolean

    /** True when consumed (or no longer owned). */
    suspend fun consume(purchaseToken: String): Boolean
}

/**
 * Live gateway around `BillingClient`: pending purchases enabled, automatic service
 * reconnection, plus a retrying `startConnection` for the first connection.
 */
internal class LivePlayBillingGateway(context: Context, private val logger: BillingLogger) : PlayBillingGateway {

    @Volatile private var listener: ((StorePurchasesUpdate) -> Unit)? = null
    private val connectMutex = Mutex()

    val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            val update = StorePurchasesUpdate(
                result.responseCode,
                result.debugMessage,
                purchases.orEmpty().map { StorePurchase.from(it, isSubscription = null) },
            )
            try {
                listener?.invoke(update)
            } catch (t: Throwable) {
                logger.error("Billing: purchases listener failed", t)
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    override fun setPurchasesUpdatedListener(listener: (StorePurchasesUpdate) -> Unit) {
        this.listener = listener
    }

    /**
     * Connects if needed. Auto reconnection covers later disconnects; this covers the first
     * connection and a client that gave up. Returns the setup result.
     */
    suspend fun ensureConnected(): BillingResult = connectMutex.withLock {
        if (client.isReady) return@withLock ok()
        var last = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED).build()
        for (attempt in 0 until CONNECT_ATTEMPTS) {
            if (attempt > 0) delay(500L shl attempt)
            last = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    client.startConnection(object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: BillingResult) {
                            if (cont.isActive) cont.resume(result)
                        }

                        override fun onBillingServiceDisconnected() {
                            logger.warn("Billing: Play Billing service disconnected")
                        }
                    })
                }
            } ?: BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE).build()
            if (last.responseCode == BillingClient.BillingResponseCode.OK) return@withLock last
            // No Play Store / unsupported device: retrying won't help.
            if (last.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ||
                last.responseCode == BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED
            ) {
                break
            }
        }
        logger.warn("Billing: Play Billing connection failed (${last.responseCode} ${last.debugMessage})")
        last
    }

    override suspend fun queryPurchases(): StorePurchasesResult {
        val setup = ensureConnected()
        if (setup.responseCode != BillingClient.BillingResponseCode.OK) {
            return StorePurchasesResult(setup.responseCode, setup.debugMessage, emptyList())
        }
        val all = ArrayList<StorePurchase>()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                return StorePurchasesResult(result.billingResult.responseCode, result.billingResult.debugMessage, emptyList())
            }
            result.purchasesList.mapTo(all) { StorePurchase.from(it, isSubscription = type == BillingClient.ProductType.SUBS) }
        }
        return StorePurchasesResult(BillingClient.BillingResponseCode.OK, null, all)
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (ensureConnected().responseCode != BillingClient.BillingResponseCode.OK) return false
        val result = client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build(),
        )
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override suspend fun consume(purchaseToken: String): Boolean {
        if (ensureConnected().responseCode != BillingClient.BillingResponseCode.OK) return false
        val result = client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build())
        return result.billingResult.responseCode == BillingClient.BillingResponseCode.OK ||
            result.billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_NOT_OWNED
    }

    /** Product details for both types, in the order requested. Unknown ids are left out. */
    suspend fun queryProductDetails(productIds: List<String>): List<ProductDetails> {
        if (productIds.isEmpty()) return emptyList()
        val setup = ensureConnected()
        if (setup.responseCode != BillingClient.BillingResponseCode.OK) {
            throw AppReferBillingException.Store(setup.responseCode, setup.debugMessage)
        }
        val found = LinkedHashMap<String, ProductDetails>()
        var lastError: BillingResult? = null
        // A query may only contain one product type.
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    productIds.distinct().map {
                        QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
                    },
                )
                .build()
            val result = client.queryProductDetails(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                lastError = result.billingResult
                continue
            }
            result.productDetailsList.orEmpty().forEach { found.putIfAbsent(it.productId, it) }
        }
        if (found.isEmpty() && lastError != null) {
            throw AppReferBillingException.Store(lastError.responseCode, lastError.debugMessage)
        }
        return productIds.distinct().mapNotNull { found[it] }
    }

    /** Must be called on the main thread. */
    suspend fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult {
        val setup = ensureConnected()
        if (setup.responseCode != BillingClient.BillingResponseCode.OK) return setup
        return withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
    }

    private fun ok() = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

/** Device-side entitlement logic: the outage fallback. */
internal object LocalEntitlements {

    data class Local(
        val entitlements: Map<String, AppReferEntitlementInfo>,
        val activeSubscriptions: Set<String>,
    )

    /**
     * Entitlement ids for a product: the configured map, then what the server has told
     * us before, then the default (the server's own default is `premium`).
     */
    fun entitlementIds(
        productId: String,
        configured: Map<String, List<String>>,
        learned: Map<String, List<String>>,
        defaultEntitlement: String?,
    ): List<String> {
        configured[productId]?.takeIf { it.isNotEmpty() }?.let { return it }
        learned[productId]?.takeIf { it.isNotEmpty() }?.let { return it }
        return listOf(defaultEntitlement ?: "premium")
    }

    /** [ProductTypes] value of a purchase queried by type. */
    fun productType(purchase: StorePurchase, consumables: Set<String>): String = when {
        purchase.isSubscription == true -> ProductTypes.SUBSCRIPTION
        purchase.productId in consumables -> ProductTypes.CONSUMABLE
        else -> ProductTypes.NON_CONSUMABLE
    }

    /**
     * Entitlements granted by Google Play's own purchases: purchased (not pending), not
     * consumable. `queryPurchasesAsync` only returns subscriptions that are still active,
     * so their expiry is unknown here.
     */
    fun compute(
        purchases: List<StorePurchase>,
        configured: Map<String, List<String>>,
        learned: Map<String, List<String>>,
        defaultEntitlement: String?,
        consumables: Set<String>,
    ): Local {
        val entitlements = LinkedHashMap<String, AppReferEntitlementInfo>()
        val activeSubscriptions = LinkedHashSet<String>()

        for (p in purchases) {
            if (p.state != StorePurchase.State.PURCHASED) continue
            val type = productType(p, consumables)
            if (type == ProductTypes.CONSUMABLE) continue
            val isSubscription = type == ProductTypes.SUBSCRIPTION

            for (productId in p.productIds) {
                if (isSubscription) activeSubscriptions.add(productId)
                for (id in entitlementIds(productId, configured, learned, defaultEntitlement)) {
                    val info = AppReferEntitlementInfo(
                        identifier = id,
                        isActive = true,
                        productIdentifier = productId,
                        store = BillingStore.PLAY_STORE,
                        expirationDateMillis = null,
                        willRenew = isSubscription && p.isAutoRenewing,
                        periodType = if (isSubscription) BillingPeriodType.UNKNOWN else BillingPeriodType.NORMAL,
                        state = BillingSubscriptionState.ACTIVE,
                        // Same as the server's key for a subscription that was never upgraded.
                        subscriptionKey = sha256Hex(p.purchaseToken),
                    )
                    val current = entitlements[id]
                    // A lifetime purchase beats a subscription whose expiry is unknown.
                    if (current != null && (current.productIdentifier !in activeSubscriptions || isSubscription)) continue
                    entitlements[id] = info
                }
            }
        }
        return Local(entitlements, activeSubscriptions)
    }

    /**
     * Merges device entitlements into a server snapshot (fresh or cached). Per entitlement
     * the server rule applies: active beats inactive, later expiry wins. A Play subscription
     * (expiry unknown on device) never replaces an active server entitlement, which carries
     * the real expiry.
     */
    fun merge(
        server: AppReferCustomerInfo?,
        local: Local,
        appUserId: String,
        nowMillis: Long,
        source: AppReferCustomerInfo.Source,
    ): AppReferCustomerInfo {
        val all = LinkedHashMap(server?.entitlements?.all ?: emptyMap())
        for ((id, info) in local.entitlements) {
            val current = all[id]
            if (current != null) {
                val unknownExpiry = info.productIdentifier in local.activeSubscriptions
                if (current.isActive && unknownExpiry) continue
                if (!info.beats(current)) continue
            }
            all[id] = info
        }
        return AppReferCustomerInfo(
            appUserId = appUserId,
            entitlements = EntitlementInfos(all),
            activeSubscriptions = (server?.activeSubscriptions ?: emptySet()) + local.activeSubscriptions,
            requestDateMillis = nowMillis,
            source = source,
        )
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
