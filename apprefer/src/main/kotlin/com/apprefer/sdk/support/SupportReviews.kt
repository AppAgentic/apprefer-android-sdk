package com.apprefer.sdk.support

import java.net.URI

// Review cards in the help chat (server: lib/support/reviews.ts). A team member can attach an
// optional "Write a review" card to a reply; tapping it opens the app's Google Play listing, as
// Google recommends for a review button (the quota-limited In-App Review API is never put behind a
// button, so a tap never silently does nothing). The SDK never shows stars, never asks whether the
// customer likes the app, and never says a review was written: opening the page is all it knows.

/** A review card as the server sends it, with its state now. */
data class AppReferSupportReviewCard(
    /** Stable id of the card (`rvc_…`). */
    val cardId: String,
    /** available or unavailable. */
    val status: String,
    /** Why it can't be opened: revoked, expired, account_changed, disabled, store_changed, not_on_platform. */
    val reason: String?,
    /** app_store or play_store (null when unavailable). */
    val store: String?,
    /** The store page to open; null unless available. */
    val url: String?,
) {
    val isAvailable: Boolean get() = status == "available"

    internal companion object {
        /** Null when the value isn't a review card: no broken card is drawn. */
        fun from(raw: Any?): AppReferSupportReviewCard? {
            val m = raw as? Map<*, *> ?: return null
            val cardId = m["cardId"] as? String ?: return null
            val status = m["status"] as? String ?: return null
            return AppReferSupportReviewCard(cardId, status, m["reason"] as? String, m["store"] as? String, m["url"] as? String)
        }
    }
}

internal sealed class SupportReviewLook {
    data class Open(val url: String) : SupportReviewLook()
    object Unavailable : SupportReviewLook()
    /** No store page for this device (or a link the SDK won't open). */
    object Unsupported : SupportReviewLook()

    companion object {
        fun of(card: AppReferSupportReviewCard): SupportReviewLook {
            if (!card.isAvailable) return if (card.reason == "not_on_platform") Unsupported else Unavailable
            return SupportReviewLink.validated(card.url)?.let { Open(it) } ?: Unsupported
        }
    }
}

internal object SupportReviewLink {
    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /**
     * Only one app's Google Play listing: https, play.google.com, `/store/apps/details`, a single
     * `id=<package>` and nothing else. Anything else is never opened, whatever the server says.
     */
    fun validated(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val u = runCatching { URI(raw) }.getOrNull() ?: return null
        if (u.scheme != "https" || u.host?.lowercase() != "play.google.com" || u.rawUserInfo != null || u.port != -1 || u.rawFragment != null) return null
        if (u.rawPath != "/store/apps/details") return null
        val q = u.rawQuery ?: return null
        if (!q.startsWith("id=") || q.contains('&')) return null
        val pkg = q.removePrefix("id=")
        return if (PACKAGE.matches(pkg)) "https://play.google.com/store/apps/details?id=$pkg" else null
    }

    /** The string-table suffix for a store (`googlePlay` / `appStore`): each language words the store its own way. */
    fun storeKey(store: String?): String = if (store == "app_store") "appStore" else "googlePlay"
}
