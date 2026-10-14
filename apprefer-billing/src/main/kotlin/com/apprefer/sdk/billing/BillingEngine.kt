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

    /** Purchase tokens acknowledged on device this launch (same cache lag). */
    private val acknowledgedTokens = HashSet<String>()
    private var isDraining = false
    private var purchaseWaiter: Pair<String, CompletableDeferred<StorePurchasesUpdate>>? = null

    /** Retry queue back-off after 401/403/404/409/429, or a `Retry-After`. */
    private var drainPausedUntil = 0L
    private var backoffDelay = 0L

    /** configure(appUserId) that couldn't be applied because storage was unreadable. */
    @Volatile private var pendingConfigure: String? = appUserId?.takeIf { it.isNotEmpty() }

    init {
        // configure(appUserId) behaves like logIn: purchases made while anonymous on this
        // device move to the user (sent to /identify when reachable).
        if (pendingConfigure != null) activeUser()
        play.setPurchasesUpdatedListener(::onPurchasesUpdated)
    }

    private fun applyConfigure(appUserId: String) {
        if (appUserId == storage.loggedInAppUserId) return
        if (storage.loggedInAppUserId == null && storage.hasAnonymousId && !BillingStorage.isAnonymous(appUserId)) {
            if (!storage.addPendingIdentify(storage.anonymousId(), appUserId)) {
                throw BillingStorageException("Could not save the pending identify")
            }
        }
        storage.loggedInAppUserId = if (BillingStorage.isAnonymous(appUserId)) null else appUserId
    }

    /**
     * The current app user, or null while billing storage can't be read. Never mints a new
     * identity over an unreadable one; applies a configure(appUserId) that had to wait.
     */
    private fun activeUser(): String? = try {
        pendingConfigure?.let {
            applyConfigure(it)
            pendingConfigure = null
        }
        storage.currentAppUserId
    } catch (e: BillingStorageException) {
        logger.error("Billing: storage unavailable (${e.message}); answering from Google Play only")
        null
    }

    // region Lifecycle

    /**
     * Sends Play purchases AppRefer hasn't accepted yet (e.g. pending purchases that
     * completed while the app was closed), retries unfinished acknowledgements, flushes the
     * retry queue and refreshes entitlements.
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

    /** Empty while billing storage can't be read. */
    val currentAppUserId: String get() = activeUser() ?: ""
    val consumableProductIds: Set<String> get() = options.consumableProductIds
    val isAnonymous: Boolean get() = activeUser()?.let(BillingStorage::isAnonymous) ?: true

    // endregion

    // region CustomerInfo

    suspend fun customerInfo(policy: AppReferBilling.FetchPolicy): AppReferCustomerInfo = withContext(dispatcher) {
        val user = activeUser() ?: return@withContext lockedFallback()
        val fetched = lastServerFetch
        if (policy == AppReferBilling.FetchPolicy.CACHED_OR_FETCHED &&
            fetched != null && fetched.first == user && now() - fetched.second < options.cacheTtlMillis
        ) {
            val cached = storage.cachedCustomerInfo()
            if (cached != null && cached.appUserId == user) {
                return@withContext publish(withDeviceEntitlements(cached.reevaluated(now())))
            }
        }
        try {
            fetchFromServer(user)
        } catch (e: AppReferBillingException) {
            logger.warn("Billing: customer refresh failed (${e.message}); using Google Play fallback")
            localFallback()
        }
    }

    private suspend fun fetchFromServer(user: String): AppReferCustomerInfo {
        flushPendingIdentifies()
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
        if (response.info.appUserId != user || user != activeUser()) {
            // Answer for a user who is no longer current (logIn/logOut raced it).
            return response.info
        }
        storage.setCachedCustomerInfo(response.info)
        lastServerFetch = user to now()
        return publish(withDeviceEntitlements(response.info))
    }

    /**
     * Merges this user's Google Play purchases into a server answer, so a payer is never
     * locked out when the server didn't get the purchase (upload dropped or rejected, or no
     * account token for Google's notification to match). A purchase counts as this user's
     * when it has no `obfuscatedAccountId`, carries this user's account token, or is still
     * queued for them. A purchase tagged with another user's token is left out.
     */
    private suspend fun withDeviceEntitlements(info: AppReferCustomerInfo): AppReferCustomerInfo {
        val result = play.queryPurchases()
        if (!result.isOk) return info
        val user = info.appUserId
        val token = storage.accountToken(user)
        val queued = queue.tokens(user)
        val mine = result.purchases.filter { p ->
            p.obfuscatedAccountId == null || p.obfuscatedAccountId.equals(token, ignoreCase = true) || p.purchaseToken in queued
        }
        if (mine.isEmpty()) return info
        return mergeLocal(info, mine, user, info.source)
    }

    /**
     * Outage path: Google Play's active purchases merged with the cached server answer for
     * the same user. Every purchase on the device counts, whoever's token it carries: a
     * paying user is never locked out because AppRefer is down.
     */
    suspend fun localFallback(): AppReferCustomerInfo = withContext(dispatcher) {
        val user = activeUser() ?: return@withContext lockedFallback()
        val purchases = play.queryPurchases().purchases
        val cached = storage.cachedCustomerInfo()?.takeIf { it.appUserId == user }?.reevaluated(now())
        publish(mergeLocal(cached, purchases, user, AppReferCustomerInfo.Source.LOCAL_FALLBACK))
    }

    /**
     * Billing storage can't be read: the user is unknown, so nothing is sent, cached or
     * published. Google Play's purchases still answer.
     */
    private suspend fun lockedFallback(): AppReferCustomerInfo =
        mergeLocal(null, play.queryPurchases().purchases, "", AppReferCustomerInfo.Source.LOCAL_FALLBACK)

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
     * arrives through `onPurchasesUpdated` and is recorded for the user who started it,
     * even if logIn/logOut runs meanwhile.
     */
    suspend fun purchase(
        productId: String,
        productType: String,
        launch: suspend (accountToken: String?) -> Pair<Int, String?>,
    ): PurchaseOutcome = withContext(dispatcher) {
        if (purchaseWaiter != null) throw AppReferBillingException.PurchaseInProgress()
        val waiter = CompletableDeferred<StorePurchasesUpdate>()
        purchaseWaiter = productId to waiter
        try {
            val user = activeUser()
            val token = user?.let { accountTokenFor(it) }
            val (code, message) = launch(token)
            if (code != BillingResponseCode.OK) return@withContext launchFailed(code, message)
            val update = waiter.await()
            when (update.responseCode) {
                BillingResponseCode.OK -> {
                    val purchase = update.purchases.firstOrNull { productId in it.productIds }
                    when (purchase?.state) {
                        StorePurchase.State.PURCHASED -> {
                            val typed = purchase.copy(isSubscription = productType == ProductTypes.SUBSCRIPTION)
                            PurchaseOutcome(AppReferBilling.PurchaseResult.Outcome.PURCHASED, typed, process(typed, productType, user))
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
     * The app user a purchase belongs to: whoever its `obfuscatedAccountId` was issued to,
     * otherwise the current user. Never a restore, so it can't move another user's purchase.
     */
    private fun ownerOf(purchase: StorePurchase): String? {
        val tagged = purchase.obfuscatedAccountId?.let {
            try {
                storage.appUserIdForAccountToken(it)
            } catch (_: BillingStorageException) {
                null
            }
        }
        return tagged ?: activeUser()
    }

    /**
     * Sends a purchased Play purchase to AppRefer for [owner]. The server verifies and
     * acknowledges it; consumables are consumed here once it is recorded. If AppRefer can't
     * take it now, the token is queued on disk, then acknowledged on device so Google never
     * refunds it while the upload waits; access comes from Google Play meanwhile.
     * [restore] is true only for Restore Purchases.
     */
    private suspend fun process(
        purchase: StorePurchase,
        productType: String,
        owner: String?,
        restore: Boolean = false,
    ): AppReferCustomerInfo {
        if (purchase.state != StorePurchase.State.PURCHASED) return localFallback()
        if (owner == null) {
            // Whose purchase this is can't be read. Left unacknowledged, so the next sync
            // sends it once storage is readable.
            logger.warn("Billing: storage unavailable; ${purchase.orderId ?: purchase.productId} will be sent on the next sync")
            return lockedFallback()
        }

        val attributes = if (owner == activeUser()) storage.pendingAttributes else emptyMap()
        val deviceId = appreferId()
        try {
            val response = api.send(
                BillingEndpoint.Purchase(owner, deviceId, purchase.purchaseToken, purchase.productId, productType, attributes, restore),
            )
            if (attributes.isNotEmpty()) storage.clearSentAttributes(attributes)
            resetBackoff()
            onAccepted(purchase.purchaseToken, productType)
            val info = accept(response, owner)
            drainQueue()
            return info
        } catch (e: AppReferBillingException) {
            uploadFailed(purchase, productType, owner, deviceId, restore, e)
        }
        return localFallback()
    }

    /** Queues a failed upload (or drops a rejected one) and makes sure Play won't refund it. */
    private suspend fun uploadFailed(
        purchase: StorePurchase,
        productType: String,
        owner: String,
        deviceId: String?,
        restore: Boolean,
        e: AppReferBillingException,
    ) {
        val name = purchase.orderId ?: purchase.productId
        if (!e.isRetryable) {
            // Rejected as malformed: retrying won't help. Google Play's real-time
            // notifications still reach the server, and the purchase still unlocks on device.
            logger.error("Billing: AppRefer rejected purchase $name: ${e.message}")
            ensureAcknowledged(purchase)
            return
        }
        val durable = queue.enqueue(
            PendingPurchase(purchase.purchaseToken, purchase.productId, productType, owner, deviceId, now(), 0, restore),
            countAttempt = !e.isBackoff,
        )
        pauseIfAsked(e)
        if (durable) {
            logger.warn("Billing: queued purchase $name for retry (${e.message})")
            ensureAcknowledged(purchase)
        } else {
            // No retry copy on disk: leave it unacknowledged, so the next sync sends it again.
            logger.error("Billing: could not save purchase $name for retry; it will be sent on the next sync")
        }
    }

    private suspend fun onAccepted(purchaseToken: String, productType: String) {
        acceptedTokens.add(purchaseToken)
        queue.remove(purchaseToken)
        if (productType == ProductTypes.CONSUMABLE && !play.consume(purchaseToken)) {
            logger.warn("Billing: consume failed; will retry on the next sync")
        }
    }

    /**
     * Acknowledges on device (the server acknowledges too; doing both is harmless), so the
     * 3-day refund never depends on the app being reopened. Consumables are acknowledged,
     * not consumed: they are consumed only after AppRefer records them.
     */
    private suspend fun ensureAcknowledged(purchase: StorePurchase) {
        if (purchase.isAcknowledged || purchase.purchaseToken in acknowledgedTokens) return
        if (play.acknowledge(purchase.purchaseToken)) {
            acknowledgedTokens.add(purchase.purchaseToken)
        } else {
            logger.warn("Billing: acknowledge failed for ${purchase.orderId ?: purchase.productId}; will retry on the next sync")
        }
    }

    /**
     * The Android counterpart of StoreKit's unfinished transactions: purchased Play
     * purchases that are unacknowledged or unconsumed. Sends the ones AppRefer hasn't seen
     * and retries acknowledge/consume that failed earlier (runs at start and on foreground).
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
                    if (type == ProductTypes.CONSUMABLE) {
                        if (!play.consume(purchase.purchaseToken)) logger.warn("Billing: consume failed; will retry on the next sync")
                    } else {
                        ensureAcknowledged(purchase)
                    }
                }
                // The queue resends it; just make sure Play doesn't refund it meanwhile.
                queue.containsToken(purchase.purchaseToken) -> ensureAcknowledged(purchase)
                else -> process(purchase, type, ownerOf(purchase))
            }
        }
    }

    /**
     * `queryPurchasesAsync`, then sends every purchased Play purchase to AppRefer as a
     * restore, which may move a purchase owned by another app user to this one.
     */
    suspend fun restore(): AppReferCustomerInfo = withContext(dispatcher) {
        val result = play.queryPurchases()
        if (!result.isOk) throw AppReferBillingException.Store(result.responseCode, result.debugMessage)
        val purchases = result.purchases.filter { it.state == StorePurchase.State.PURCHASED }
        if (purchases.isEmpty()) return@withContext customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)

        val user = activeUser() ?: return@withContext lockedFallback()
        flushPendingIdentifies()
        val deviceId = appreferId()
        var last: BillingCustomerResponse? = null
        var failed = false
        for (purchase in purchases) {
            val type = LocalEntitlements.productType(purchase, options.consumableProductIds)
            try {
                last = api.send(
                    BillingEndpoint.Purchase(user, deviceId, purchase.purchaseToken, purchase.productId, type, emptyMap(), restore = true),
                )
                resetBackoff()
                onAccepted(purchase.purchaseToken, type)
            } catch (e: AppReferBillingException) {
                failed = true
                uploadFailed(purchase, type, user, deviceId, restore = true, e)
                if (e.isRetryable && (e.isConnectivity || e.isBackoff)) break
            }
        }
        val lastResponse = last
        if (lastResponse != null && !failed) return@withContext accept(lastResponse, user)
        if (lastResponse != null) accept(lastResponse, user)
        localFallback()
    }

    /**
     * Re-sends queued purchases, each for the user who made it (restores stay restores).
     * Stops at the first connectivity failure. A 401/403/404/409/429 pauses the queue
     * (30 s doubling to 1 h, or the server's `Retry-After`) without counting an attempt.
     */
    suspend fun drainQueue(): Unit = withContext(dispatcher) {
        if (isDraining || queue.isEmpty || now() < drainPausedUntil) return@withContext
        isDraining = true
        try {
            for (dropped in queue.prune(now())) {
                logger.error("Billing: gave up resending purchase ${dropped.productId} after ${dropped.attempts} attempts")
            }
            for (entry in queue.entries) {
                try {
                    val response = api.send(
                        BillingEndpoint.Purchase(
                            entry.appUserId, entry.appreferId, entry.purchaseToken, entry.productId, entry.productType,
                            emptyMap(), entry.restore,
                        ),
                    )
                    resetBackoff()
                    onAccepted(entry.purchaseToken, entry.productType)
                    if (entry.appUserId == activeUser()) accept(response, entry.appUserId)
                } catch (e: AppReferBillingException) {
                    when {
                        e.isBackoff -> {
                            pauseIfAsked(e)
                            logger.warn("Billing: AppRefer answered ${e.message}; retrying queued purchases later")
                            return@withContext
                        }
                        e.isRetryable -> {
                            queue.recordAttempt(entry.purchaseToken)
                            pauseIfAsked(e)
                            if (e.isConnectivity || e.retryDelayMillis != null) return@withContext
                        }
                        else -> {
                            // Rejected as malformed. Still unlocks from Google Play.
                            logger.error("Billing: AppRefer rejected queued purchase ${entry.productId}: ${e.message}")
                            queue.remove(entry.purchaseToken)
                        }
                    }
                }
            }
        } finally {
            isDraining = false
        }
    }

    /** Pauses the retry queue after a back-off status (doubling) or a `Retry-After`. */
    private fun pauseIfAsked(e: AppReferBillingException) {
        val wait = when {
            e.isBackoff -> {
                backoffDelay = (backoffDelay * 2).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
                e.retryDelayMillis ?: backoffDelay
            }
            else -> e.retryDelayMillis ?: return
        }.coerceAtMost(MAX_BACKOFF_MS)
        drainPausedUntil = maxOf(drainPausedUntil, now() + wait)
    }

    private fun resetBackoff() {
        backoffDelay = 0
        drainPausedUntil = 0
    }

    /**
     * The Google `obfuscatedAccountId` for the current user, fetched from AppRefer if not
     * cached. Null when AppRefer is unreachable: the purchase still goes ahead.
     */
    suspend fun accountToken(): String? = withContext(dispatcher) {
        activeUser()?.let { accountTokenFor(it) }
    }

    private suspend fun accountTokenFor(user: String): String? {
        storage.accountToken(user)?.let { return it }
        try {
            fetchFromServer(user)
        } catch (_: AppReferBillingException) {
        }
        return storage.accountToken(user)
    }

    // endregion

    // region Identity

    suspend fun logIn(appUserId: String): AppReferCustomerInfo = withContext(dispatcher) {
        if (!isValid(appUserId) || BillingStorage.isAnonymous(appUserId)) throw AppReferBillingException.InvalidAppUserId()
        val current = activeUser() ?: throw AppReferBillingException.Storage("identity unreadable")
        if (appUserId != current) {
            storageWrite {
                if (BillingStorage.isAnonymous(current) && !storage.addPendingIdentify(current, appUserId)) {
                    throw BillingStorageException("Could not save the pending identify")
                }
                storage.loggedInAppUserId = appUserId
            }
            lastServerFetch = null
        }
        customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
    }

    /** Signs out and starts a fresh anonymous id. Pending identifies stay: they still have to reach the server. */
    suspend fun logOut(): AppReferCustomerInfo = withContext(dispatcher) {
        activeUser() ?: throw AppReferBillingException.Storage("identity unreadable")
        storageWrite {
            storage.resetAnonymousId()
            storage.loggedInAppUserId = null
        }
        lastServerFetch = null
        customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
    }

    private inline fun storageWrite(block: () -> Unit) {
        try {
            block()
        } catch (e: BillingStorageException) {
            throw AppReferBillingException.Storage(e.message ?: "write failed")
        }
    }

    fun setAttributes(attributes: Map<String, String>) {
        storage.mergePendingAttributes(attributes)
    }

    /**
     * Sends waiting identifies in order; stops at the first one that should be retried. An
     * entry is removed only if it is still in the list once its request completes.
     */
    private suspend fun flushPendingIdentifies() {
        val pending = try {
            storage.pendingIdentifies
        } catch (_: BillingStorageException) {
            return
        }
        for (entry in pending) {
            try {
                api.send(BillingEndpoint.Identify(entry.first, entry.second))
                storage.removePendingIdentify(entry)
            } catch (e: AppReferBillingException) {
                if (e.isRetryable) {
                    logger.warn("Billing: identify will be retried (${e.message})")
                    return
                }
                storage.removePendingIdentify(entry)
            }
        }
    }

    // endregion

    companion object {
        const val MIN_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 3600_000L

        fun isValid(appUserId: String): Boolean =
            appUserId.isNotEmpty() && appUserId.length <= 256 && appUserId.none { it.isWhitespace() }
    }
}
