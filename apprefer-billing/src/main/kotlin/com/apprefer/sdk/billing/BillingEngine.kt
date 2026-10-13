package com.apprefer.sdk.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Core of AppReferBilling: identity, server sync, purchase processing, retry queue and
 * the outage fallback. The Play purchase UI lives in `AppReferBilling`; everything here
 * goes through [PlayBillingGateway] and [BillingApiClient] so it can be tested.
 *
 * All state is touched on [dispatcher], which must run one coroutine at a time
 * (`limitedParallelism(1)`), like the iOS actor.
 */
internal class BillingEngine(
    private val api: BillingApiClient,
    private val storage: BillingStorage,
    private val queue: PendingPurchaseQueue,
    private val play: PlayBillingGateway,
    private val options: Options,
    appUserId: String?,
    private val appreferId: () -> String?,
    private val logger: BillingLogger,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val onChange: (AppReferCustomerInfo) -> Unit = {},
) {
    data class Options(
        val productEntitlements: Map<String, List<String>> = emptyMap(),
        val defaultEntitlement: String? = null,
        /** One-time products that are consumed after AppRefer accepts them. */
        val consumableProductIds: Set<String> = emptySet(),
        /** How long a server answer counts as fresh for `CACHED_OR_FETCHED`. */
        val cacheTtlMillis: Long = 5 * 60 * 1000L,
        /**
         * Play refunds purchases that aren't acknowledged within 3 days. AppRefer
         * acknowledges on receipt; if it hasn't accepted a purchase this long after it was
         * made, the SDK acknowledges (or consumes) it itself so the user isn't refunded.
         */
        val selfAcknowledgeAfterMillis: Long = 48 * 3600 * 1000L,
    )

    /** What `purchase` returns; mapped to `AppReferBilling.PurchaseResult`. */
    data class PurchaseOutcome(
        val outcome: AppReferBilling.PurchaseResult.Outcome,
        val purchase: StorePurchase?,
        val customerInfo: AppReferCustomerInfo,
    )

    private var lastPublished: AppReferCustomerInfo? = null
    private var lastServerFetch: Pair<String, Long>? = null

    /** Users whose appreferId/attributes were registered (POST customers) this launch. */
    private val registeredUsers = HashSet<String>()

    /** Users registered before the attribution device id existed; registered again once it does. */
    private val registeredWithoutDeviceId = HashSet<String>()

    /** Purchase tokens AppRefer accepted this launch (Play's cache can lag on `isAcknowledged`). */
    private val acceptedTokens = HashSet<String>()
    private var isDraining = false
    private var purchaseWaiter: Pair<String, CompletableDeferred<StorePurchasesUpdate>>? = null

    init {
        // configure(appUserId) behaves like logIn: purchases made while anonymous on this
        // device move to the user (sent to /identify when reachable).
        if (!appUserId.isNullOrEmpty() && appUserId != storage.loggedInAppUserId) {
            if (storage.loggedInAppUserId == null && storage.hasAnonymousId && !BillingStorage.isAnonymous(appUserId)) {
                storage.pendingIdentify = storage.anonymousId() to appUserId
            }
            storage.loggedInAppUserId = if (BillingStorage.isAnonymous(appUserId)) null else appUserId
        }
        play.setPurchasesUpdatedListener(::onPurchasesUpdated)
    }

    // region Lifecycle

    /**
     * Sends Play purchases AppRefer hasn't accepted yet (e.g. pending purchases that
     * completed while the app was closed), flushes the retry queue and refreshes entitlements.
     */
    suspend fun start() = withContext(dispatcher) {
        syncPurchases()
        customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
    }

    /** App came to the foreground. */
    suspend fun onForeground() = withContext(dispatcher) {
        customerInfo(AppReferBilling.FetchPolicy.CACHED_OR_FETCHED)
        syncPurchases()
        drainQueue()
    }

    val currentAppUserId: String get() = storage.currentAppUserId
    val consumableProductIds: Set<String> get() = options.consumableProductIds
    val isAnonymous: Boolean get() = storage.loggedInAppUserId == null

    // endregion

    // region CustomerInfo

    suspend fun customerInfo(policy: AppReferBilling.FetchPolicy): AppReferCustomerInfo = withContext(dispatcher) {
        val user = currentAppUserId
        val fetched = lastServerFetch
        if (policy == AppReferBilling.FetchPolicy.CACHED_OR_FETCHED &&
            fetched != null && fetched.first == user && now() - fetched.second < options.cacheTtlMillis
        ) {
            val cached = storage.cachedCustomerInfo()
            if (cached != null && cached.appUserId == user) {
                return@withContext publish(withPendingLocal(cached.reevaluated(now())))
            }
        }
        try {
            fetchFromServer()
        } catch (e: AppReferBillingException) {
            logger.warn("Billing: customer refresh failed (${e.message}); using Google Play fallback")
            localFallback()
        }
    }

    private suspend fun fetchFromServer(): AppReferCustomerInfo {
        flushPendingIdentify()
        val user = currentAppUserId
        val attributes = storage.pendingAttributes
        val deviceId = appreferId()
        val needsRegister = user !in registeredUsers || attributes.isNotEmpty() ||
            storage.accountToken(user) == null || (user in registeredWithoutDeviceId && deviceId != null)
        val response = api.send(
            if (needsRegister) BillingEndpoint.CreateCustomer(user, deviceId, attributes)
            else BillingEndpoint.GetCustomer(user),
        )
        if (needsRegister) {
            registeredUsers.add(user)
            if (deviceId == null) registeredWithoutDeviceId.add(user) else registeredWithoutDeviceId.remove(user)
            storage.clearSentAttributes(attributes)
        }
        val info = accept(response, user)
        drainQueue()
        return info
    }

    /** Stores a server answer and returns what callers should see. */
    private suspend fun accept(response: BillingCustomerResponse, user: String): AppReferCustomerInfo {
        response.accountToken?.let { storage.setAccountToken(it, response.info.appUserId) }
        storage.learnEntitlements(response.info)
        if (response.info.appUserId != user || user != currentAppUserId) {
            // Answer for a user who is no longer current (logIn/logOut raced it).
            return response.info
        }
        storage.setCachedCustomerInfo(response.info)
        lastServerFetch = user to now()
        return publish(withPendingLocal(response.info))
    }

    /**
     * While a purchase for this user is still queued, the server can't know it yet, so
     * device entitlements are merged in.
     */
    private suspend fun withPendingLocal(info: AppReferCustomerInfo): AppReferCustomerInfo {
        if (!queue.contains(info.appUserId)) return info
        val purchases = play.queryPurchases().purchases
        return mergeLocal(info, purchases, info.appUserId, info.source)
    }

    /**
     * Outage path: Google Play's active purchases merged with the cached server answer for
     * the same user. A paying user is never locked out because AppRefer is down.
     */
    suspend fun localFallback(): AppReferCustomerInfo = withContext(dispatcher) {
        val user = currentAppUserId
        val purchases = play.queryPurchases().purchases
        val cached = storage.cachedCustomerInfo()?.takeIf { it.appUserId == user }?.reevaluated(now())
        publish(mergeLocal(cached, purchases, user, AppReferCustomerInfo.Source.LOCAL_FALLBACK))
    }

    private fun mergeLocal(
        server: AppReferCustomerInfo?,
        purchases: List<StorePurchase>,
        user: String,
        source: AppReferCustomerInfo.Source,
    ): AppReferCustomerInfo {
        val local = LocalEntitlements.compute(
            purchases = purchases,
            configured = options.productEntitlements,
            learned = storage.learnedEntitlements,
            defaultEntitlement = options.defaultEntitlement,
            consumables = options.consumableProductIds,
        )
        return LocalEntitlements.merge(server, local, user, now(), source)
    }

    private fun publish(info: AppReferCustomerInfo): AppReferCustomerInfo {
        if (lastPublished?.hasSameContent(info) != true) onChange(info)
        lastPublished = info
        return info
    }

    // endregion

    // region Purchases

    /**
     * Runs one purchase: [launch] starts the Play purchase flow with the customer's account
     * token (null if AppRefer is unreachable: the purchase still goes ahead). The result
     * arrives through `onPurchasesUpdated`.
     */
    suspend fun purchase(
        productId: String,
        productType: String,
        launch: suspend (accountToken: String?) -> Pair<Int, String?>,
    ): PurchaseOutcome = withContext(dispatcher) {
        if (purchaseWaiter != null) throw AppReferBillingException.PurchaseInProgress()
        val token = accountToken()
        val waiter = CompletableDeferred<StorePurchasesUpdate>()
        purchaseWaiter = productId to waiter
        try {
            val (code, message) = launch(token)
            if (code != BillingResponseCode.OK) return@withContext launchFailed(code, message)
            val update = waiter.await()
            when (update.responseCode) {
                BillingResponseCode.OK -> {
                    val purchase = update.purchases.firstOrNull { productId in it.productIds }
                    when (purchase?.state) {
                        StorePurchase.State.PURCHASED -> {
                            val typed = purchase.copy(isSubscription = productType == ProductTypes.SUBSCRIPTION)
                            PurchaseOutcome(AppReferBilling.PurchaseResult.Outcome.PURCHASED, typed, process(typed, productType))
                        }
                        // Pending (slow payment method): it arrives later through
                        // onPurchasesUpdated or the next foreground sync.
                        else -> PurchaseOutcome(
                            AppReferBilling.PurchaseResult.Outcome.PENDING,
                            purchase,
                            customerInfo(AppReferBilling.FetchPolicy.CACHED_OR_FETCHED),
                        )
                    }
                }
                else -> launchFailed(update.responseCode, update.debugMessage)
            }
        } finally {
            if (purchaseWaiter?.second === waiter) purchaseWaiter = null
        }
    }

    private suspend fun launchFailed(code: Int, message: String?): PurchaseOutcome {
        when (code) {
            BillingResponseCode.USER_CANCELED -> return PurchaseOutcome(
                AppReferBilling.PurchaseResult.Outcome.USER_CANCELLED,
                null,
                customerInfo(AppReferBilling.FetchPolicy.CACHED_OR_FETCHED),
            )
            // Owned already (often a purchase AppRefer hasn't seen): send it, then report.
            BillingResponseCode.ITEM_ALREADY_OWNED -> syncPurchases()
        }
        throw AppReferBillingException.Store(code, message)
    }

    /** Called by Play for every purchase update, on the main thread. */
    fun onPurchasesUpdated(update: StorePurchasesUpdate) {
        scope.launch(dispatcher) {
            val waiter = purchaseWaiter
            val forWaiter = waiter != null &&
                (update.responseCode != BillingResponseCode.OK || update.purchases.any { waiter.first in it.productIds })
            if (waiter != null && forWaiter) {
                purchaseWaiter = null
                waiter.second.complete(update)
            } else if (update.responseCode == BillingResponseCode.OK) {
                // A pending purchase completed, or a purchase made outside `purchase()`.
                // Re-query so each purchase's product type is known.
                syncPurchases()
            }
        }
    }

    /**
     * Sends a purchased Play purchase to AppRefer. The server verifies and acknowledges
     * it; consumables are consumed here once it is accepted. If AppRefer can't take it
     * now, the token is queued on disk and access comes from Google Play meanwhile.
     */
    private suspend fun process(purchase: StorePurchase, productType: String): AppReferCustomerInfo {
        if (purchase.state != StorePurchase.State.PURCHASED) return localFallback()

        val user = currentAppUserId
        val attributes = storage.pendingAttributes
        val deviceId = appreferId()
        try {
            val response = api.send(
                BillingEndpoint.Purchase(user, deviceId, purchase.purchaseToken, purchase.productId, productType, attributes),
            )
            storage.clearSentAttributes(attributes)
            onAccepted(purchase.purchaseToken, productType)
            val info = accept(response, user)
            drainQueue()
            return info
        } catch (e: AppReferBillingException) {
            if (e.isRetryable) {
                logger.warn("Billing: queued purchase ${purchase.orderId ?: purchase.productId} for retry (${e.message})")
                if (queue.containsToken(purchase.purchaseToken)) {
                    queue.recordAttempt(purchase.purchaseToken)
                } else {
                    queue.add(
                        PendingPurchase(
                            purchaseToken = purchase.purchaseToken,
                            productId = purchase.productId,
                            productType = productType,
                            appUserId = user,
                            appreferId = deviceId,
                            enqueuedAtMillis = now(),
                            attempts = 1,
                        ),
                    )
                }
            } else {
                // Rejected as malformed: retrying won't help. Google Play's real-time
                // notifications still reach the server.
                logger.error("Billing: AppRefer rejected purchase ${purchase.orderId ?: purchase.productId}: ${e.message}")
            }
        }
        selfAcknowledgeIfOverdue(purchase, productType)
        return localFallback()
    }

    private suspend fun onAccepted(purchaseToken: String, productType: String) {
        acceptedTokens.add(purchaseToken)
        queue.remove(purchaseToken)
        if (productType == ProductTypes.CONSUMABLE && !play.consume(purchaseToken)) {
            logger.warn("Billing: consume failed; will retry on the next sync")
        }
    }

    /** See [Options.selfAcknowledgeAfterMillis]. */
    private suspend fun selfAcknowledgeIfOverdue(purchase: StorePurchase, productType: String) {
        if (now() - purchase.purchaseTimeMillis < options.selfAcknowledgeAfterMillis) return
        val done = when {
            productType == ProductTypes.CONSUMABLE -> play.consume(purchase.purchaseToken)
            !purchase.isAcknowledged -> play.acknowledge(purchase.purchaseToken)
            else -> return
        }
        logger.warn("Billing: AppRefer has not accepted ${purchase.orderId ?: purchase.productId} in time; acknowledged on device ($done)")
    }

    /**
     * The Android counterpart of StoreKit's unfinished transactions: purchased Play
     * purchases that are unacknowledged (AppRefer never accepted them) or unconsumed.
     */
    suspend fun syncPurchases() = withContext(dispatcher) {
        val result = play.queryPurchases()
        if (!result.isOk) return@withContext
        for (purchase in result.purchases) {
            if (purchase.state != StorePurchase.State.PURCHASED) continue
            val type = LocalEntitlements.productType(purchase, options.consumableProductIds)
            val unfinished = !purchase.isAcknowledged || type == ProductTypes.CONSUMABLE
            when {
                !unfinished -> continue
                purchase.purchaseToken in acceptedTokens -> {
                    if (type == ProductTypes.CONSUMABLE) play.consume(purchase.purchaseToken)
                }
                // The queue resends it; just make sure Play doesn't refund it meanwhile.
                queue.containsToken(purchase.purchaseToken) -> selfAcknowledgeIfOverdue(purchase, type)
                else -> process(purchase, type)
            }
        }
    }

    /** `queryPurchasesAsync`, then sends every purchased Play purchase to AppRefer. */
    suspend fun restore(): AppReferCustomerInfo = withContext(dispatcher) {
        val result = play.queryPurchases()
        if (!result.isOk) throw AppReferBillingException.Store(result.responseCode, result.debugMessage)
        val purchases = result.purchases.filter { it.state == StorePurchase.State.PURCHASED }
        if (purchases.isEmpty()) return@withContext customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)

        flushPendingIdentify()
        val user = currentAppUserId
        val deviceId = appreferId()
        var last: BillingCustomerResponse? = null
        var failed = false
        for (purchase in purchases) {
            val type = LocalEntitlements.productType(purchase, options.consumableProductIds)
            try {
                last = api.send(
                    BillingEndpoint.Purchase(user, deviceId, purchase.purchaseToken, purchase.productId, type, emptyMap()),
                )
                onAccepted(purchase.purchaseToken, type)
            } catch (e: AppReferBillingException) {
                failed = true
                if (!e.isRetryable) continue
                if (queue.containsToken(purchase.purchaseToken)) {
                    queue.recordAttempt(purchase.purchaseToken)
                } else {
                    queue.add(PendingPurchase(purchase.purchaseToken, purchase.productId, type, user, deviceId, now(), 1))
                }
                if (e.isConnectivity) break
            }
        }
        val lastResponse = last
        if (lastResponse != null && !failed) return@withContext accept(lastResponse, user)
        if (lastResponse != null) accept(lastResponse, user)
        localFallback()
    }

    /** Re-sends queued purchases. Stops at the first connectivity failure. */
    suspend fun drainQueue(): Unit = withContext(dispatcher) {
        if (isDraining || queue.isEmpty) return@withContext
        isDraining = true
        try {
            for (dropped in queue.prune(now())) {
                logger.error("Billing: gave up resending purchase ${dropped.productId} after ${dropped.attempts} attempts")
            }
            for (entry in queue.entries) {
                try {
                    val response = api.send(
                        BillingEndpoint.Purchase(
                            entry.appUserId, entry.appreferId, entry.purchaseToken, entry.productId, entry.productType, emptyMap(),
                        ),
                    )
                    onAccepted(entry.purchaseToken, entry.productType)
                    if (entry.appUserId == currentAppUserId) accept(response, entry.appUserId)
                } catch (e: AppReferBillingException) {
                    if (e.isRetryable) {
                        queue.recordAttempt(entry.purchaseToken)
                        if (e.isConnectivity) return@withContext
                    } else {
                        queue.remove(entry.purchaseToken)
                    }
                }
            }
        } finally {
            isDraining = false
        }
    }

    /**
     * The Google `obfuscatedAccountId` for the current user, fetched from AppRefer if not
     * cached. Null when AppRefer is unreachable: the purchase still goes ahead.
     */
    suspend fun accountToken(): String? = withContext(dispatcher) {
        val user = currentAppUserId
        storage.accountToken(user)?.let { return@withContext it }
        try {
            fetchFromServer()
        } catch (_: AppReferBillingException) {
        }
        storage.accountToken(user)
    }

    // endregion

    // region Identity

    suspend fun logIn(appUserId: String): AppReferCustomerInfo = withContext(dispatcher) {
        if (!isValid(appUserId) || BillingStorage.isAnonymous(appUserId)) throw AppReferBillingException.InvalidAppUserId()
        if (appUserId != currentAppUserId) {
            if (isAnonymous) storage.pendingIdentify = storage.anonymousId() to appUserId
            storage.loggedInAppUserId = appUserId
            lastServerFetch = null
        }
        customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
    }

    suspend fun logOut(): AppReferCustomerInfo = withContext(dispatcher) {
        storage.loggedInAppUserId = null
        storage.pendingIdentify = null
        storage.resetAnonymousId()
        lastServerFetch = null
        customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
    }

    fun setAttributes(attributes: Map<String, String>) {
        storage.mergePendingAttributes(attributes)
    }

    private suspend fun flushPendingIdentify() {
        val pending = storage.pendingIdentify ?: return
        try {
            api.send(BillingEndpoint.Identify(pending.first, pending.second))
            storage.pendingIdentify = null
        } catch (e: AppReferBillingException) {
            if (e.isRetryable) {
                logger.warn("Billing: identify will be retried (${e.message})")
            } else {
                storage.pendingIdentify = null
            }
        }
    }

    // endregion

    companion object {
        fun isValid(appUserId: String): Boolean =
            appUserId.isNotEmpty() && appUserId.length <= 256 && appUserId.none { it.isWhitespace() }
    }
}
