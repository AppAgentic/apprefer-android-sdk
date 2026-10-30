package com.apprefer.sdk.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.apprefer.sdk.AppReferCallback
import com.apprefer.sdk.AppReferInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Receives entitlement changes (called on the main thread). */
fun interface AppReferBillingListener {
    fun onCustomerInfoUpdated(customerInfo: AppReferCustomerInfo)
}

/**
 * AppRefer Billing: Google Play purchases and entitlements, verified and recorded by
 * AppRefer. A drop-in replacement for RevenueCat's `Purchases`.
 *
 * ```kotlin
 * AppRefer.configure(context, apiKey = "pk_live_...")
 * AppReferBilling.configure(context, entitlementsByProduct = mapOf("pro_yearly" to listOf("premium")))
 *
 * val products = AppReferBilling.products(listOf("pro_yearly"))
 * val result = AppReferBilling.purchase(activity, products[0])
 * if (result.customerInfo.entitlements["premium"]?.isActive == true) unlock()
 * ```
 */
object AppReferBilling {

    enum class FetchPolicy {
        /** The cached answer if it is under 5 minutes old, otherwise the server. */
        CACHED_OR_FETCHED,

        /** Always ask the server. */
        FETCH_CURRENT,
    }

    class PurchaseResult internal constructor(
        val outcome: Outcome,
        /** The Play purchase, when `outcome` is `PURCHASED` (or `PENDING`). */
        val purchase: Purchase?,
        val customerInfo: AppReferCustomerInfo,
    ) {
        enum class Outcome {
            PURCHASED,
            USER_CANCELLED,

            /**
             * Waiting on a slow payment method (cash, bank transfer). It arrives later
             * through `customerInfoStream` / the listener once paid; no access until then.
             */
            PENDING,
        }

        /** RevenueCat-style flag. */
        val userCancelled: Boolean get() = outcome == Outcome.USER_CANCELLED
    }

    // region State

    private val lock = Any()
    @Volatile private var engine: BillingEngine? = null
    @Volatile private var gateway: LivePlayBillingGateway? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var currentEnvironment: (() -> BillingEnvironment?)? = null
    private fun validScope(info: AppReferCustomerInfo) = currentEnvironment?.invoke()?.let { info.environment == it } == true
    private val latest = MutableStateFlow<AppReferCustomerInfo?>(null)

    /** Whether [configure] has been called. */
    @JvmStatic
    val isConfigured: Boolean get() = engine != null

    /** Receives every entitlement change on the main thread. */
    @JvmStatic
    @Volatile
    var listener: AppReferBillingListener? = null

    /** Every entitlement change, starting with the latest known value. */
    @JvmStatic
    val customerInfoStream: Flow<AppReferCustomerInfo> = environmentSnapshots(latest.filterNotNull()) { currentEnvironment?.invoke() }

    // endregion

    // region Configure

    /**
     * Starts AppRefer Billing. Call once at launch (e.g. `Application.onCreate`), after
     * `AppRefer.configure(context, apiKey)`.
     *
     * @param apiKey SDK key. Defaults to the key passed to `AppRefer.configure`.
     * @param appUserId Your user id. Null uses an anonymous `$ARAnon:<UUID>` kept on device.
     * @param entitlementsByProduct productId → entitlement ids, used when AppRefer is
     *   unreachable. Mirror the app's AppRefer billing config. Products not listed fall
     *   back to what the server last said, then [defaultEntitlement].
     * @param defaultEntitlement Entitlement for unmapped products (server default `premium`).
     * @param consumableProductIds One-time products that are consumed (can be bought again)
     *   after AppRefer accepts them. Other one-time products are non-consumable.
     */
    @JvmStatic
    @JvmOverloads
    @OptIn(ExperimentalCoroutinesApi::class)
    fun configure(
        context: Context,
        apiKey: String? = null,
        appUserId: String? = null,
        entitlementsByProduct: Map<String, List<String>> = emptyMap(),
        defaultEntitlement: String? = null,
        consumableProductIds: Set<String> = emptySet(),
        debug: Boolean = false,
        logLevel: Int = 1,
        environment: BillingEnvironment? = null,
    ) {
        val logger = BillingLogger(debug, logLevel)
        val appContext = context.applicationContext
        val created: BillingEngine
        synchronized(lock) {
            if (engine != null) {
                logger.warn("AppReferBilling.configure called more than once; ignoring")
                return
            }
            val storage = BillingStorage.live(appContext)
            val environmentProvider: () -> BillingEnvironment? = {
                val key = apiKey ?: AppReferInternal.apiKey()
                if (key.isNullOrEmpty() || (key.startsWith("pk_test_") && environment == BillingEnvironment.PRODUCTION)) null
                else environment ?: if (key.startsWith("pk_test_")) BillingEnvironment.SANDBOX else BillingEnvironment.PRODUCTION
            }
            currentEnvironment = environmentProvider
            val play = LivePlayBillingGateway(appContext, logger)
            created = BillingEngine(
                api = BillingApiClient.live(appContext, environmentProvider, storage::purchaseEnvironment, storage::recordPurchaseEnvironment) { apiKey ?: AppReferInternal.apiKey() },
                storage = storage,
                queue = PendingPurchaseQueue(java.io.File(storage.directory, "pending_purchases.json")),
                rejected = RejectedPurchaseLog(java.io.File(storage.directory, "rejected_purchases.json")),
                play = play,
                options = BillingEngine.Options(
                    environment = environmentProvider,
                    productEntitlements = entitlementsByProduct,
                    defaultEntitlement = defaultEntitlement,
                    consumableProductIds = consumableProductIds,
                ),
                appUserId = appUserId,
                // Same gate as Support: no device id while collection is off, or after
                // reset() until configure runs again.
                appreferId = { AppReferInternal.linkableDeviceId() },
                linkAllowed = { AppReferInternal.installLinkAllowed() },
                // The device id goes only to the Billing user set after the last reset().
                accountEpoch = { AppReferInternal.accountEpoch() },
                logger = logger,
                dispatcher = Dispatchers.IO.limitedParallelism(1),
                scope = scope,
                onChange = ::publish,
            )
            gateway = play
            engine = created
        }
        Handler(Looper.getMainLooper()).post {
            scope.launch { safely(logger) { created.start() } }
        }
        observeForeground(appContext, created, logger)
        // Support offer cards show Google Play's own prices for the offering's packages.
        AppReferInternal.supportOfferPrices = { ids -> offerPrices(created.offerings(), ids) }
    }

    /** Offering id -> package id -> Google Play's price, product name and billing period for Support offer cards. */
    internal fun offerPrices(offerings: AppReferOfferings, ids: Set<String>): Map<String, Map<String, AppReferInternal.SupportStorePrice>> =
        ids.mapNotNull { id ->
            val offering = offerings.all[id] ?: return@mapNotNull null
            id to offering.availablePackages.mapNotNull { p -> storePrice(p)?.let { p.identifier to it } }.toMap()
        }.toMap()

    /** The regular (recurring) price of the package's base plan or offer, or the one-time price. */
    internal fun playPrice(p: AppReferPackage): String? = storePrice(p)?.price

    /**
     * The one-time price, or the last (recurring) pricing phase of the package's base plan or offer
     * with that phase's own billing period: a free-trial or introductory phase is never shown.
     */
    internal fun storePrice(p: AppReferPackage): AppReferInternal.SupportStorePrice? {
        val details = p.storeProduct
        val name = details.name.takeIf { it.isNotBlank() }
        details.oneTimePurchaseOfferDetails?.formattedPrice?.let { return AppReferInternal.SupportStorePrice(it, name, null, true) }
        val offers = details.subscriptionOfferDetails.orEmpty()
        val offer = offers.firstOrNull { p.offerToken != null && it.offerToken == p.offerToken }
            ?: offers.firstOrNull { p.basePlanId != null && it.basePlanId == p.basePlanId && it.offerId == null }
            ?: offers.firstOrNull()
        val phase = offer?.pricingPhases?.pricingPhaseList?.lastOrNull() ?: return null
        return AppReferInternal.SupportStorePrice(phase.formattedPrice, name, phase.billingPeriod.takeIf { it.isNotBlank() }, false)
    }

    private fun publish(info: AppReferCustomerInfo) {
        if (!validScope(info)) return
        latest.value = info
        AppReferInternal.billingChanged()
        mainHandler.post {
            if (!validScope(info)) return@post
            try {
                listener?.onCustomerInfoUpdated(info)
            } catch (t: Throwable) {
                android.util.Log.e("AppRefer", "AppReferBillingListener threw", t)
            }
        }
    }

    /** Refreshes entitlements, resends queued purchases and picks up completed pending ones on foreground. */
    private fun observeForeground(context: Context, engine: BillingEngine, logger: BillingLogger) {
        val app = context as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            private var wasBackground = false

            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1 && wasBackground) {
                    wasBackground = false
                    scope.launch { safely(logger) { engine.onForeground() } }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                started = maxOf(0, started - 1)
                if (started == 0 && !activity.isChangingConfigurations) wasBackground = true
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun requireEngine(): BillingEngine = engine ?: throw AppReferBillingException.NotConfigured()

    // endregion

    // region Identity

    /** The current app user id (`$ARAnon:<UUID>` while anonymous); empty before [configure]. */
    @JvmStatic
    val appUserId: String get() = engine?.currentAppUserId ?: ""

    @JvmStatic
    val isAnonymous: Boolean get() = engine?.isAnonymous ?: true

    /** Switches to a signed-in user. Purchases made while anonymous move to them. */
    @Throws(AppReferBillingException::class)
    suspend fun logIn(appUserId: String): AppReferCustomerInfo = requireEngine().logIn(appUserId)

    /** Signs out and starts a fresh anonymous id. */
    @Throws(AppReferBillingException::class)
    suspend fun logOut(): AppReferCustomerInfo = requireEngine().logOut()

    /**
     * Customer attributes, sent with the next AppRefer call. Supported keys: `$email`,
     * `$displayName`, `$phoneNumber`, `$gpsAdId`, `$fbAnonId`, `$deviceVersion`, `$ip`.
     * The device keys are dropped, not sent, while collection is off or after
     * `AppRefer.reset()` until configure runs again.
     */
    @JvmStatic
    fun setAttributes(attributes: Map<String, String>) {
        engine?.setAttributes(attributes)
    }

    @JvmStatic fun setEmail(email: String) = setAttributes(mapOf("\$email" to email))
    @JvmStatic fun setDisplayName(name: String) = setAttributes(mapOf("\$displayName" to name))
    @JvmStatic fun setPhoneNumber(phone: String) = setAttributes(mapOf("\$phoneNumber" to phone))

    // endregion

    // region Entitlements

    /**
     * The customer's entitlements. Never throws: if AppRefer can't be reached, the answer
     * comes from Google Play's active purchases merged with the cache.
     */
    suspend fun customerInfo(fetchPolicy: FetchPolicy = FetchPolicy.CACHED_OR_FETCHED): AppReferCustomerInfo {
        val engine = engine ?: return AppReferCustomerInfo(
            appUserId = "",
            entitlements = EntitlementInfos(emptyMap()),
            activeSubscriptions = emptySet(),
            requestDateMillis = System.currentTimeMillis(),
            source = AppReferCustomerInfo.Source.LOCAL_FALLBACK,
        ).also { android.util.Log.e("AppRefer", "AppReferBilling.customerInfo called before configure()") }
        return try {
            engine.customerInfo(fetchPolicy)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            engine.localFallback()
        }
    }

    // endregion

    suspend fun offerings(fetchPolicy: FetchPolicy = FetchPolicy.CACHED_OR_FETCHED): AppReferOfferings = requireEngine().offerings(fetchPolicy)

    suspend fun purchase(activity: Activity, pkg: AppReferPackage): PurchaseResult =
        purchase(activity, pkg.storeProduct, offerToken = pkg.offerToken, presentedOfferingContext = pkg.presentedOfferingContext)

    // region Products and purchases

    /** Play products (subscriptions and one-time products) in the order requested. */
    @Throws(AppReferBillingException::class)
    suspend fun products(productIds: List<String>): List<ProductDetails> {
        requireEngine()
        val play = gateway ?: throw AppReferBillingException.NotConfigured()
        return play.queryProductDetails(productIds)
    }

    /**
     * Buys a product. Tags the purchase with the customer's AppRefer account token
     * (`setObfuscatedAccountId`), sends it to AppRefer (which verifies and acknowledges
     * it), and consumes consumables once AppRefer has accepted them. Who started it is saved
     * first, so a purchase that completes later (pending payment, restart, user switch) is
     * recorded for them; if that can't be saved (or the identity can't be read), throws
     * [AppReferBillingException.Storage] without opening Google Play.
     *
     * @param offerToken Subscription offer to buy. Null picks a free-trial offer when the
     *   user is eligible for one, otherwise the base plan (see [defaultOfferToken]).
     * @param oldProductId For upgrades/downgrades: the subscription being replaced.
     * @param replacementMode
     *   `BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode`.
     */
    @Throws(AppReferBillingException::class)
    suspend fun purchase(
        activity: Activity,
        product: ProductDetails,
        offerToken: String? = null,
        oldProductId: String? = null,
        replacementMode: Int = SubscriptionProductReplacementParams.ReplacementMode.WITH_TIME_PRORATION,
        presentedOfferingContext: PresentedOfferingContext? = null,
    ): PurchaseResult {
        val engine = requireEngine()
        val play = gateway ?: throw AppReferBillingException.NotConfigured()
        val isSubscription = product.productType == BillingClient.ProductType.SUBS
        val productType = when {
            isSubscription -> ProductTypes.SUBSCRIPTION
            product.productId in engine.consumableProductIds -> ProductTypes.CONSUMABLE
            else -> ProductTypes.NON_CONSUMABLE
        }

        // Resolved before the flow starts: the purchase being replaced.
        val oldPurchaseToken = oldProductId?.let { old ->
            play.queryPurchases().purchases.firstOrNull { it.isSubscription == true && old in it.productIds }?.purchaseToken
        }

        val outcome = engine.purchase(product.productId, productType, presentedOfferingContext) launch@{ accountToken ->
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
            if (isSubscription) {
                val token = offerToken ?: defaultOfferToken(product)
                    ?: return@launch BillingClient.BillingResponseCode.ITEM_UNAVAILABLE to "No subscription offer available"
                productParams.setOfferToken(token)
                if (oldProductId != null && oldPurchaseToken != null) {
                    productParams.setSubscriptionProductReplacementParams(
                        SubscriptionProductReplacementParams.newBuilder()
                            .setOldProductId(oldProductId)
                            .setReplacementMode(replacementMode)
                            .build(),
                    )
                }
            }
            val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams.build()))
            if (accountToken != null) flow.setObfuscatedAccountId(accountToken)
            if (oldPurchaseToken != null) {
                flow.setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder().setOldPurchaseToken(oldPurchaseToken).build(),
                )
            }
            val result = play.launchBillingFlow(activity, flow.build())
            result.responseCode to result.debugMessage
        }
        return PurchaseResult(outcome.outcome, outcome.purchase?.raw, outcome.customerInfo)
    }

    /**
     * `queryPurchasesAsync`, then sends every active Play purchase to AppRefer, plus the
     * [rejectedPurchases] Play no longer lists. Throws if Google Play can't be reached.
     */
    @Throws(AppReferBillingException::class)
    suspend fun restorePurchases(): AppReferCustomerInfo = requireEngine().restore()

    /**
     * Purchases AppRefer refused for good (400/413/422), that had no usable store data, or
     * that could not be delivered within 50 attempts / 90 days. Each was saved here before
     * it was acknowledged (consumables are never consumed), for support, and
     * [restorePurchases] sends them again. Empty before [configure].
     */
    suspend fun rejectedPurchases(): List<RejectedPurchase> = engine?.rejectedPurchases() ?: emptyList()

    /**
     * The offer [purchase] buys when no `offerToken` is given: an offer with a free-trial
     * phase if the user is eligible for one, otherwise the base plan, otherwise the first
     * offer. Products with several base plans should pass the `offerToken` they show.
     */
    @JvmStatic
    fun defaultOfferToken(product: ProductDetails): String? {
        val offers = product.subscriptionOfferDetails.orEmpty()
        return (
            offers.firstOrNull { offer -> offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
                ?: offers.firstOrNull { it.offerId == null }
                ?: offers.firstOrNull()
            )?.offerToken
    }

    // endregion

    // region Java callbacks

    @JvmStatic
    @JvmOverloads
    fun customerInfo(fetchPolicy: FetchPolicy = FetchPolicy.CACHED_OR_FETCHED, callback: AppReferCallback<AppReferCustomerInfo>) {
        deliver(callback) { customerInfo(fetchPolicy) }
    }

    @JvmStatic
    fun products(productIds: List<String>, callback: AppReferCallback<List<ProductDetails>>) {
        deliver(callback) { products(productIds) }
    }

    @JvmStatic
    @JvmOverloads
    fun purchase(
        activity: Activity,
        product: ProductDetails,
        offerToken: String? = null,
        callback: AppReferCallback<PurchaseResult>,
    ) {
        deliver(callback) { purchase(activity, product, offerToken) }
    }

    @JvmStatic
    fun restorePurchases(callback: AppReferCallback<AppReferCustomerInfo>) {
        deliver(callback) { restorePurchases() }
    }

    @JvmStatic
    fun rejectedPurchases(callback: AppReferCallback<List<RejectedPurchase>>) {
        deliver(callback) { rejectedPurchases() }
    }

    @JvmStatic
    fun logIn(appUserId: String, callback: AppReferCallback<AppReferCustomerInfo>) {
        deliver(callback) { logIn(appUserId) }
    }

    @JvmStatic
    fun logOut(callback: AppReferCallback<AppReferCustomerInfo>) {
        deliver(callback) { logOut() }
    }

    /** Runs [block] and calls back on the main thread. */
    private fun <T> deliver(callback: AppReferCallback<T>, block: suspend () -> T) {
        scope.launch {
            val result = runCatching { block() }
            mainHandler.post {
                try {
                    result.fold(callback::onResult, callback::onError)
                } catch (t: Throwable) {
                    android.util.Log.e("AppRefer", "AppReferBilling callback threw", t)
                }
            }
        }
    }

    // endregion

    private suspend fun safely(logger: BillingLogger, block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            logger.error("Billing: background refresh failed", t)
        }
    }
}
