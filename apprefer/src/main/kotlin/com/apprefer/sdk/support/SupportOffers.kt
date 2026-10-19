package com.apprefer.sdk.support

import android.content.Context
import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

// Offer cards in the help chat (server: lib/support/offers.ts). A team member or the app's AI
// assistant attaches one of the app's AppRefer Billing offerings to a reply; the chat draws it as a
// card with the store's own prices and, when tapped, hands it to the app's own paywall through
// `AppReferSupport.onOffer`. The SDK never shows a paywall, never starts a purchase and never shows
// a price, discount or deadline of its own. A purchase is confirmed by Billing as usual.

/**
 * What [AppReferSupport.onOffer] receives when the customer taps an offer card: open your own
 * paywall for [offeringId] (e.g. `AppReferBilling.offerings().all[offer.offeringId]`), with
 * [packageId] preselected when set. Opening it is not a purchase.
 */
data class AppReferSupportOffer(
    /** Stable id of the card (`ofc_…`), the same on every device and in your team inbox. */
    val cardId: String,
    val conversationId: String,
    /** One of your app's AppRefer Billing offerings. */
    val offeringId: String,
    /** The package the team pointed at, or null for the whole offering. */
    val packageId: String?,
)

/** An offer card as the server sends it, with its state now. Prices are never part of it. */
data class AppReferSupportOfferCard(
    val cardId: String,
    val offeringId: String,
    val packageId: String?,
    /** available, owned or unavailable. */
    val status: String,
    /** Why it can't be opened: owned, revoked, expired, offering_unavailable, not_on_platform, account_changed. */
    val reason: String?,
    val packages: List<Package>,
) {
    data class Package(
        val id: String,
        /** weekly, monthly, two_month, three_month, six_month, annual, lifetime or custom. */
        val packageType: String,
        /** The name the app gave the package in AppRefer, if any. */
        val displayName: String?,
        /** The store product on this platform. */
        val productId: String,
        /** The customer's current plan or an owned purchase (only checked for verified customers). */
        val current: Boolean,
    )

    val isAvailable: Boolean get() = status == "available"

    /** The packages the card points at (one when the team picked a package). */
    val shownPackages: List<Package> get() = packageId?.let { id -> packages.filter { it.id == id }.ifEmpty { packages } } ?: packages

    internal companion object {
        /** Null when the value isn't a card (an older or unexpected payload): no broken card is drawn. */
        fun from(raw: Any?): AppReferSupportOfferCard? {
            val m = raw as? Map<*, *> ?: return null
            val cardId = m["cardId"] as? String ?: return null
            val offeringId = m["offeringId"] as? String ?: return null
            val status = m["status"] as? String ?: return null
            val packages = (m["packages"] as? List<*>).orEmpty().mapNotNull { p ->
                val pm = p as? Map<*, *> ?: return@mapNotNull null
                val id = pm["id"] as? String ?: return@mapNotNull null
                val product = pm["productId"] as? String ?: return@mapNotNull null
                Package(id, pm["packageType"] as? String ?: "custom", (pm["displayName"] as? String)?.takeIf { it.isNotBlank() }, product, pm["current"] == true)
            }
            return AppReferSupportOfferCard(cardId, offeringId, m["packageId"] as? String, status, m["reason"] as? String, packages)
        }
    }
}

/** How the chat draws a card: from the server's state and whether the app connected its paywall. */
internal enum class SupportOfferLook {
    OPEN, OWNED, UNAVAILABLE,
    /** Available, but the app set no `onOffer`: honest text, no button. */
    UNSUPPORTED;

    val notice: String? get() = when (this) {
        OPEN -> null
        OWNED -> SupportStrings.text("support.offer.owned")
        UNAVAILABLE -> SupportStrings.text("support.offer.unavailable")
        UNSUPPORTED -> SupportStrings.text("support.offer.unsupported")
    }

    companion object {
        fun of(card: AppReferSupportOfferCard, hasHandler: Boolean): SupportOfferLook = when (card.status) {
            "available" -> if (hasHandler) OPEN else UNSUPPORTED
            "owned" -> OWNED
            else -> UNAVAILABLE
        }
    }
}

internal object SupportOfferText {
    private val typeKeys = mapOf(
        "weekly" to "weekly", "monthly" to "monthly", "two_month" to "twoMonth", "three_month" to "threeMonth",
        "six_month" to "sixMonth", "annual" to "annual", "lifetime" to "lifetime",
    )

    /** The app's own name for the package, else its length in the chat's language, else the store's product name, else "Option". */
    fun label(p: AppReferSupportOfferCard.Package, storeName: String?): String {
        p.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        typeKeys[p.packageType]?.let { return SupportStrings.text("support.offer.package.$it") }
        storeName?.takeIf { it.isNotBlank() }?.let { return it }
        return SupportStrings.text("support.offer.package.custom")
    }

    /**
     * The `support.offer.price.*` key for a store billing period (ISO 8601, e.g. `P1Y`), or null
     * for one-time purchases and periods the table doesn't word (the bare store price is shown).
     */
    fun periodKey(iso: String?): String? {
        val m = Regex("^P(\\d+)([DWMY])$").matchEntire(iso?.uppercase() ?: return null) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return when (m.groupValues[2] to n) {
            "D" to 7, "W" to 1 -> "week"
            "D" to 28, "W" to 4 -> "fourWeeks"
            "M" to 1 -> "month"
            "M" to 2 -> "twoMonths"
            "M" to 3 -> "threeMonths"
            "M" to 6 -> "sixMonths"
            "M" to 12, "Y" to 1 -> "year"
            else -> null
        }
    }

    /** The store's own regular price with the store's own billing period, e.g. "£29.99/year". */
    fun price(p: SupportOfferPrice): String =
        periodKey(p.period)?.let { SupportStrings.text("support.offer.price.$it", "price" to p.price) } ?: p.price

    /** The tag on a row the customer already has: "Purchased" for a one-off, "Current plan" for a subscription. */
    fun ownedTag(p: AppReferSupportOfferCard.Package, price: SupportOfferPrice?): String? {
        if (!p.current) return null
        val oneOff = price?.oneTime ?: (p.packageType == "lifetime")
        return SupportStrings.text(if (oneOff) "support.offer.purchased" else "support.offer.current")
    }
}

/**
 * A store price and product name for one package, from AppRefer Billing: the regular price
 * (never a trial or introductory phase) with the store's billing period (ISO 8601; null for
 * one-time purchases).
 */
internal data class SupportOfferPrice(val price: String, val name: String?, val period: String? = null, val oneTime: Boolean = false)

/** A receipt the server hasn't confirmed yet. Same id on every retry, so it's stored once. */
internal data class SupportOfferReceipt(
    val conversationId: String,
    val cardId: String,
    /** shown or opened. */
    val kind: String,
    val clientEventId: String,
    val occurredAt: String,
    val attempts: Int = 0,
) {
    fun toMap(): Map<String, Any?> = mapOf("conversationId" to conversationId, "cardId" to cardId, "kind" to kind,
        "clientEventId" to clientEventId, "occurredAt" to occurredAt, "attempts" to attempts)
    companion object {
        fun from(m: Map<*, *>): SupportOfferReceipt? {
            return SupportOfferReceipt(
                m["conversationId"] as? String ?: return null, m["cardId"] as? String ?: return null, m["kind"] as? String ?: return null,
                m["clientEventId"] as? String ?: return null, m["occurredAt"] as? String ?: return null, (m["attempts"] as? Number)?.toInt() ?: 0,
            )
        }
        fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
        fun parse(iso: String): Long? = runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)?.time }.getOrNull()
        /** A receipt id from a prefix and a random part (`[A-Za-z0-9_-]{8,64}`). */
        fun eventId(prefix: String): String = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(24)
        /** The id a card's `shown` receipt always uses, so it is stored once per card. */
        fun shownId(cardId: String): String = "shown_" + if (cardId.startsWith("rvc_")) "r" + cardId.removePrefix("rvc_") else cardId.removePrefix("ofc_")
    }
}

/** Where pending receipts are kept (SharedPreferences in the app, memory in tests). */
internal interface SupportOfferReceiptBacking { var raw: String? }

internal class SupportOfferPrefs(context: Context) : SupportOfferReceiptBacking {
    private val prefs = context.applicationContext.getSharedPreferences("apprefer_support_offers", Context.MODE_PRIVATE)
    override var raw: String?
        get() = prefs.getString("receipts", null)
        set(value) { prefs.edit().apply { if (value == null) remove("receipts") else putString("receipts", value) }.apply() }
}

/**
 * Receipts waiting to be sent, kept on disk so a tap made offline still counts later. Cleared with
 * the Support session (reset, clearIdentity): they belong to that session only.
 */
internal class SupportOfferReceipts(private val backing: SupportOfferReceiptBacking, private val now: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val MAX_ITEMS = 50
        const val MAX_AGE_MS = 7 * 86_400_000L
        const val MAX_ATTEMPTS = 8
    }

    val pending: List<SupportOfferReceipt> @Synchronized get() = load()

    @Synchronized fun enqueue(r: SupportOfferReceipt) { save(load().filterNot { it.cardId == r.cardId && it.clientEventId == r.clientEventId } + r) }
    @Synchronized fun remove(r: SupportOfferReceipt) { save(load().filterNot { it.cardId == r.cardId && it.clientEventId == r.clientEventId }) }
    /** A transient failure: kept for later, up to [MAX_ATTEMPTS]. */
    @Synchronized fun failed(r: SupportOfferReceipt) {
        save(load().mapNotNull { if (it.cardId == r.cardId && it.clientEventId == r.clientEventId) it.copy(attempts = it.attempts + 1).takeIf { n -> n.attempts < MAX_ATTEMPTS } else it })
    }
    @Synchronized fun clear() { backing.raw = null }

    private fun load(): List<SupportOfferReceipt> {
        val items = runCatching { (JsonCodec.decodeObject(backing.raw)["items"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(SupportOfferReceipt::from) } }.getOrDefault(emptyList())
        val oldest = now() - MAX_AGE_MS
        return items.filter { (SupportOfferReceipt.parse(it.occurredAt) ?: 0L) > oldest }
    }
    private fun save(items: List<SupportOfferReceipt>) {
        val kept = items.takeLast(MAX_ITEMS)
        backing.raw = if (kept.isEmpty()) null else JsonCodec.encode(mapOf("items" to kept.map { it.toMap() }))
    }
}

/** The outcome of asking the server to record an `opened` receipt before the paywall opens. */
internal sealed class SupportOfferOpenCheck {
    /** Recorded (or a duplicate): open the paywall. */
    data class Recorded(val card: AppReferSupportOfferCard?) : SupportOfferOpenCheck()
    /** The server says the card can't be opened now (withdrawn, changed…): redraw, don't open. */
    object Refused : SupportOfferOpenCheck()
    /** No answer in time: open anyway (the store and the paywall are the real gate); the receipt stays queued. */
    object Offline : SupportOfferOpenCheck()
}

/**
 * Opening a card: record the `opened` receipt (asking the server whether the card can still be
 * opened, for up to [timeoutMs]), then hand it to the app. A refusal redraws the card instead.
 */
internal class SupportOfferOpener(
    val queue: SupportOfferReceipts,
    var timeoutMs: Long = 3_000,
    var send: suspend (SupportOfferReceipt) -> AppReferSupportOfferCard?,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun receipt(card: AppReferSupportOfferCard, conversationId: String) = receipt(card.cardId, conversationId)
    fun receipt(cardId: String, conversationId: String) =
        SupportOfferReceipt(conversationId, cardId, "opened", SupportOfferReceipt.eventId("open"), SupportOfferReceipt.iso(now()))

    suspend fun check(r: SupportOfferReceipt): SupportOfferOpenCheck {
        queue.enqueue(r)
        val outcome = try {
            withTimeoutOrNull(timeoutMs) { SupportOfferOpenCheck.Recorded(send(r)) } ?: SupportOfferOpenCheck.Offline
        } catch (e: AppReferSupportException) {
            if (e.status == 404 || e.status == 409) SupportOfferOpenCheck.Refused else SupportOfferOpenCheck.Offline
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SupportOfferOpenCheck.Offline
        }
        // Offline: the receipt stays queued and goes out later with the same id.
        if (outcome !is SupportOfferOpenCheck.Offline) queue.remove(r)
        return outcome
    }
}

/** Shared state for offer cards: whether the app set `onOffer`, and Billing changes to refresh on. */
internal object SupportOffers {
    @Volatile var hasHandler = false
    /**
     * `offers` (this SDK draws cards), `offer-handler` (the app opens its paywall from them) and
     * `reviews` (this SDK draws review cards and opens the store page itself).
     */
    val featuresHeader: String get() = if (hasHandler) "offers, offer-handler, reviews" else "offers, reviews"
    val billingListeners = CopyOnWriteArraySet<() -> Unit>()
    fun billingChanged() { billingListeners.forEach { runCatching { it() } } }
}
