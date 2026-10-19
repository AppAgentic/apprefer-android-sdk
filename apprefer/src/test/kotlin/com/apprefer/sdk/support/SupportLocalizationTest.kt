package com.apprefer.sdk.support

import com.apprefer.sdk.R
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SupportLocalizationTest {
    @Before fun setUp() = SupportStringsFixture.install()
    @After fun tearDown() = SupportStringsFixture.uninstall()

    private val fixture = SupportStringsFixture
    private fun table(language: String) = fixture.table(language)

    private fun placeholders(text: String): Set<String> = Regex("\\{(\\w+)\\}").findAll(text).map { it.groupValues[1] }.toSet()

    /** The shared table (Localization/support-strings.json). */
    private val shared: JSONObject by lazy {
        val file = listOf(File("../Localization/support-strings.json"), File("Localization/support-strings.json")).first { it.isFile }
        JSONObject(file.readText())
    }

    private val english get() = table("en")

    // MARK: - Catalogue

    @Test fun everyLanguageShipsAndEnglishHasTheWholeKeySet() {
        assertEquals(35, SupportStrings.supportedLanguages.size)
        for (language in SupportStrings.supportedLanguages) {
            val folder = fixture.folder(language)
            assertTrue("$language: values folder is missing", folder != null && File(folder, "apprefer_support_strings.xml").isFile)
        }
        val keys = shared.getJSONObject("keys").keys().asSequence().toList()
        // 78 chat keys + 22 help centre / status keys (0.10.0) + 7 lifecycle notification keys (0.11.0)
        // + 15 offer card keys (0.12.0) + 32 photo/video testimonial keys (0.13.0).
        assertEquals(170, keys.size)
        assertEquals("every key has a resource", keys.toSet(), SupportStringIds.text.keys + SupportStringIds.plurals.keys)
        assertEquals(keys.toSet(), english.strings.keys + english.plurals.keys)
        for (key in listOf("support.home.subtitle", "support.ai.tag", "support.error.offline", "support.notification.channel")) {
            assertNotNull(key, english.strings[key])
        }
        assertEquals(setOf("one", "other"), english.plurals["support.a11y.unread"]?.keys)
        // The generated id map points at real resources with the expected names.
        for ((key, id) in SupportStringIds.text) assertEquals(key, id, R.string::class.java.getField(fixture.resourceName(key)).getInt(null))
        for ((key, id) in SupportStringIds.plurals) assertEquals(key, id, R.plurals::class.java.getField(fixture.resourceName(key)).getInt(null))
    }

    @Test fun resourcesMatchTheSharedTableExactly() {
        // Generated from the shared JSON and unescaped back to the same text, in every language.
        val keys = shared.getJSONObject("keys")
        val translations = shared.getJSONObject("translations")
        for (language in SupportStrings.supportedLanguages) {
            val source = if (language == "en") null else translations.getJSONObject(language)
            for (key in keys.keys()) {
                val value = source?.get(key) ?: keys.getJSONObject(key).get("en")
                if (value is JSONObject) {
                    val forms = value.keys().asSequence().associateWith { value.getString(it) }
                    assertEquals("$language $key", forms, table(language).plurals[key])
                } else {
                    assertEquals("$language $key", value, table(language).strings[key])
                }
            }
        }
        assertEquals("apostrophes survive escaping", "Sent · we'll notify you when the team replies", english.strings["support.thread.sentNoticeNoTime"])
        assertEquals("Billing & refunds", english.strings["support.topic.billing.title"])
    }

    @Test fun everyKeyIsTranslatedInEveryLanguageWithItsPlaceholders() {
        val counts = (0..230).toList() + listOf(1_000, 1_001, 1_000_000, 2_000_000)
        for (language in SupportStrings.supportedLanguages) {
            val t = table(language)
            assertFalse("$language has no table", t.strings.isEmpty())
            for ((key, source) in english.strings) {
                val text = t.strings[key]
                assertNotNull("$language: missing $key", text)
                assertTrue("$language: empty $key", text!!.isNotBlank())
                assertEquals("$language: placeholders of $key", placeholders(source), placeholders(text))
            }
            for (key in english.plurals.keys) {
                val forms = t.plurals[key] ?: error("$language: missing plural $key")
                assertNotNull("$language: $key has no other form", forms["other"])
                val needed = counts.map { SupportStrings.cldrPluralCategory(it, language) }.toSet()
                for (category in needed) {
                    val text = forms[category] ?: error("$language: missing $key#$category")
                    assertEquals("$language: placeholders of $key#$category", setOf("count"), placeholders(text))
                }
                for ((category, text) in forms) assertTrue("$language: empty $key#$category", text.isNotBlank())
            }
            // Nothing stray: every resource belongs to the English set.
            for (key in t.strings.keys + t.plurals.keys) assertTrue("$language: unknown key $key", key in english.strings || key in english.plurals)
        }
    }

    @Test fun noTranslationNamesAnAIProvider() {
        val banned = listOf("openai", "chatgpt", "gpt", "anthropic", "claude", "gemini", "llama", "qwen", "mistral")
        for (language in SupportStrings.supportedLanguages) {
            val t = table(language)
            val all = t.strings.entries.map { it.key to it.value } + t.plurals.flatMap { (k, forms) -> forms.values.map { k to it } }
            for ((key, text) in all) assertFalse("$language: $key names a provider", banned.any { text.lowercase().contains(it) })
        }
    }

    // MARK: - Lookup

    @Test fun forcedLanguageReadsItsTableAndFillsPlaceholders() {
        AppReferSupport.language = "de"
        assertEquals("de", SupportStrings.language)
        assertEquals(table("de").strings["support.home.subtitle"], SupportStrings.text("support.home.subtitle"))
        assertNotEquals("How can we help today?", SupportStrings.text("support.home.subtitle"))
        val title = SupportStrings.text("support.home.title", "app" to "Sparkly")
        assertTrue(title.contains("Sparkly")); assertFalse(title.contains("{app}"))
        AppReferSupport.language = "pt-BR"
        assertEquals("pt-BR", SupportStrings.language)
        assertEquals(table("pt-BR").strings["support.ai.tag"], SupportStrings.text("support.ai.tag"))
        AppReferSupport.language = "es-419"
        assertEquals("es-MX", SupportStrings.language)
        AppReferSupport.language = null
        assertEquals("en", SupportStrings.language)
    }

    @Test fun unknownLanguageFallsBackToEnglish() {
        assertEquals("en", SupportStrings.resolve(emptyList(), listOf("xx-YY", "tlh")))
        AppReferSupport.language = "xx"
        assertEquals("en", SupportStrings.language)
        assertEquals("How can we help today?", SupportStrings.text("support.home.subtitle"))
        assertEquals("1 unread message", SupportStrings.plural("support.a11y.unread", 1))
        assertEquals("3 unread messages", SupportStrings.plural("support.a11y.unread", 3))
        // A key the table doesn't have shows the key, never an empty label.
        assertEquals("support.not.a.key", SupportStrings.text("support.not.a.key"))
    }

    @Test fun missingTranslationFallsBackToEnglish() {
        assertNull(SupportStrings.bundled("support.home.subtitle", "xx"))
        // A language whose table lacks a key: the chain ends in English.
        SupportStrings.testSource = object : SupportStrings.Source {
            override fun text(key: String, language: String) = if (language == "fr" && key == "support.home.subtitle") null else fixture.text(key, language)
            override fun plural(key: String, language: String, count: Int) = if (language == "fr") null else fixture.plural(key, language, count)
        }
        AppReferSupport.language = "fr"
        assertEquals("How can we help today?", SupportStrings.text("support.home.subtitle"))
        assertEquals(table("fr").strings["support.home.continue"], SupportStrings.text("support.home.continue"))
        assertEquals("2 unread messages", SupportStrings.plural("support.a11y.unread", 2))
    }

    @Test fun overrideWinsInEveryLanguage() {
        AppReferSupport.strings = mapOf(
            "support.home.subtitle" to "What's up, {name}?",
            "support.a11y.unread#one" to "One new reply",
            "support.thread.sending" to "",
        )
        for (language in listOf("en", "de", "ar", "ja")) {
            AppReferSupport.language = language
            assertEquals("What's up, Jay?", SupportStrings.text("support.home.subtitle", "name" to "Jay"))
            // A form override applies where the language uses that form (Japanese has only "other").
            val expected = if (language == "ja") SupportStrings.fill(table("ja").plurals["support.a11y.unread"]!!.getValue("other"), mapOf("count" to "1")) else "One new reply"
            assertEquals(language, expected, SupportStrings.plural("support.a11y.unread", 1))
        }
        AppReferSupport.language = "en"
        assertEquals("other forms keep the catalogue", "2 unread messages", SupportStrings.plural("support.a11y.unread", 2))
        assertEquals("an override may blank a string", "", SupportStrings.text("support.thread.sending"))
        // A plain key overrides every plural form.
        AppReferSupport.strings = mapOf("support.a11y.unread" to "{count} new")
        assertEquals("5 new", SupportStrings.plural("support.a11y.unread", 5))
        // The map is copied: changing the caller's map later changes nothing.
        val mutable = mutableMapOf("support.home.subtitle" to "A")
        AppReferSupport.strings = mutable
        mutable["support.home.subtitle"] = "B"
        assertEquals("A", SupportStrings.text("support.home.subtitle"))
    }

    @Test fun placeholdersFillByNameInAnyOrderOnce() {
        assertEquals("1h · Jay", SupportStrings.fill("{time} · {name}", mapOf("name" to "Jay", "time" to "1h")))
        assertEquals("Hi Jay {unknown}", SupportStrings.fill("Hi {name} {unknown}", mapOf("name" to "Jay")))
        assertEquals("values are never expanded again", "Hi {time}", SupportStrings.fill("Hi {name}", mapOf("name" to "{time}", "time" to "x")))
        assertEquals("{ a}", SupportStrings.fill("{ {name}}", mapOf("name" to "a")))
        assertEquals("{name", SupportStrings.fill("{name", mapOf("name" to "a")))
        assertEquals("no arguments leaves braces", "{x}", SupportStrings.fill("{x}", emptyMap()))
    }

    // MARK: - Language choice

    @Test fun theAppsLanguageWinsSoTheChatMatchesTheApp() {
        // An English-only app on a German phone: the chat stays English like the app.
        assertEquals("en", SupportStrings.resolve(listOf("en"), listOf("de-DE", "en")))
        // An app running in German.
        assertEquals("de", SupportStrings.resolve(listOf("de"), listOf("de-DE")))
        // No app languages: the device's order.
        assertEquals("fr", SupportStrings.resolve(emptyList(), listOf("fr-CA", "en")))
        // An app language the SDK doesn't ship falls through to the device.
        assertEquals("it", SupportStrings.resolve(listOf("fil"), listOf("tl", "it-IT")))

        // What the app shows: its own locale list, limited to the languages it declares (localeConfig).
        assertEquals("no declaration: the app's list", listOf("de-DE", "en"), SupportStrings.appLanguages(listOf("de-DE", "en"), null))
        assertEquals(listOf("en"), SupportStrings.appLanguages(listOf("de-DE", "en-GB"), listOf("en", "fr")).map { it.substringBefore('-') })
        assertEquals("nothing declared matches: the app's English default", listOf("en"), SupportStrings.appLanguages(listOf("de-DE"), listOf("fr", "en")))
        assertEquals("no English declared: its first language", listOf("fr"), SupportStrings.appLanguages(listOf("de-DE"), listOf("fr", "it")))
        assertEquals(listOf("pt-PT"), SupportStrings.appLanguages(listOf("pt-PT"), listOf("pt-BR", "en")))
        assertEquals("Chinese by script", listOf("en"), SupportStrings.appLanguages(listOf("zh-TW"), listOf("zh-Hans", "en")))
        assertEquals(listOf("zh-Hant-TW"), SupportStrings.appLanguages(listOf("zh-Hant-TW"), listOf("zh-Hant", "en")))
        assertEquals("legacy Hebrew code", listOf("iw-IL"), SupportStrings.appLanguages(listOf("iw-IL"), listOf("he", "en")))
        // End to end: an English-only app (declares en) on a German phone gets an English chat.
        SupportStrings.testLanguages = SupportStrings.appLanguages(listOf("de-DE"), listOf("en")) to listOf("de-DE")
        assertEquals("en", SupportStrings.language)
        assertEquals("How can we help today?", SupportStrings.text("support.home.subtitle"))
        // An app with an in-app language picker set to French on an English phone.
        SupportStrings.testLanguages = SupportStrings.appLanguages(listOf("fr-FR"), null) to listOf("en-US")
        assertEquals("fr", SupportStrings.language)
        // Forcing wins over both.
        AppReferSupport.language = "ja"
        assertEquals("ja", SupportStrings.language)
    }

    @Test fun regionalVariantsMatchTheRightTable() {
        val cases = listOf(
            "es" to "es", "es-ES" to "es", "es-MX" to "es-MX", "es-419" to "es-MX", "es-US" to "es-MX", "es_AR" to "es-MX",
            "pt" to "pt-BR", "pt-BR" to "pt-BR", "pt-PT" to "pt-PT", "pt-AO" to "pt-PT",
            "zh-Hans" to "zh-Hans", "zh-Hans-CN" to "zh-Hans", "zh" to "zh-Hans", "zh-CN" to "zh-Hans", "zh-SG" to "zh-Hans",
            "zh-Hant" to "zh-Hant", "zh-Hant-TW" to "zh-Hant", "zh-TW" to "zh-Hant", "zh-HK" to "zh-Hant", "zh-Hant-HK" to "zh-Hant", "zh_TW" to "zh-Hant",
            "nb" to "nb", "no" to "nb", "nn-NO" to "nb", "nb-NO" to "nb",
            "iw" to "he", "iw-IL" to "he", "he-IL" to "he", "in" to "id", "in-ID" to "id", "ms-MY" to "ms",
            "en-GB" to "en", "de-AT" to "de", "fr-CH" to "fr", "sr" to "en",
        )
        for ((preference, expected) in cases) assertEquals(preference, expected, SupportStrings.match(listOf(preference)) ?: "en")
        for (language in SupportStrings.supportedLanguages) assertEquals("every shipped table matches itself", language, SupportStrings.match(listOf(language)))
    }

    @Test fun rightToLeftLanguages() {
        AppReferSupport.language = "ar"; assertTrue(SupportStrings.isRightToLeft)
        AppReferSupport.language = "he"; assertTrue(SupportStrings.isRightToLeft)
        AppReferSupport.language = "iw"; assertTrue(SupportStrings.isRightToLeft)
        AppReferSupport.language = "de"; assertFalse(SupportStrings.isRightToLeft)
        assertEquals("ar", SupportStrings.localeOf("ar").language)
        assertEquals("Hant", SupportStrings.localeOf("zh-Hant").script)
        assertEquals("MX", SupportStrings.localeOf("es-MX").country)
    }

    // MARK: - Plurals

    @Test fun pluralCategoriesFollowCLDR() {
        fun check(language: String, expected: Map<Int, String>) {
            for ((n, category) in expected) {
                assertEquals("$language $n", category, SupportStrings.cldrPluralCategory(n, language))
                // On the JVM (no ICU) the SDK uses its own rules.
                assertEquals("$language $n", category, SupportStrings.pluralCategory(n, language))
            }
        }
        check("en", mapOf(0 to "other", 1 to "one", 2 to "other"))
        check("ja", mapOf(0 to "other", 1 to "other", 5 to "other"))
        check("fr", mapOf(0 to "one", 1 to "one", 2 to "other", 1_000_000 to "many"))
        check("pt-BR", mapOf(0 to "one", 1 to "one", 2 to "other"))
        check("pt-PT", mapOf(0 to "other", 1 to "one", 2 to "other", 1_000_000 to "many"))
        check("es-MX", mapOf(0 to "other", 1 to "one", 1_000_000 to "many"))
        check("hi", mapOf(0 to "one", 1 to "one", 2 to "other"))
        check("ru", mapOf(1 to "one", 2 to "few", 4 to "few", 5 to "many", 11 to "many", 12 to "many", 21 to "one", 22 to "few", 25 to "many", 111 to "many"))
        check("uk", mapOf(1 to "one", 3 to "few", 0 to "many"))
        check("pl", mapOf(1 to "one", 2 to "few", 5 to "many", 21 to "many", 22 to "few", 0 to "many"))
        check("cs", mapOf(1 to "one", 2 to "few", 4 to "few", 5 to "other", 0 to "other"))
        check("sk", mapOf(1 to "one", 3 to "few", 22 to "other"))
        check("hr", mapOf(1 to "one", 21 to "one", 11 to "other", 2 to "few", 24 to "few", 5 to "other"))
        check("ro", mapOf(0 to "few", 1 to "one", 2 to "few", 19 to "few", 20 to "other", 101 to "few", 120 to "other"))
        check("ar", mapOf(0 to "zero", 1 to "one", 2 to "two", 3 to "few", 10 to "few", 11 to "many", 99 to "many", 100 to "other", 103 to "few"))
        check("he", mapOf(1 to "one", 2 to "two", 3 to "other", 20 to "other"))
    }

    @Test fun pluralUsesTheLanguagesForm() {
        AppReferSupport.language = "ru"
        val forms = table("ru").plurals.getValue("support.a11y.unread")
        assertEquals(SupportStrings.fill(forms.getValue("one"), mapOf("count" to "1")), SupportStrings.plural("support.a11y.unread", 1))
        assertEquals(SupportStrings.fill(forms.getValue("few"), mapOf("count" to "3")), SupportStrings.plural("support.a11y.unread", 3))
        assertEquals(SupportStrings.fill(forms.getValue("many"), mapOf("count" to "7")), SupportStrings.plural("support.a11y.unread", 7))
        AppReferSupport.language = "ar"
        val arabic = table("ar").plurals.getValue("support.a11y.unread")
        assertTrue(SupportStrings.plural("support.a11y.unread", 2).contains(arabic.getValue("two").replace("{count}", "").trim()))
    }

    // MARK: - Errors

    @Test fun errorsShowLocalizedTextNotServerMessages() {
        AppReferSupport.language = "en"
        assertEquals(SupportStrings.text("support.error.rateLimited"), SupportStrings.errorText(AppReferSupportException(429, "rate_limited")))
        assertEquals(SupportStrings.text("support.error.tooLong"), SupportStrings.errorText(AppReferSupportException(413, "too_large")))
        assertEquals(SupportStrings.text("support.error.generic"), SupportStrings.errorText(AppReferSupportException(500, "boom")))
        assertEquals(SupportStrings.text("support.error.notSent"), SupportStrings.errorText(AppReferSupportException(500, "boom"), "support.error.notSent"))
        assertEquals(SupportStrings.text("support.error.upload"), SupportStrings.errorText(AppReferSupportException(502, "x", AppReferSupportException.Reason.UPLOAD)))
        assertEquals(SupportStrings.text("support.error.unavailable"), SupportStrings.errorText(AppReferSupportException(0, "x", AppReferSupportException.Reason.UNAVAILABLE)))
        assertEquals(SupportStrings.text("support.error.offline"), SupportStrings.errorText(UnknownHostException("apprefer.com")))
        assertEquals(SupportStrings.text("support.error.offline"), SupportStrings.errorText(IOException("wrapped", SocketTimeoutException())))
        assertEquals(SupportStrings.text("support.error.loadFailed"), SupportStrings.errorText(IllegalStateException("boom"), "support.error.loadFailed"))
        assertEquals(SupportStrings.text("support.error.generic"), SupportStrings.errorText(IOException("disk")))
        AppReferSupport.language = "de"
        assertEquals(table("de").strings["support.error.offline"], SupportStrings.errorText(SocketTimeoutException()))
        // The raw message stays available for logs.
        assertEquals("rate_limited", AppReferSupportException(429, "rate_limited").message)
    }

    @Test fun apiErrorsCarryTheirReason() = kotlinx.coroutines.runBlocking {
        val store = object : SupportStore { override var token: String? = "s1"; override var profile: String? = "{}" }
        val unconfigured = SupportAPI({ null }, store, { emptyMap() }, { emptyMap() }, SupportTransport { _, _, _, _ -> SupportResponse(200, "{}") })
        val e = runCatching { unconfigured.conversations() }.exceptionOrNull()
        assertTrue(e is AppReferSupportException)
        assertEquals(SupportStrings.text("support.error.unavailable"), SupportStrings.errorText(e!!))
        val replies = java.util.ArrayDeque(listOf(
            SupportResponse(200, """{"attachmentId":"a1","uploadUrl":"https://r2.example/x","headers":{}}"""),
            SupportResponse(403, ""),
        ))
        val uploads = SupportAPI({ "pk_test" }, store, { mapOf() }, { emptyMap() }, SupportTransport { _, _, _, _ -> replies.removeFirst() })
        val failed = runCatching { uploads.upload(ByteArray(3)) }.exceptionOrNull()
        assertEquals(SupportStrings.text("support.error.upload"), SupportStrings.errorText(failed!!))
    }

    // MARK: - Configuration

    @Test fun builtInTopicsFollowTheChatLanguageAndCustomOnesStayAsWritten() {
        assertEquals(listOf("billing", "restore", "bug", "idea"), AppReferSupportTopic.defaults.map { it.id })
        assertEquals(listOf("Billing & refunds", "Restore purchase", "Report a bug", "Suggest an idea"), AppReferSupportTopic.defaults.map { it.title })
        AppReferSupport.language = "de"
        val de = table("de").strings
        assertEquals(de["support.topic.billing.title"], AppReferSupportTopic.billing.title)
        assertEquals(de["support.topic.bug.subtitle"], AppReferSupportTopic.bug.subtitle)
        assertEquals(de["support.topic.idea.prompt"], AppReferSupportTopic.idea.prompt)
        assertEquals("billing", AppReferSupportTopic.billing.id)
        // Your own text is shown as written in every language.
        val custom = AppReferSupportTopic("Order help", "Where's my order?", 0, 0, "Order number?")
        assertEquals("Order help", custom.title); assertEquals("Order help", custom.id); assertEquals("Order number?", custom.prompt)
        // A copy keeps following the language for the text you leave out.
        val renamed = AppReferSupportTopic.billing.copy(title = "Payments")
        assertEquals("Payments", renamed.title)
        assertEquals(de["support.topic.billing.subtitle"], renamed.subtitle)
        assertEquals("billing", renamed.id)
        assertEquals(AppReferSupportTopic.billing, AppReferSupportTopic.billing.copy())
        // Overrides reach the built-in tiles too.
        AppReferSupport.strings = mapOf("support.topic.restore.title" to "Get Pro back")
        assertEquals("Get Pro back", AppReferSupportTopic.restore.title)
    }

    @Test fun replyTimeDefaultsToTheChatLanguageAndAnAppValueIsShownAsWritten() {
        val saved = AppReferSupport.replyTime
        try {
            AppReferSupport.language = "fr"
            assertEquals(table("fr").strings["support.replyTime.default"], AppReferSupport.replyTime)
            assertEquals(SupportStrings.text("support.presence.team", "time" to AppReferSupport.replyTime), SupportText.presence(emptyList(), AppReferSupport.replyTime))
            AppReferSupport.replyTime = "a few hours"
            assertEquals("a few hours", AppReferSupport.replyTime)
            AppReferSupport.language = "ja"
            assertEquals("a few hours", AppReferSupport.replyTime)
            assertTrue(SupportText.handoff("Jay", AppReferSupport.replyTime).contains("a few hours"))
        } finally {
            AppReferSupport.replyTime = saved
        }
    }

    @Test fun chatTextFollowsTheLanguage() {
        AppReferSupport.language = "es-MX"
        val es = table("es-MX").strings
        assertEquals(es["support.home.greeting"], SupportText.greeting(null))
        assertEquals(SupportStrings.fill(es.getValue("support.home.greeting.named"), mapOf("name" to "María")), SupportText.greeting("María López"))
        assertEquals(SupportStrings.fill(es.getValue("support.composer.placeholder.persona"), mapOf("name" to "Sam")), SupportText.homeHint(null, "Sam"))
        assertEquals(es["support.composer.placeholder.home"], SupportText.homeHint(null, null))
        assertEquals(SupportStrings.fill(es.getValue("support.thread.handoff.team"), mapOf("time" to "1h")), SupportText.handoff(null, "1h"))
        assertEquals(SupportStrings.fill(es.getValue("support.presence.many"), mapOf("name" to "Jay", "time" to "1h")), SupportText.presence(listOf("Jay", "Ana"), "1h"))
        assertEquals(es["support.conversation.defaultSubject"], AppReferSupportConversation(mapOf("id" to "c")).subject)
    }
}
