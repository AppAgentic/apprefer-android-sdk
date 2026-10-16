package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

// MARK: - Help centre models

/** A help article as listed in the help centre (no body). */
data class AppReferHelpArticle @JvmOverloads constructor(
    val slug: String,
    val title: String,
    /** The article's summary, or the start of its text. */
    val summary: String = "",
    val category: String? = null,
    /** The article's web page, if it has one. */
    val url: String? = null,
    /** ISO 8601. */
    val updatedAt: String = "",
    /** "published" or, for test SDK keys only, "draft". */
    val status: String? = "published",
    /** The translation the server picked (e.g. "de"), null for the default language. */
    val locale: String? = null,
) {
    val isDraft: Boolean get() = status == "draft"
    /** [url] when it is an http(s) link. */
    val webUrl: String? get() = SupportMarkdown.safeUrl(url)?.takeIf { !it.lowercase().startsWith("mailto:") }
    /** [updatedAt] in milliseconds since 1970, or null. */
    val updatedAtMillis: Long? get() = supportParseDate(updatedAt)

    internal companion object {
        fun from(m: Map<String, Any?>): AppReferHelpArticle? {
            val slug = m["slug"] as? String ?: return null
            return AppReferHelpArticle(
                slug = slug,
                title = m["title"] as? String ?: slug,
                summary = m["summary"] as? String ?: "",
                category = (m["category"] as? String)?.takeIf { it.isNotEmpty() },
                url = m["url"] as? String,
                updatedAt = m["updatedAt"] as? String ?: "",
                status = m["status"] as? String,
                locale = m["locale"] as? String,
            )
        }
    }
}

/** One help article with its markdown body. */
data class AppReferHelpArticleDetail(
    val article: AppReferHelpArticle,
    /**
     * Markdown. The built-in reader renders headings, lists, links, emphasis, code and images; HTML
     * is never run and only http(s) and mailto links are kept.
     */
    val body: String,
) {
    val slug: String get() = article.slug
    val title: String get() = article.title
    val summary: String get() = article.summary
    val category: String? get() = article.category
    val url: String? get() = article.url
    val updatedAt: String get() = article.updatedAt
    val isDraft: Boolean get() = article.isDraft
}

/** What AppRefer Support can do for this app right now (`AppReferSupport.status()`). */
data class AppReferSupportStatus(
    val push: Push,
    val email: Email,
    /** Published help articles (drafts not counted). */
    val helpCentreArticles: Int,
) {
    data class PushChannel @JvmOverloads constructor(
        val configured: Boolean,
        /** true: the store accepted the credentials; false: it rejected them (see [error]); null: not checked yet or unreachable. */
        val verified: Boolean?,
        val checkedAt: String? = null,
        val error: String? = null,
        /** iOS only: which APNs environments accepted the key (production, sandbox). */
        val environments: Map<String, Boolean?>? = null,
    )
    data class Push(val ios: PushChannel, val android: PushChannel)
    data class Email @JvmOverloads constructor(
        /** Replies can be emailed to customers who gave an email address. */
        val configured: Boolean,
        val verified: Boolean? = null,
        /** Customers can answer those emails and their answer lands in the conversation. */
        val receivesReplies: Boolean,
    )

    /**
     * Register for reply notifications only when this is true: push is configured for Android and
     * Firebase accepted the service account (`verified == true`).
     */
    val pushReady: Boolean get() = push.android.configured && push.android.verified == true

    /** Customers can answer reply emails and their answer reaches the conversation. */
    val emailReplies: Boolean get() = email.receivesReplies

    internal companion object {
        private val off = PushChannel(configured = false, verified = null)

        private fun channel(m: Map<String, Any?>?): PushChannel {
            if (m == null) return off
            @Suppress("UNCHECKED_CAST") val env = (m["environments"] as? Map<String, Any?>)?.mapValues { it.value as? Boolean }
            return PushChannel(m["configured"] as? Boolean ?: false, m["verified"] as? Boolean, m["checkedAt"] as? String, m["error"] as? String, env)
        }

        @Suppress("UNCHECKED_CAST")
        fun from(m: Map<String, Any?>): AppReferSupportStatus {
            val push = m["push"] as? Map<String, Any?>
            val email = m["email"] as? Map<String, Any?>
            val help = m["helpCentre"] as? Map<String, Any?>
            return AppReferSupportStatus(
                Push(channel(push?.get("ios") as? Map<String, Any?>), channel(push?.get("android") as? Map<String, Any?>)),
                Email(email?.get("configured") as? Boolean ?: false, email?.get("verified") as? Boolean, email?.get("receivesReplies") as? Boolean ?: false),
                (help?.get("publishedArticles") as? Number)?.toInt() ?: 0,
            )
        }
    }
}

/** ISO 8601 (with or without milliseconds, Z or an offset) to milliseconds; null when unreadable. */
internal fun supportParseDate(iso: String?): Long? {
    if (iso.isNullOrEmpty()) return null
    val normalized = iso.replace(Regex("Z$"), "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
    return listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ").firstNotNullOfOrNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(normalized)?.time }.getOrNull()
    }
}

// MARK: - Requests

/**
 * The help centre and status requests: SDK key only, no support session. Lists and articles are
 * kept in memory for five minutes; when a request fails, the last copy for the same key, language
 * and query is used whatever its age, and only without one is the error thrown. The status is
 * kept as long as the server's Cache-Control `max-age` allows (5 minutes once every configured
 * channel is verified, else 30 s).
 */
internal class SupportHelpClient(
    private val key: () -> String?,
    private val transport: SupportTransport = SupportNetwork,
    private val base: String = "https://apprefer.com",
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private class Cached<T>(val value: T, val expiresAt: Long)
    private val lists = HashMap<String, Cached<List<AppReferHelpArticle>>>()
    private val articles = HashMap<String, Cached<AppReferHelpArticleDetail>>()
    private val statuses = HashMap<String, Cached<AppReferSupportStatus>>()
    private val lock = Any()

    private fun cacheKey(vararg parts: String) = (listOf(key().orEmpty()) + parts).joinToString("|")

    private fun get(path: String, query: List<Pair<String, String>> = emptyList(), language: String? = null): SupportResponse {
        val apiKey = key() ?: throw AppReferSupportException(0, "AppRefer isn't configured", AppReferSupportException.Reason.UNAVAILABLE)
        val url = base + path + if (query.isEmpty()) "" else "?" + query.joinToString("&") { (n, v) -> "$n=${escape(v)}" }
        val headers = mutableMapOf("X-AppRefer-Key" to apiKey)
        language?.let { headers["Accept-Language"] = it }
        val response = transport.request("GET", url, headers, null)
        if (response.status !in 200..299) {
            val obj = JsonCodec.decodeObject(response.body)
            throw AppReferSupportException(response.status, obj["error"] as? String ?: "Something went wrong (${response.status})")
        }
        return response
    }

    /** Published articles in [language] (the chat's language), best first; with [query], the server's search results. */
    suspend fun list(query: String?, language: String): List<AppReferHelpArticle> = withContext(Dispatchers.IO) {
        val q = normalizedQuery(query)
        val k = cacheKey("list", language, q.orEmpty())
        synchronized(lock) { lists[k]?.takeIf { it.expiresAt > now() }?.let { return@withContext it.value } }
        try {
            val items = mutableListOf("locale" to language)
            if (q != null) items.add("q" to q)
            val response = get("/api/support/v1/articles", items, language)
            val result = JsonCodec.decodeObject(response.body).objects("articles").mapNotNull { AppReferHelpArticle.from(it) }
            synchronized(lock) {
                val at = now()
                if (lists.size > 40) lists.entries.removeAll { it.value.expiresAt <= at }
                lists[k] = Cached(result, at + CACHE_MS)
            }
            result
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { synchronized(lock) { lists[k]?.value } ?: throw e }
    }

    /** The last article list for [language] (no query), whatever its age. */
    fun cachedList(language: String): List<AppReferHelpArticle>? = synchronized(lock) { lists[cacheKey("list", language, "")]?.value }

    /** One article. Throws [AppReferSupportException] with status 404 when it doesn't exist or isn't published. */
    suspend fun article(slug: String, language: String): AppReferHelpArticleDetail = withContext(Dispatchers.IO) {
        val k = cacheKey("article", language, slug)
        synchronized(lock) { articles[k]?.takeIf { it.expiresAt > now() }?.let { return@withContext it.value } }
        try {
            val response = get("/api/support/v1/articles/${escape(slug)}", listOf("locale" to language), language)
            val m = JsonCodec.decodeObject(response.body).obj("article")
            val article = AppReferHelpArticle.from(m) ?: throw AppReferSupportException(response.status, "Unreadable article")
            val detail = AppReferHelpArticleDetail(article, m["body"] as? String ?: "")
            synchronized(lock) {
                val at = now()
                if (articles.size > 40) articles.entries.removeAll { it.value.expiresAt <= at }
                articles[k] = Cached(detail, at + CACHE_MS)
            }
            detail
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: AppReferSupportException) {
            if (e.status == 404) { synchronized(lock) { articles.remove(k) }; throw e }
            synchronized(lock) { articles[k]?.value } ?: throw e
        } catch (e: Exception) { synchronized(lock) { articles[k]?.value } ?: throw e }
    }

    /** Support readiness for this app, kept as long as the server's Cache-Control allows. */
    suspend fun status(): AppReferSupportStatus = withContext(Dispatchers.IO) {
        val k = key().orEmpty()
        synchronized(lock) { statuses[k]?.takeIf { it.expiresAt > now() }?.let { return@withContext it.value } }
        val response = get("/api/support/v1/status")
        val status = AppReferSupportStatus.from(JsonCodec.decodeObject(response.body))
        val age: Long = maxAgeSeconds(response.header("cache-control")) ?: 30L
        synchronized(lock) { statuses[k] = Cached(status, now() + age * 1000L) }
        status
    }

    /** Fills the list cache (tests). */
    internal fun seed(language: String, list: List<AppReferHelpArticle>) = synchronized(lock) {
        lists[cacheKey("list", language, "")] = Cached(list, now() + CACHE_MS)
    }

    companion object {
        /** Lists and articles are kept in memory for five minutes. */
        const val CACHE_MS = 300_000L
        /** Search terms longer than this are cut (the server refuses longer ones). */
        const val MAX_QUERY_LENGTH = 200

        fun normalizedQuery(query: String?): String? = query?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_QUERY_LENGTH)

        /** `max-age` from a Cache-Control header, capped at five minutes; null without one. `no-store` / `no-cache` mean zero. */
        fun maxAgeSeconds(header: String?): Long? {
            header ?: return null
            var age: Long? = null
            for (part in header.lowercase().split(',')) {
                val directive = part.trim()
                if (directive == "no-store" || directive == "no-cache") return 0L
                if (directive.startsWith("max-age=")) directive.removePrefix("max-age=").toLongOrNull()?.let { age = it.coerceIn(0L, 300L) }
            }
            return age
        }

        private fun escape(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

// MARK: - UI logic (pure, tested on the JVM)

internal object SupportHelpLogic {
    /** Articles whose title, summary or category contain every word of [query] (case and accents ignored). */
    fun localMatches(articles: List<AppReferHelpArticle>, query: String): List<AppReferHelpArticle> {
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::fold)
        return articles.filter { a ->
            val haystack = fold(listOf(a.title, a.summary, a.category.orEmpty()).joinToString(" "))
            words.all { haystack.contains(it) }
        }
    }

    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    /**
     * Articles by category in the server's order; uncategorised ones last ("More articles"), or
     * with no heading at all when nothing has a category.
     */
    fun groups(articles: List<AppReferHelpArticle>): List<Pair<String?, List<AppReferHelpArticle>>> {
        val byCategory = LinkedHashMap<String, MutableList<AppReferHelpArticle>>()
        val loose = mutableListOf<AppReferHelpArticle>()
        for (article in articles) {
            val category = article.category?.trim()?.takeIf { it.isNotEmpty() }
            if (category == null) loose.add(article) else byCategory.getOrPut(category) { mutableListOf() }.add(article)
        }
        val result = mutableListOf<Pair<String?, List<AppReferHelpArticle>>>()
        for ((category, list) in byCategory) result.add(Pair(category, list))
        if (loose.isNotEmpty()) result.add((if (byCategory.isEmpty()) null else SupportStrings.text("support.help.otherCategory")) to loose)
        return result
    }
}

/** What the line under the customer's last message promises about the reply. */
internal enum class SupportReplyNotice {
    /** "we'll notify you" (also when the status is unknown, as before 0.10.0). */
    PUSH,
    EMAIL,
    /** Neither push nor email can reach them: the reply shows in the chat. */
    IN_APP;

    fun text(time: String?): String = when (this) {
        PUSH -> if (time.isNullOrEmpty()) SupportStrings.text("support.thread.sentNoticeNoTime") else SupportStrings.text("support.thread.sentNotice", "time" to time)
        EMAIL -> if (time.isNullOrEmpty()) SupportStrings.text("support.status.sentNoticeEmailNoTime") else SupportStrings.text("support.status.sentNoticeEmail", "time" to time)
        IN_APP -> if (time.isNullOrEmpty()) SupportStrings.text("support.status.sentNoticeInAppNoTime") else SupportStrings.text("support.status.sentNoticeInApp", "time" to time)
    }

    companion object {
        fun choose(status: AppReferSupportStatus?, hasPushToken: Boolean, hasEmail: Boolean): SupportReplyNotice {
            status ?: return PUSH
            if (status.pushReady && hasPushToken) return PUSH
            if (status.email.configured && hasEmail) return EMAIL
            return IN_APP
        }
    }
}
