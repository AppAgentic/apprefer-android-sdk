package com.apprefer.sdk.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
    private val rejected: RejectedPurchaseLog,
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

    /** The identify flush in flight; every caller waits on this one. */
    private var identifyFlush: Deferred<Unit>? = null

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

    private suspend fun fetchFromServer(user: String, drain: Boolean = true): AppReferCustomerInfo {
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
        if (drain) drainQueue()
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
     * paying user is never locked out because AppRefer is down. Only for a total outage
     * (no customer answer); see [afterUploadFailure].
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
     * even if logIn/logOut runs meanwhile. Who started it is saved before the purchase flow
     * opens, so a purchase that completes after a restart, logOut or user switch (pending
     * payment methods) is still theirs; if that can't be saved, the flow is not opened.
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
            val user = activeUser() ?: throw AppReferBillingException.Storage("identity unreadable; purchase not started")
            val token = accountTokenFor(user)
            val intent = PurchaseIntent(productId, user, token, now())
            if (!storage.addPurchaseIntent(intent)) {
                throw AppReferBillingException.Storage("could not save who started the purchase; purchase not started")
            }
            val (code, message) = launch(token)
            if (code != BillingResponseCode.OK) {
                dropIntent(intent)
                return@withContext launchFailed(code, message)
            }
            val update = waiter.await()
            when (update.responseCode) {
                BillingResponseCode.OK -> {
                    val purchase = update.purchases.firstOrNull { productId in it.productIds }
                    when (purchase?.state) {
                        StorePurchase.State.PURCHASED -> {
                            val typed = purchase.copy(isSubscription = productType == ProductTypes.SUBSCRIPTION)
                            PurchaseOutcome(AppReferBilling.PurchaseResult.Outcome.PURCHASED, typed, process(typed, productType, user, intent = intent))
                        }
                        // Pending (slow payment method): it arrives later through
                        // onPurchasesUpdated or the next foreground sync, and the saved
                        // intent says who started it.
                        else -> PurchaseOutcome(
                            AppReferBilling.PurchaseResult.Outcome.PENDING,
                            purchase,
                            customerInfo(AppReferBilling.FetchPolicy.CACHED_OR_FETCHED),
                        )
                    }
                }
                else -> {
                    // Other errors keep the intent: Play sometimes reports one for a purchase
                    // that still goes through.
                    if (update.responseCode == BillingResponseCode.USER_CANCELED) dropIntent(intent)
                    launchFailed(update.responseCode, update.debugMessage)
                }
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

    /** Who a delivered purchase is recorded for, and the saved intent it resolves. */
    private data class Owner(val appUserId: String, val intent: PurchaseIntent?, val supportNote: String? = null)

    /** Untagged purchases left open this sync because intents were lost. */
    private var deferredForLostIntents = 0

    /**
     * The app user a purchase delivered outside its `purchase()` call belongs to (pending
     * payment completed, app restarted, user switched): whoever its `obfuscatedAccountId`
     * was issued to, else whoever the saved intent says started it (an untagged purchase
     * matches the oldest intent for its product, tagged or not), else the current user.
     * Renewals never match or use up an intent. Never a restore, so it can't move another
     * user's purchase. Null (left open and sent later) while the account tokens, or the
     * intents an untagged purchase needs, can't be read, and while intents were lost
     * ([BillingStorage.intentsLostAtMillis]): an untagged purchase with no intent then
     * waits [LOST_INTENT_WAIT_MS] from the earlier of its purchase time and the loss, then
     * goes to the current user with a support note.
     */
    private fun ownerOf(purchase: StorePurchase): Owner? {
        val tag = purchase.obfuscatedAccountId
        val tagged = tag?.let {
            try {
                storage.appUserIdForAccountToken(it)
            } catch (_: BillingStorageException) {
                return null
            }
        }
        if (purchase.isRenewal) return (tagged ?: activeUser())?.let { Owner(it, null) }
        val name = purchase.orderId ?: purchase.productId
        val intents = try {
            storage.purchaseIntents
        } catch (_: BillingStorageException) {
            if (tagged == null) {
                logger.warn("Billing: purchase intents unreadable; $name will be sent on the next sync")
                return null
            }
            emptyList()
        }
        val intent = intents.firstOrNull {
            it.productId in purchase.productIds && (tag == null || it.accountToken.equals(tag, ignoreCase = true))
        }
        if (tagged != null || intent != null) return Owner(tagged ?: intent!!.appUserId, intent)
        if (tag == null) {
            val lostAt = try {
                storage.intentsLostAtMillis
            } catch (_: BillingStorageException) {
                logger.warn("Billing: purchase intents unreadable; $name will be sent on the next sync")
                return null
            }
            if (lostAt != null) {
                val user = activeUser() ?: return null
                // From the earlier of the purchase and the loss, so it stays inside Play's
                // 3-day acknowledgement window.
                val from = if (purchase.purchaseTimeMillis > 0) minOf(lostAt, purchase.purchaseTimeMillis) else lostAt
                if (now() < from + LOST_INTENT_WAIT_MS) {
                    deferredForLostIntents++
                    logger.warn("Billing: who started $name may have been lost; left open and retried later")
                    return null
                }
                val note = "who started this purchase was lost (malformed intent storage); recorded for the current user $user"
                logger.error("Billing: $name: $note. Support: check the purchase_intents.*.corrupt file")
                return Owner(user, null, note)
            }
        }
        return activeUser()?.let { Owner(it, null) }
    }

    private fun dropIntent(intent: PurchaseIntent?) {
        if (intent != null && !storage.removePurchaseIntents(listOf(intent))) {
            logger.warn("Billing: could not drop a purchase intent; dropped later")
        }
    }

    /**
     * Clears the intents-lost marker once no purchase is waiting on it (nothing was left
     * open for it this sync and no untagged purchase is still pending payment) and it is at
     * least [LOST_INTENT_WAIT_MS] old, so a slow pending purchase that arrives later is
     * still held. Called only after a successful store query.
     */
    private fun clearLostIntentsIfResolved(purchases: List<StorePurchase>) {
        if (deferredForLostIntents > 0) return
        if (purchases.any { it.state == StorePurchase.State.PENDING && it.obfuscatedAccountId == null }) return
        try {
            val lostAt = storage.intentsLostAtMillis ?: return
            if (now() < lostAt + LOST_INTENT_WAIT_MS) return
        } catch (_: BillingStorageException) {
            return
        }
        if (!storage.clearIntentsLost()) logger.warn("Billing: could not clear the intents-lost marker; cleared later")
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
        intent: PurchaseIntent? = null,
        supportNote: String? = null,
    ): AppReferCustomerInfo {
        if (purchase.state != StorePurchase.State.PURCHASED) return localFallback()
        if (purchase.purchaseToken.isEmpty() || purchase.productId.isEmpty()) {
            logger.error("Billing: purchase ${purchase.orderId ?: purchase.productId} has no usable store data; not sent")
            if (secureRejected(purchase, productType, owner, null, "no usable store data", supportNote)) dropIntent(intent)
            return localFallback()
        }
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
            dropIntent(intent)
            val info = accept(response, owner)
            drainQueue()
            return info
        } catch (e: AppReferBillingException) {
            // Until a copy is on disk Play keeps the purchase open, and the intent is still needed.
            if (uploadFailed(purchase, productType, owner, deviceId, restore, e, supportNote)) dropIntent(intent)
            return afterUploadFailure(e)
        }
    }

    /**
     * What callers see after a purchase upload failed. If AppRefer still answers for the
     * customer, its answer is merged with only this customer's Play purchases (untagged,
     * their token, or durably queued for them), so a rejected or cut-off upload never unlocks
     * another user's purchase. Only a total outage (the customer fetch fails too) falls back
     * to every purchase on the device.
     */
    private suspend fun afterUploadFailure(e: AppReferBillingException): AppReferCustomerInfo {
        val user = activeUser() ?: return lockedFallback()
        // Not set up: a customer fetch would fail the same way. A network error gets one
        // customer fetch, since only the upload may have been cut off.
        val notSetUp = e is AppReferBillingException.MissingApiKey || e is AppReferBillingException.NotConfigured
        if (!notSetUp) {
            try {
                // No drain: the purchase that just failed would be resent straight away.
                return fetchFromServer(user, drain = false)
            } catch (_: AppReferBillingException) {
            }
        }
        return localFallback()
    }

    /**
     * Queues a failed upload (or archives a rejected one) and makes sure Play won't refund
     * it. Returns whether its copy is on disk.
     */
    private suspend fun uploadFailed(
        purchase: StorePurchase,
        productType: String,
        owner: String,
        deviceId: String?,
        restore: Boolean,
        e: AppReferBillingException,
        supportNote: String? = null,
    ): Boolean {
        val name = purchase.orderId ?: purchase.productId
        if (!e.isRetryable) {
            // Rejected as malformed: retrying won't help. Kept for support and Restore
            // Purchases; the purchase still unlocks on device.
            logger.error("Billing: AppRefer rejected purchase $name: ${e.message}")
            val secured = secureRejected(purchase, productType, owner, deviceId, e.message ?: "rejected", supportNote)
            if (secured && queue.containsToken(purchase.purchaseToken)) queue.remove(purchase.purchaseToken)
            return secured
        }
        pauseIfAsked(e)
        return queueForRetry(purchase, productType, owner, deviceId, restore, countAttempt = countsAttempt(e), reason = e.message)
    }

    /**
     * A purchase AppRefer won't take (final 400/413/422, or no usable store data): its data
     * is saved to the rejected list first, and only then is it acknowledged. It is never
     * consumed, so a consumable stays owned until AppRefer records it. Until the copy is on
     * disk it stays unacknowledged, so the next sync tries again. Returns whether it is on disk.
     */
    private suspend fun secureRejected(
        purchase: StorePurchase,
        productType: String?,
        owner: String?,
        deviceId: String?,
        reason: String,
        supportNote: String? = null,
    ): Boolean {
        val entry = RejectedPurchase(
            purchaseToken = purchase.purchaseToken,
            productId = purchase.productId,
            productType = productType,
            orderId = purchase.orderId,
            appUserId = owner,
            appreferId = deviceId,
            accountToken = purchase.obfuscatedAccountId,
            reason = reason,
            rejectedAtMillis = now(),
            originalJson = purchase.raw?.originalJson,
            supportNote = supportNote,
        )
        if (!rejected.add(entry)) {
            logger.error("Billing: could not save rejected purchase ${purchase.orderId ?: purchase.productId}; left unacknowledged")
            return false
        }
        if (purchase.purchaseToken.isNotEmpty()) ensureAcknowledged(purchase)
        return true
    }

    /** Moves a queued purchase to the rejected list; it leaves the queue only once that copy is on disk. */
    private fun moveToRejected(entry: PendingPurchase, reason: String) {
        val saved = rejected.add(
            RejectedPurchase(
                purchaseToken = entry.purchaseToken,
                productId = entry.productId,
                productType = entry.productType,
                orderId = null,
                appUserId = entry.appUserId,
                appreferId = entry.appreferId,
                accountToken = null,
                reason = reason,
                rejectedAtMillis = now(),
            ),
        )
        if (saved) queue.remove(entry.purchaseToken)
        else logger.error("Billing: could not save rejected purchase ${entry.productId}; kept in the retry queue")
    }

    /**
     * Saves the retry copy, then acknowledges on device. Without a durable copy the purchase
     * stays unacknowledged, so the next sync saves (or sends) it again.
     */
    private suspend fun queueForRetry(
        purchase: StorePurchase,
        productType: String,
        owner: String,
        deviceId: String?,
        restore: Boolean,
        countAttempt: Boolean,
        reason: String?,
    ): Boolean {
        val name = purchase.orderId ?: purchase.productId
        queue.enqueue(
            PendingPurchase(purchase.purchaseToken, purchase.productId, productType, owner, deviceId, now(), 0, restore),
            countAttempt = countAttempt,
        )
        if (queue.isDurable(purchase.purchaseToken)) {
            logger.warn("Billing: queued purchase $name for retry (${reason ?: "paused"})")
            ensureAcknowledged(purchase)
            return true
        }
        logger.error("Billing: could not save purchase $name for retry; it will be sent on the next sync")
        return false
    }

    /** Back-off statuses and `Retry-After` answers ("come back later") don't use up a purchase's retries. */
    private fun countsAttempt(e: AppReferBillingException) = !e.isBackoff && e.retryDelayMillis == null

    private suspend fun onAccepted(purchaseToken: String, productType: String) {
        acceptedTokens.add(purchaseToken)
        queue.remove(purchaseToken)
        rejected.remove(purchaseToken)
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
        queue.reloadIfNeeded()
        rejected.reloadIfNeeded()
        val result = play.queryPurchases()
        if (!result.isOk) return@withContext
        deferredForLostIntents = 0
        // Reading the intents quarantines malformed storage and saves the intents-lost marker.
        try {
            storage.purchaseIntents
        } catch (_: BillingStorageException) {
        }
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
                // The queue resends it; just make sure Play doesn't refund it meanwhile, once
                // its retry copy is on disk (a provisional copy is saved first).
                queue.containsToken(purchase.purchaseToken) -> {
                    if (queue.isDurable(purchase.purchaseToken) || (queue.persist() && queue.isDurable(purchase.purchaseToken))) {
                        ensureAcknowledged(purchase)
                    } else {
                        logger.warn("Billing: retry copy for ${purchase.orderId ?: purchase.productId} still not saved; not acknowledging yet")
                    }
                }
                // Refused for good: kept for Restore Purchases. Acknowledged once its copy is
                // on disk, never consumed and never resent from here.
                rejected.containsToken(purchase.purchaseToken) -> {
                    if (rejected.isDurable(purchase.purchaseToken) || (rejected.persist() && rejected.isDurable(purchase.purchaseToken))) {
                        ensureAcknowledged(purchase)
                    } else {
                        logger.warn("Billing: rejected copy for ${purchase.orderId ?: purchase.productId} still not saved; not acknowledging yet")
                    }
                }
                else -> {
                    val deferredBefore = deferredForLostIntents
                    val owner = ownerOf(purchase)
                    if (deferredForLostIntents > deferredBefore) continue
                    process(purchase, type, owner?.appUserId, intent = owner?.intent, supportNote = owner?.supportNote)
                }
            }
        }
        // Intents leave only on a definitive outcome, never by age or by Play not listing them.
        clearLostIntentsIfResolved(result.purchases)
    }

    /**
     * `queryPurchasesAsync`, then sends every purchased Play purchase to AppRefer as a
     * restore, which may move a purchase owned by another app user to this one. Rejected
     * purchases Play no longer lists are sent again too, and leave the rejected list once
     * AppRefer records them.
     */
    suspend fun restore(): AppReferCustomerInfo = withContext(dispatcher) {
        val result = play.queryPurchases()
        if (!result.isOk) throw AppReferBillingException.Store(result.responseCode, result.debugMessage)
        val purchases = result.purchases.filter { it.state == StorePurchase.State.PURCHASED && it.purchaseToken.isNotEmpty() }
        rejected.reloadIfNeeded()
        val listed = purchases.mapTo(HashSet()) { it.purchaseToken }
        val archived = rejected.entries.filter { it.purchaseToken.isNotEmpty() && it.purchaseToken !in listed && it.productType != null }
        if (purchases.isEmpty() && archived.isEmpty()) return@withContext customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)

        val user = activeUser() ?: return@withContext lockedFallback()
        flushPendingIdentifies()
        val deviceId = appreferId()
        var last: BillingCustomerResponse? = null
        var error: AppReferBillingException? = null
        var paused = false
        for (purchase in purchases) {
            val type = LocalEntitlements.productType(purchase, options.consumableProductIds)
            if (paused) {
                // AppRefer asked us to wait (or is down): queue the rest of the restore for
                // this customer without sending it or using up an attempt.
                queueForRetry(purchase, type, user, deviceId, restore = true, countAttempt = false, reason = error?.message)
                continue
            }
            try {
                last = api.send(
                    BillingEndpoint.Purchase(user, deviceId, purchase.purchaseToken, purchase.productId, type, emptyMap(), restore = true),
                )
                resetBackoff()
                onAccepted(purchase.purchaseToken, type)
            } catch (e: AppReferBillingException) {
                error = e
                uploadFailed(purchase, type, user, deviceId, restore = true, e)
                if (e.isRetryable && (e.isConnectivity || e.isBackoff || e.retryDelayMillis != null)) paused = true
            }
        }
        for (entry in archived) {
            if (paused) break
            try {
                last = api.send(
                    BillingEndpoint.Purchase(user, deviceId, entry.purchaseToken, entry.productId, entry.productType!!, emptyMap(), restore = true),
                )
                resetBackoff()
                rejected.remove(entry.purchaseToken)
            } catch (e: AppReferBillingException) {
                // Stays in the rejected list.
                error = e
                pauseIfAsked(e)
                if (e.isRetryable && (e.isConnectivity || e.isBackoff || e.retryDelayMillis != null)) paused = true
            }
        }
        val lastResponse = last
        val failure = error
        when {
            // The customer endpoint answered: merge only this customer's purchases (queued
            // restores count as theirs).
            lastResponse != null -> accept(lastResponse, user)
            failure != null -> afterUploadFailure(failure)
            else -> customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        }
    }

    /**
     * Re-sends queued purchases, each for the user who made it (restores stay restores).
     * Stops at the first connectivity failure. A 401/403/404/409/429 pauses the queue
     * (30 s doubling to 1 h, or the server's `Retry-After`) without counting an attempt.
     */
    suspend fun drainQueue(): Unit = withContext(dispatcher) {
        queue.reloadIfNeeded()
        if (isDraining || queue.isEmpty || now() < drainPausedUntil) return@withContext
        isDraining = true
        try {
            for (expired in queue.expired(now())) {
                logger.error("Billing: gave up resending purchase ${expired.productId} after ${expired.attempts} attempts")
                moveToRejected(expired, "not recorded after ${expired.attempts} attempts")
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
                            if (countsAttempt(e)) queue.recordAttempt(entry.purchaseToken)
                            pauseIfAsked(e)
                            if (e.isConnectivity || e.retryDelayMillis != null) return@withContext
                        }
                        else -> {
                            // Rejected as malformed. Kept for Restore Purchases; still unlocks from Google Play.
                            logger.error("Billing: AppRefer rejected queued purchase ${entry.productId}: ${e.message}")
                            moveToRejected(entry, e.message ?: "rejected")
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

    /** See `AppReferBilling.rejectedPurchases`. */
    suspend fun rejectedPurchases(): List<RejectedPurchase> = withContext(dispatcher) {
        rejected.reloadIfNeeded()
        rejected.entries
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
     * entry is removed only if it is still in the list once its request completes. One
     * flush runs at a time: concurrent callers wait for the same one, and it picks up
     * entries added while it runs before it completes.
     */
    private suspend fun flushPendingIdentifies() {
        identifyFlush?.takeIf { it.isActive }?.let { return it.await() }
        val flush = scope.async(dispatcher) { sendPendingIdentifies() }
        identifyFlush = flush
        try {
            flush.await()
        } finally {
            if (identifyFlush === flush && !flush.isActive) identifyFlush = null
        }
    }

    private suspend fun sendPendingIdentifies() {
        val attempted = HashSet<Pair<String, String>>()
        while (true) {
            val pending = try {
                storage.pendingIdentifies
            } catch (_: BillingStorageException) {
                return
            }
            val entry = pending.firstOrNull { it !in attempted } ?: return
            attempted.add(entry)
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

        /**
         * How long an untagged purchase waits for its lost intent before it goes to the
         * current user: inside Google's 3-day acknowledgement window.
         */
        const val LOST_INTENT_WAIT_MS = 48L * 3600 * 1000

        fun isValid(appUserId: String): Boolean =
            appUserId.isNotEmpty() && appUserId.length <= 256 && appUserId.none { it.isWhitespace() }
    }
}
