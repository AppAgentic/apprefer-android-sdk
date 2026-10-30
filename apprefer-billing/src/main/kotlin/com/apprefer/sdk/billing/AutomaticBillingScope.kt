package com.apprefer.sdk.billing

/** Default store context. Explicit selections and test keys never participate in inference. */
internal class AutomaticBillingScope(
    private val storage: BillingStorage,
    private val apiKey: () -> String?,
    private val explicit: BillingEnvironment? = null,
    private val resetEpoch: () -> Int = { 0 },
) {
    class Ticket internal constructor(val user: String, internal val key: String, internal val identityRevision: Long, internal val resetEpoch: Int, internal val capability: String = java.util.UUID.randomUUID().toString())

    fun current(): BillingEnvironment? {
        return try {
        val key = apiKey()?.takeIf { it.isNotEmpty() } ?: return null
        if (key.startsWith("pk_test_")) return if (explicit == BillingEnvironment.PRODUCTION) null else BillingEnvironment.SANDBOX
        explicit?.let { return it }
        storage.automaticEnvironment(storage.currentAppUserId, key) ?: BillingEnvironment.PRODUCTION
        } catch (_: BillingStorageException) { null }
    }

    fun capture(user: String): Ticket? {
        val key = apiKey()?.takeIf { it.isNotEmpty() && !it.startsWith("pk_test_") } ?: return null
        if (explicit != null || storage.currentAppUserId != user) return null
        return Ticket(user, key, storage.identityRevision, resetEpoch())
    }

    fun authorizePending(ticket: Ticket, intent: PurchaseIntent, token: String?) {
        if (isCurrent(ticket)) storage.authorizeAutomaticIntent(intent, ticket.key, ticket.capability, token)
    }
    fun recoverPending(intent: PurchaseIntent, purchase: StorePurchase): Ticket? {
        val key = apiKey() ?: return null
        if (explicit != null || key.startsWith("pk_test_") || intent.appUserId != storage.currentAppUserId || purchase.isRenewal) return null
        if (!storage.isAutomaticIntent(intent, key, purchase)) return null
        return Ticket(intent.appUserId, key, storage.identityRevision, resetEpoch())
    }
    fun authorizeProof(ticket: Ticket, token: String, intent: PurchaseIntent? = null) {
        if (isCurrent(ticket)) storage.authorizeAutomaticProof(ticket.user, ticket.key, token, ticket.capability, intent)
    }
    fun recoverProof(user: String, token: String): Ticket? {
        val key = apiKey() ?: return null
        if (explicit != null || key.startsWith("pk_test_") || storage.currentAppUserId != user || !storage.isAutomaticProof(user, key, token)) return null
        return Ticket(user, key, storage.identityRevision, resetEpoch())
    }
    fun forgetProof(user: String, token: String) { apiKey()?.let { storage.forgetAutomaticProof(user, it, token) } }

    fun confirmedAnonymousAlias(from: String, ticket: Ticket) {
        if (!BillingStorage.isAnonymous(from) || !isCurrent(ticket)) return
        if (storage.automaticEnvironment(ticket.user, ticket.key) != null) return
        val inherited = storage.automaticEnvironment(from, ticket.key) ?: return
        storage.setAutomaticEnvironment(ticket.user, ticket.key, inherited)
    }

    fun isCurrent(ticket: Ticket): Boolean = explicit == null && apiKey() == ticket.key &&
        storage.currentAppUserId == ticket.user && storage.identityRevision == ticket.identityRevision && resetEpoch() == ticket.resetEpoch

    /** Called only after that fresh purchase is accepted for its captured owner. */
    fun commit(ticket: Ticket, environment: BillingEnvironment, acceptedToken: String? = null): Boolean {
        if (!isCurrent(ticket)) return false
        storage.setAutomaticEnvironment(ticket.user, ticket.key, environment, acceptedToken)
        return true
    }
}
