package com.apprefer.sdk.billing

import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * `CustomerInfo` is also available as [AppReferCustomerInfo], for files that import
 * both AppRefer and RevenueCat during a migration.
 */
typealias CustomerInfo = AppReferCustomerInfo

/** One entitlement (for example `premium`). Also available as [AppReferEntitlementInfo]. */
typealias EntitlementInfo = AppReferEntitlementInfo

/**
 * The customer's entitlements as AppRefer Billing sees them.
 *
 * Mirrors RevenueCat's `CustomerInfo`, so `customerInfo.entitlements["premium"]?.isActive`
 * works the same way. Decoded from the server's `CustomerInfo` JSON
 * (`web/lib/billing/types.ts` in the AppRefer repo).
 */
enum class BillingEnvironment(val raw: String) { PRODUCTION("production"), SANDBOX("sandbox") }

data class AppReferCustomerInfo(
    val appUserId: String,
    val entitlements: EntitlementInfos,
    /** Product ids of auto-renewing subscriptions that currently grant access. */
    val activeSubscriptions: Set<String>,
    /** When the snapshot was computed, in epoch milliseconds. */
    val requestDateMillis: Long,
    val source: Source,
    val environment: BillingEnvironment? = null,
) {
    /** Where this snapshot came from. */
    enum class Source(internal val raw: String) {
        /** Returned by the AppRefer server just now. */
        SERVER("server"),

        /** The last server response, re-checked against the clock. */
        CACHE("cache"),

        /**
         * AppRefer was unreachable or refused the request; entitlements come from Google
         * Play's own active purchases, merged with the cache.
         */
        LOCAL_FALLBACK("localFallback"),
        ;

        internal companion object {
            fun from(raw: String?): Source? = values().firstOrNull { it.raw == raw }
        }
    }

    /** When the snapshot was computed. */
    val requestDate: Date get() = Date(requestDateMillis)

    /** True when any entitlement is active. */
    val hasActiveEntitlement: Boolean get() = entitlements.active.isNotEmpty()

    internal fun withSource(source: Source) = copy(source = source)

    /** Same user, entitlements and subscriptions (ignores request date and source). */
    internal fun hasSameContent(other: AppReferCustomerInfo): Boolean =
        environment == other.environment && appUserId == other.appUserId &&
            entitlements == other.entitlements &&
            activeSubscriptions == other.activeSubscriptions

    /**
     * Re-checks a cached snapshot against the clock, so an expired subscription never
     * stays unlocked from an old cache. Grace periods keep the server's verdict.
     */
    internal fun reevaluated(nowMillis: Long): AppReferCustomerInfo {
        val all = LinkedHashMap<String, AppReferEntitlementInfo>()
        val activeSubs = activeSubscriptions.toMutableSet()
        for ((id, ent) in entitlements.all) {
            val expires = ent.expirationDateMillis
            if (ent.isActive && ent.state != BillingSubscriptionState.IN_GRACE_PERIOD &&
                expires != null && expires <= nowMillis
            ) {
                all[id] = ent.expired()
                activeSubs.remove(ent.productIdentifier)
            } else {
                all[id] = ent
            }
        }
        return AppReferCustomerInfo(appUserId, EntitlementInfos(all), activeSubs, nowMillis, Source.CACHE, environment)
    }
}

/** Entitlements keyed by id. `entitlements["premium"]?.isActive`. */
data class EntitlementInfos(val all: Map<String, AppReferEntitlementInfo>) {
    operator fun get(identifier: String): AppReferEntitlementInfo? = all[identifier]

    /** Only the entitlements that currently grant access. */
    val active: Map<String, AppReferEntitlementInfo> get() = all.filterValues { it.isActive }
}

data class AppReferEntitlementInfo(
    val identifier: String,
    val isActive: Boolean,
    val productIdentifier: String,
    val store: BillingStore,
    /** Epoch milliseconds; null for lifetime (non-consumable) purchases, or when unknown. */
    val expirationDateMillis: Long?,
    val willRenew: Boolean,
    val periodType: BillingPeriodType,
    val state: BillingSubscriptionState,
    /** Apple original transaction id / Google purchase-chain key. */
    val subscriptionKey: String,
) {
    /** Null for lifetime (non-consumable) purchases, or when unknown. */
    val expirationDate: Date? get() = expirationDateMillis?.let(::Date)

    internal fun expired() = copy(isActive = false, willRenew = false, state = BillingSubscriptionState.EXPIRED)

    /**
     * Server rule for choosing between two sources of one entitlement:
     * active beats inactive, then the later expiry (null = lifetime) wins.
     */
    internal fun beats(other: AppReferEntitlementInfo): Boolean {
        if (isActive != other.isActive) return isActive
        return (expirationDateMillis ?: Long.MAX_VALUE) > (other.expirationDateMillis ?: Long.MAX_VALUE)
    }
}

enum class BillingStore(internal val raw: String) {
    APP_STORE("app_store"),
    PLAY_STORE("play_store"),
    STRIPE("stripe"),
    UNKNOWN("unknown"),
    ;

    internal companion object {
        fun from(raw: String?) = values().firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

enum class BillingPeriodType(internal val raw: String) {
    TRIAL("trial"),
    INTRO("intro"),
    NORMAL("normal"),
    PROMOTIONAL("promotional"),
    UNKNOWN("unknown"),
    ;

    internal companion object {
        fun from(raw: String?) = values().firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

enum class BillingSubscriptionState(internal val raw: String) {
    ACTIVE("active"),

    /** Billing failed but the store is still retrying; access continues. */
    IN_GRACE_PERIOD("in_grace_period"),

    /** Billing failed; no access. */
    IN_BILLING_RETRY("in_billing_retry"),
    PAUSED("paused"),
    EXPIRED("expired"),
    REVOKED("revoked"),
    UNKNOWN("unknown"),
    ;

    internal companion object {
        fun from(raw: String?) = values().firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/** Server response for the customers/purchases/identify endpoints. */
internal data class BillingCustomerResponse(val info: AppReferCustomerInfo, val accountToken: String?)

/** JSON mapping shared by the API client (server answers) and the on-disk cache. */
internal object CustomerInfoJson {

    /** Parses a server answer. Throws [AppReferBillingException.InvalidResponse] without an `appUserId`. */
    fun parseResponse(body: String, nowMillis: Long = System.currentTimeMillis()): BillingCustomerResponse {
        val obj = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AppReferBillingException.InvalidResponse()
        }
        val info = decode(obj, nowMillis)?.withSource(AppReferCustomerInfo.Source.SERVER)
            ?: throw AppReferBillingException.InvalidResponse()
        return BillingCustomerResponse(info, obj.optStringOrNull("accountToken"))
    }

    fun decode(obj: JSONObject, nowMillis: Long = System.currentTimeMillis()): AppReferCustomerInfo? {
        val appUserId = obj.optStringOrNull("appUserId") ?: return null
        val entitlements = LinkedHashMap<String, AppReferEntitlementInfo>()
        obj.optJSONObject("entitlements")?.let { ents ->
            for (key in ents.keys()) {
                val e = ents.optJSONObject(key) ?: continue
                entitlements[key] = decodeEntitlement(e, key)
            }
        }
        val subs = LinkedHashSet<String>()
        obj.optJSONArray("activeSubscriptions")?.let { arr ->
            for (i in 0 until arr.length()) arr.optStringOrNull(i)?.let(subs::add)
        }
        return AppReferCustomerInfo(
            environment = BillingEnvironment.entries.firstOrNull { it.raw == obj.optStringOrNull("environment") },
            appUserId = appUserId,
            entitlements = EntitlementInfos(entitlements),
            activeSubscriptions = subs,
            requestDateMillis = obj.optNumber("requestDateMs")?.toLong() ?: nowMillis,
            source = AppReferCustomerInfo.Source.from(obj.optStringOrNull("source"))
                ?: AppReferCustomerInfo.Source.SERVER,
        )
    }

    private fun decodeEntitlement(e: JSONObject, key: String) = AppReferEntitlementInfo(
        identifier = e.optStringOrNull("id")?.takeIf { it.isNotEmpty() } ?: key,
        isActive = e.opt("isActive") as? Boolean ?: false,
        productIdentifier = e.optStringOrNull("productId") ?: "",
        store = BillingStore.from(e.optStringOrNull("store")),
        expirationDateMillis = e.optNumber("expiresAtMs")?.toLong(),
        willRenew = e.opt("willRenew") as? Boolean ?: false,
        periodType = BillingPeriodType.from(e.optStringOrNull("periodType")),
        state = BillingSubscriptionState.from(e.optStringOrNull("state")),
        subscriptionKey = e.optStringOrNull("subscriptionKey") ?: "",
    )

    fun encode(info: AppReferCustomerInfo): JSONObject {
        val ents = JSONObject()
        for ((id, e) in info.entitlements.all) {
            ents.put(
                id,
                JSONObject()
                    .put("id", e.identifier)
                    .put("isActive", e.isActive)
                    .put("productId", e.productIdentifier)
                    .put("store", e.store.raw)
                    .put("expiresAtMs", e.expirationDateMillis ?: JSONObject.NULL)
                    .put("willRenew", e.willRenew)
                    .put("periodType", e.periodType.raw)
                    .put("state", e.state.raw)
                    .put("subscriptionKey", e.subscriptionKey),
            )
        }
        return JSONObject()
            .put("environment", info.environment?.raw)
            .put("appUserId", info.appUserId)
            .put("entitlements", ents)
            .put("activeSubscriptions", JSONArray(info.activeSubscriptions.sorted()))
            .put("requestDateMs", info.requestDateMillis)
            .put("source", info.source.raw)
    }

    private fun JSONObject.optStringOrNull(key: String): String? = opt(key) as? String
    private fun JSONArray.optStringOrNull(index: Int): String? = opt(index) as? String
    private fun JSONObject.optNumber(key: String): Number? = opt(key) as? Number
}
