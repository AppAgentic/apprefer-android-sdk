package com.apprefer.sdk.support

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class SupportHelpTest {
    @Before fun strings() = SupportStringsFixture.install()
    @After fun resetStrings() = SupportStringsFixture.uninstall()

    private val listJSON = """
        {"articles":[{"slug":"refunds","title":"Refunds","summary":"How refunds work","category":"Billing","locale":"de","url":"https://help.example.com/refunds","status":"published","updatedAt":"2026-10-15T09:00:00.000Z"},
                     {"slug":"draft-1","title":"Coming soon","summary":"","category":null,"locale":null,"url":null,"status":"draft","updatedAt":"2026-10-16T09:00:00Z"}],"locale":"de"}
    """.trimIndent()
    private val statusJSON = """
        {"push":{"ios":{"configured":true,"verified":true,"checkedAt":"2026-10-16T09:00:00.000Z","environments":{"production":true,"sandbox":null}},
                 "android":{"configured":true,"verified":false,"checkedAt":"2026-10-16T09:00:00.000Z","error":"No permission"}},
         "email":{"configured":true,"verified":null,"receivesReplies":true},"helpCentre":{"publishedArticles":12}}
    """.trimIndent()

    private class Request(val method: String, val url: String, val headers: Map<String, String>)
    private class Harness(var key: String? = "pk_test_help") {
        val requests = mutableListOf<Request>()
        var now = 1_800_000_000_000L
        var reply: (Request) -> SupportResponse? = { SupportResponse(200, "{}") }
        val client = SupportHelpClient({ key }, SupportTransport { method, url, headers, _ ->
            val request = Request(method, url, headers.toMap())
            requests.add(request)
            reply(request) ?: throw java.net.UnknownHostException("offline")
        }, "https://help.test", { now })
        fun articleRequests() = requests.filter { it.url.startsWith("https://help.test/api/support/v1/articles") }
    }

    private fun query(url: String): Map<String, String> = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    // MARK: - Articles

    @Test fun listSendsKeyLocaleAndQueryWithoutASession() = runBlocking {
        val h = Harness(); h.reply = { SupportResponse(200, listJSON) }
        val articles = h.client.list("  c++ & refunds ", "pt-BR")
        assertEquals(listOf("refunds", "draft-1"), articles.map { it.slug })
        val r = h.articleRequests().last()
        assertEquals("GET", r.method)
        assertTrue(r.url.startsWith("https://help.test/api/support/v1/articles?"))
        assertEquals("pk_test_help", r.headers["X-AppRefer-Key"])
        assertNull("the help centre needs no session", r.headers["X-AppRefer-Support-Session"])
        assertEquals("pt-BR", r.headers["Accept-Language"])
        assertEquals("pt-BR", query(r.url)["locale"])
        assertEquals("trimmed, and + and & survive encoding", "c++ & refunds", query(r.url)["q"])
        assertTrue(r.url.contains("q=c%2B%2B%20%26%20refunds"))
        assertFalse("no support session is minted", h.requests.any { it.url.endsWith("/session") })
    }

    @Test fun listWithoutQueryHasNoQAndLongQueriesAreCut() = runBlocking {
        val h = Harness(); h.reply = { SupportResponse(200, listJSON) }
        h.client.list("   ", "en")
        assertNull(query(h.articleRequests().last().url)["q"])
        h.client.list("a".repeat(250), "en")
        assertEquals(200, query(h.articleRequests().last().url)["q"]?.length)
    }

    @Test fun decodesArticlesAndDrafts() = runBlocking {
        val h = Harness(); h.reply = { SupportResponse(200, listJSON) }
        val articles = h.client.list(null, "de")
        assertEquals("Billing", articles[0].category)
        assertEquals("https://help.example.com/refunds", articles[0].webUrl)
        assertNotNull(articles[0].updatedAtMillis)
        assertFalse(articles[0].isDraft)
        assertTrue(articles[1].isDraft)
        assertNull(articles[1].category)
        assertNull(articles[1].webUrl)
        assertNotNull("no milliseconds", articles[1].updatedAtMillis)
    }

    @Test fun listIsCachedForFiveMinutesThenRefetched() = runBlocking {
        val h = Harness(); h.reply = { SupportResponse(200, listJSON) }
        h.client.list(null, "en")
        h.now += 299_000
        h.client.list(null, "en")
        assertEquals("served from memory", 1, h.articleRequests().size)
        h.client.list(null, "de")
        assertEquals("another language is another list", 2, h.articleRequests().size)
        h.now += 2_000
        h.client.list(null, "en")
        assertEquals("refetched after five minutes", 3, h.articleRequests().size)
        h.key = "pk_test_other"
        h.client.list(null, "en")
        assertEquals("another SDK key is another list", 4, h.articleRequests().size)
    }

    @Test fun offlineReturnsTheLastListAndOtherwiseThrows() = runBlocking {
        val h = Harness(); h.reply = { SupportResponse(200, listJSON) }
        h.client.list(null, "en")
        h.now += 3_600_000
        h.reply = { null }
        assertEquals("an old list beats an error", 2, h.client.list(null, "en").size)
        assertEquals(2, h.client.cachedList("en")?.size)
        try { h.client.list(null, "fr"); fail("nothing cached for fr") } catch (e: java.net.UnknownHostException) {}
        h.reply = { SupportResponse(503, """{"error":"down"}""") }
        try { h.client.list("x", "en"); fail() } catch (e: AppReferSupportException) { assertEquals(503, e.status) }
    }

    @Test fun articleDecodesBodyAndThrowsNotFoundFor404() = runBlocking {
        val h = Harness()
        h.reply = { r ->
            if (r.url.contains("/missing")) SupportResponse(404, """{"error":"Article not found"}""")
            else SupportResponse(200, """{"article":{"slug":"a b","title":"Refunds","summary":"s","category":null,"locale":"en","url":null,"status":"published","updatedAt":"2026-10-15T09:00:00Z","body":"# Refunds\n\nText"}}""")
        }
        val article = h.client.article("a b", "en")
        assertEquals("Refunds", article.title)
        assertEquals("# Refunds\n\nText", article.body)
        assertEquals("https://help.test/api/support/v1/articles/a%20b?locale=en", h.articleRequests().last().url)
        assertEquals("pk_test_help", h.articleRequests().last().headers["X-AppRefer-Key"])
        try { h.client.article("missing", "en"); fail("404 must throw") } catch (e: AppReferSupportException) {
            assertTrue(e.isNotFound); assertEquals(404, e.status)
        }
        // A cached article is served offline; a 404 is never hidden by the cache.
        h.now += 3_600_000; h.reply = { null }
        assertEquals("Refunds", h.client.article("a b", "en").title)
    }

    @Test fun withoutAnApiKeyTheHelpCentreIsUnavailable() = runBlocking {
        val h = Harness(key = null); h.reply = { SupportResponse(200, listJSON) }
        try { h.client.list(null, "en"); fail("expected unavailable") } catch (e: AppReferSupportException) { assertEquals(0, e.status) }
        assertTrue(h.requests.isEmpty())
    }

    // MARK: - Status

    @Test fun statusMapping() {
        val status = AppReferSupportStatus.from(com.apprefer.sdk.internal.JsonCodec.decodeObject(statusJSON))
        assertFalse("Android was rejected", status.pushReady)
        assertTrue(status.emailReplies)
        assertEquals(12, status.helpCentreArticles)
        assertEquals(false, status.push.android.verified)
        assertEquals("No permission", status.push.android.error)
        assertTrue(status.push.ios.verified == true)
        assertEquals(true, status.push.ios.environments?.get("production"))
        assertNull(status.email.verified)
        val empty = AppReferSupportStatus.from(emptyMap())
        assertFalse(empty.pushReady); assertFalse(empty.emailReplies); assertEquals(0, empty.helpCentreArticles)
    }

    @Test fun pushReadyTruthTable() {
        fun status(configured: Boolean, verified: Boolean?) = AppReferSupportStatus(
            AppReferSupportStatus.Push(AppReferSupportStatus.PushChannel(true, true), AppReferSupportStatus.PushChannel(configured, verified)),
            AppReferSupportStatus.Email(configured = false, receivesReplies = false), 0)
        assertTrue(status(true, true).pushReady)
        assertFalse(status(true, false).pushReady)
        assertFalse("not checked yet", status(true, null).pushReady)
        assertFalse(status(false, null).pushReady)
        assertFalse(status(false, true).pushReady)
        // iOS being ready never makes Android ready.
        val iosOnly = AppReferSupportStatus(AppReferSupportStatus.Push(AppReferSupportStatus.PushChannel(true, true), AppReferSupportStatus.PushChannel(false, null)),
            AppReferSupportStatus.Email(configured = true, receivesReplies = false), 0)
        assertFalse(iosOnly.pushReady)
        assertFalse("configured email without inbound replies", iosOnly.emailReplies)
    }

    @Test fun statusHonoursCacheControl() = runBlocking {
        val h = Harness()
        var maxAge = 300
        h.reply = { SupportResponse(200, statusJSON, mapOf("cache-control" to "private, max-age=$maxAge")) }
        val calls = { h.requests.filter { it.url == "https://help.test/api/support/v1/status" } }
        h.client.status(); h.now += 299_000; h.client.status()
        assertEquals(1, calls().size)
        assertEquals("pk_test_help", calls().first().headers["X-AppRefer-Key"])
        h.now += 2_000; maxAge = 30; h.client.status()
        assertEquals(2, calls().size)
        h.now += 29_000; h.client.status()
        assertEquals("30 s while something is unverified", 2, calls().size)
        h.now += 2_000; h.client.status()
        assertEquals(3, calls().size)
    }

    @Test fun maxAgeParsing() {
        assertEquals(300L, SupportHelpClient.maxAgeSeconds("private, max-age=300"))
        assertEquals(30L, SupportHelpClient.maxAgeSeconds("max-age=30"))
        assertEquals("capped at five minutes", 300L, SupportHelpClient.maxAgeSeconds("max-age=99999"))
        assertEquals(0L, SupportHelpClient.maxAgeSeconds("no-store"))
        assertNull(SupportHelpClient.maxAgeSeconds("private"))
        assertNull(SupportHelpClient.maxAgeSeconds(null))
        assertEquals("headers are read ignoring case", "x", SupportResponse(200, "", mapOf("Cache-Control" to "x")).header("cache-control"))
    }

    // MARK: - Markdown

    @Test fun unsafeLinksAreDropped() {
        val text = SupportMarkdown.inline("[a](javascript:alert(1)) [b](https://ok.example/x) [c](mailto:help@example.com) [d](data:text/html,hi) [e](/relative) [f](myapp://open) [g](JAVASCRIPT:alert(1)) <javascript:alert(2)> [h](http:no-host) [i](vbscript:x) [j]( https://spaced.example )")
        assertEquals(listOf("https://ok.example/x", "mailto:help@example.com", "https://spaced.example"), text.links)
        assertTrue("the link text stays as plain text", text.text.startsWith("a b c d e f g"))
        assertFalse(text.text.contains("]("))
    }

    @Test fun safeUrl() {
        assertNotNull(SupportMarkdown.safeUrl("https://example.com"))
        assertNotNull(SupportMarkdown.safeUrl("HTTP://example.com/a?b=c"))
        assertNotNull(SupportMarkdown.safeUrl("mailto:a@b.co"))
        assertNull(SupportMarkdown.safeUrl("mailto:"))
        assertNull(SupportMarkdown.safeUrl("javascript:alert(1)"))
        assertNull(SupportMarkdown.safeUrl("java\nscript:alert(1)"))
        assertNull(SupportMarkdown.safeUrl("file:///etc/passwd"))
        assertNull(SupportMarkdown.safeUrl("tel:123"))
        assertNull(SupportMarkdown.safeUrl("//example.com"))
        assertNull(SupportMarkdown.safeUrl(null))
    }

    @Test fun htmlIsNeverKept() {
        val text = SupportMarkdown.inline("Hi <script>alert('x')</script><b>there</b> <img src=x onerror=alert(1)> `<div>` <https://example.com>")
        assertFalse(text.text.contains("alert"))
        assertFalse(text.text.contains("<b>"))
        assertFalse(text.text.contains("onerror"))
        assertTrue(text.text.contains("there"))
        assertTrue("code spans keep their text", text.text.contains("<div>"))
        assertTrue("autolinks survive", text.text.contains("https://example.com"))
        assertEquals(listOf("https://example.com"), text.links)
        assertFalse(SupportMarkdown.inline("Before <script>steal()").text.contains("steal"))
    }

    @Test fun inlineStyles() {
        val text = SupportMarkdown.inline("**bold** *it* `code` ~~gone~~ __also__ _em_ snake_case_name * alone")
        assertEquals("bold it code gone also em snake_case_name * alone", text.text)
        val styles = text.spans.map { it.style to text.text.substring(it.start, it.end) }
        assertTrue(styles.contains(SupportInlineStyle.BOLD to "bold"))
        assertTrue(styles.contains(SupportInlineStyle.ITALIC to "it"))
        assertTrue(styles.contains(SupportInlineStyle.CODE to "code"))
        assertTrue(styles.contains(SupportInlineStyle.STRIKE to "gone"))
        assertTrue(styles.contains(SupportInlineStyle.BOLD to "also"))
        assertTrue(styles.contains(SupportInlineStyle.ITALIC to "em"))
        val nested = SupportMarkdown.inline("*see **this** [link](https://a.example)*")
        assertEquals("see this link", nested.text)
        assertTrue(nested.spans.any { it.style == SupportInlineStyle.BOLD && nested.text.substring(it.start, it.end) == "this" })
        assertEquals(listOf("https://a.example"), nested.links)
        assertEquals("a*b", SupportMarkdown.inline("a\\*b").text)
    }

    @Test fun blocks() {
        val md = """
            # Title #
            Intro line one
            continues here.

            ## Steps
            1. Open **Settings**
            2. Tap [Restore](https://example.com/restore)
               and wait
            - Top
              - Nested
            * [ ] todo

            > A quote
            > over two lines

            ```
            let x = "<b>"
            ```
            ---
            ![Screen](https://example.com/s.png "Title")
            ![Bad](javascript:alert(1))
            Setext
            ======
        """.trimIndent()
        val blocks = SupportMarkdown.blocks(md)
        assertEquals(blocks.toString(), 14, blocks.size)
        assertEquals(SupportMarkdownBlock.Heading(1, SupportRichText("Title")), blocks[0])
        assertEquals("Intro line one continues here.", (blocks[1] as SupportMarkdownBlock.Paragraph).text.text)
        assertEquals(2, (blocks[2] as SupportMarkdownBlock.Heading).level)
        (blocks[3] as SupportMarkdownBlock.ListItem).let { assertEquals(1, it.number); assertEquals(0, it.depth); assertEquals("Open Settings", it.text.text) }
        (blocks[4] as SupportMarkdownBlock.ListItem).let {
            assertEquals(2, it.number)
            assertEquals("indented continuation joins the item", "Tap Restore and wait", it.text.text)
            assertEquals(listOf("https://example.com/restore"), it.text.links)
        }
        (blocks[5] as SupportMarkdownBlock.ListItem).let { assertNull(it.number); assertEquals(0, it.depth) }
        (blocks[6] as SupportMarkdownBlock.ListItem).let { assertEquals(1, it.depth) }
        assertTrue((blocks[7] as SupportMarkdownBlock.ListItem).text.text.startsWith("☐"))
        assertEquals("A quote over two lines", (blocks[8] as SupportMarkdownBlock.Quote).text.text)
        assertEquals(SupportMarkdownBlock.Code("let x = \"<b>\""), blocks[9])
        assertEquals(SupportMarkdownBlock.Rule, blocks[10])
        assertEquals(SupportMarkdownBlock.Image("https://example.com/s.png", "Screen"), blocks[11])
        assertEquals("unsafe image becomes its alt text", "Bad", (blocks[12] as SupportMarkdownBlock.Paragraph).text.text)
        assertEquals(SupportMarkdownBlock.Heading(1, SupportRichText("Setext")), blocks[13])
    }

    @Test fun setextAndEmptyInput() {
        assertEquals(emptyList<SupportMarkdownBlock>(), SupportMarkdown.blocks(""))
        assertEquals(listOf(SupportMarkdownBlock.Heading(1, SupportRichText("Big"))), SupportMarkdown.blocks("Big\n==="))
        assertEquals(listOf(SupportMarkdownBlock.Heading(2, SupportRichText("Small"))), SupportMarkdown.blocks("Small\n---"))
        assertEquals(listOf(SupportMarkdownBlock.Rule), SupportMarkdown.blocks("***"))
        val hashtag = SupportMarkdown.blocks("#hashtag")
        assertEquals("no space: not a heading", 1, hashtag.size)
        assertTrue(hashtag[0] is SupportMarkdownBlock.Paragraph)
        assertEquals(listOf(SupportMarkdownBlock.Code("a\nb")), SupportMarkdown.blocks("```kotlin\na\nb"))
    }

    // MARK: - UI logic

    private fun article(slug: String, category: String?) = AppReferHelpArticle(slug, slug.replaceFirstChar { it.uppercase() }, "About $slug", category)

    @Test fun groupsByCategoryWithLooseArticlesLast() {
        val groups = SupportHelpLogic.groups(listOf(article("a", "Billing"), article("b", null), article("c", "Account"), article("d", "Billing")))
        assertEquals(listOf("Billing", "Account", "More articles"), groups.map { it.first })
        assertEquals(listOf("a", "d"), groups[0].second.map { it.slug })
        assertEquals("no heading when nothing has a category", listOf<String?>(null), SupportHelpLogic.groups(listOf(article("x", null))).map { it.first })
        assertTrue(SupportHelpLogic.groups(emptyList()).isEmpty())
    }

    @Test fun localSearchMatchesEveryWordIgnoringCaseAndAccents() {
        val list = listOf(AppReferHelpArticle("1", "Café refunds", "Money back"), AppReferHelpArticle("2", "Login", "Password reset"))
        assertEquals(listOf("1"), SupportHelpLogic.localMatches(list, "cafe MONEY").map { it.slug })
        assertEquals(listOf("2"), SupportHelpLogic.localMatches(list, "reset").map { it.slug })
        assertTrue(SupportHelpLogic.localMatches(list, "nothing").isEmpty())
    }

    @Test fun replyNoticeFollowsStatus() {
        fun status(push: Boolean, email: Boolean) = AppReferSupportStatus(
            AppReferSupportStatus.Push(AppReferSupportStatus.PushChannel(false, null), AppReferSupportStatus.PushChannel(push, if (push) true else null)),
            AppReferSupportStatus.Email(configured = email, receivesReplies = email), 0)
        assertEquals("unknown keeps the old line", SupportReplyNotice.PUSH, SupportReplyNotice.choose(null, hasPushToken = false, hasEmail = false))
        assertEquals(SupportReplyNotice.PUSH, SupportReplyNotice.choose(status(true, true), hasPushToken = true, hasEmail = true))
        assertEquals("the app never registered for push", SupportReplyNotice.EMAIL, SupportReplyNotice.choose(status(true, true), hasPushToken = false, hasEmail = true))
        assertEquals(SupportReplyNotice.EMAIL, SupportReplyNotice.choose(status(false, true), hasPushToken = true, hasEmail = true))
        assertEquals(SupportReplyNotice.IN_APP, SupportReplyNotice.choose(status(false, true), hasPushToken = true, hasEmail = false))
        assertEquals(SupportReplyNotice.IN_APP, SupportReplyNotice.choose(status(false, false), hasPushToken = false, hasEmail = true))
        assertEquals("Sent · we'll email you when the team replies", SupportReplyNotice.EMAIL.text(null))
        assertEquals("Sent 12:30 · the team's reply will appear here", SupportReplyNotice.IN_APP.text("12:30"))
    }

    // MARK: - Strings

    @Test fun newKeysAreInEveryLanguage() {
        val keys = listOf(
            "support.help.title", "support.help.search.placeholder", "support.help.search.noResults", "support.help.search.noResultsHint",
            "support.help.searching", "support.help.seeAll", "support.help.loading", "support.help.loadFailed", "support.help.listFailed",
            "support.help.updated", "support.help.draft", "support.help.otherCategory", "support.help.stillNeedHelp", "support.help.messageUs",
            "support.help.prompt", "support.help.openInBrowser", "support.help.a11y.clearSearch", "support.help.a11y.article",
            "support.status.sentNoticeEmail", "support.status.sentNoticeEmailNoTime", "support.status.sentNoticeInApp", "support.status.sentNoticeInAppNoTime",
        )
        assertEquals(35, SupportStrings.supportedLanguages.size)
        for (language in SupportStrings.supportedLanguages) {
            for (key in keys) assertFalse("$language: $key", SupportStrings.bundled(key, language).isNullOrEmpty())
            assertTrue(language, SupportStrings.bundled("support.help.search.noResults", language)!!.contains("{query}"))
            assertTrue(language, SupportStrings.bundled("support.help.updated", language)!!.contains("{date}"))
        }
        SupportStrings.forcedLanguage = "de"
        assertEquals("Keine Artikel zu „Abo“", SupportStrings.text("support.help.search.noResults", "query" to "Abo"))
    }

    // MARK: - API level

    @Test fun noApi24OnlyCollectionsInTheSdk() {
        // minSdk is 21: ConcurrentHashMap.newKeySet() and Map.putIfAbsent() are API 24+ and crash older devices.
        val roots = listOf(File("src/main"), File("apprefer/src/main"), File("../apprefer-billing/src/main"), File("apprefer-billing/src/main")).filter { it.isDirectory }
        assertTrue(roots.isNotEmpty())
        val offenders = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }.filter { file ->
            file.readLines().any { line -> !line.trim().startsWith("//") && (line.contains("newKeySet(") || line.contains(".putIfAbsent(")) }
        }
        assertEquals(emptyList<File>(), offenders)
    }
}
