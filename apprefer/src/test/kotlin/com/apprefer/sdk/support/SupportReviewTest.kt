package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Review cards (0.12.0): model, link check, copy, receipts and the API routes. */
class SupportReviewTest {
    @Before fun strings() { SupportStringsFixture.install(); SupportStrings.forcedLanguage = "en" }
    @After fun reset() { SupportStrings.forcedLanguage = null; SupportStringsFixture.uninstall() }

    private val json = """{"cardId":"rvc_0123456789abcdef01234567","status":"available","reason":null,"store":"play_store","url":"https://play.google.com/store/apps/details?id=com.sparkly.app"}"""
    private fun card(j: String = json) = AppReferSupportReviewCard.from(JsonCodec.decodeObject(j))!!
    private class Memory : SupportOfferReceiptBacking { override var raw: String? = null }

    @Test fun decodesReviewCardsAndOldPayloadsWithoutThem() {
        val m = AppReferSupportMessage(JsonCodec.decodeObject("""{"id":"m1","from":"team","authorName":"Jay","text":"Thanks!","createdAt":"2026-10-19T12:00:00.000Z","attachments":[],"review":$json}"""))
        assertEquals("rvc_0123456789abcdef01234567", m.review!!.cardId)
        assertNull(m.offer)
        assertNull(AppReferSupportMessage(JsonCodec.decodeObject("""{"id":"m2","from":"team","text":"Hi","createdAt":"x","attachments":[]}""")).review)
        assertNull(AppReferSupportReviewCard.from(mapOf("cardId" to "rvc_x")))
    }

    @Test fun onlyOneAppsGooglePlayListingIsEverOpened() {
        assertEquals("https://play.google.com/store/apps/details?id=com.sparkly.app", SupportReviewLink.validated("https://play.google.com/store/apps/details?id=com.sparkly.app"))
        listOf(
            "http://play.google.com/store/apps/details?id=com.sparkly.app",
            "https://play.google.com.evil.example/store/apps/details?id=com.sparkly.app",
            "https://evil.example/store/apps/details?id=com.sparkly.app",
            "https://play.google.com/store/apps/details?id=com.sparkly.app&url=https://evil.example",
            "https://play.google.com/store/apps/details?id=javascript:alert(1)",
            "https://play.google.com/store/apps/developer?id=Sparkly",
            "https://user@play.google.com/store/apps/details?id=com.sparkly.app",
            "https://play.google.com:8443/store/apps/details?id=com.sparkly.app",
            "https://play.google.com/store/apps/details?id=com.sparkly.app#x",
            "market://details?id=com.sparkly.app",
            "https://apps.apple.com/app/id1234567890?action=write-review", "",
        ).forEach { assertNull(it, SupportReviewLink.validated(it)) }
        assertEquals(SupportReviewLook.Unsupported, SupportReviewLook.of(card(json.replace("https://play.google.com", "https://evil.example"))))
    }

    @Test fun lookFollowsServerState() {
        assertEquals(SupportReviewLook.Open("https://play.google.com/store/apps/details?id=com.sparkly.app"), SupportReviewLook.of(card()))
        assertEquals(SupportReviewLook.Unavailable, SupportReviewLook.of(card("""{"cardId":"rvc_1","status":"unavailable","reason":"revoked","store":null,"url":null}""")))
        assertEquals(SupportReviewLook.Unsupported, SupportReviewLook.of(card("""{"cardId":"rvc_1","status":"unavailable","reason":"not_on_platform","store":null,"url":null}""")))
    }

    @Test fun neutralCopyNamesTheStoreInEveryLanguage() {
        assertEquals("Share your experience", SupportStrings.text("support.review.title"))
        assertEquals("If you'd like, you can write a review on Google Play.", SupportStrings.text("support.review.body.googlePlay"))
        assertEquals("Opens Google Play", SupportStrings.text("support.review.a11y.hint.googlePlay"))
        assertEquals("googlePlay", SupportReviewLink.storeKey("play_store"))
        assertEquals("Write a review", SupportStrings.text("support.review.open"))
        assertFalse(SupportStrings.text("support.review.title").lowercase().contains("star"))
        for (lang in listOf("ar", "de", "ja", "pt-BR", "zh-Hant")) {
            SupportStrings.forcedLanguage = lang
            assertTrue(lang, SupportStrings.text("support.review.body.googlePlay").contains("Google Play"))
            assertNotEquals(lang, "Write a review", SupportStrings.text("support.review.open"))
        }
    }

    @Test fun shownIdIsStableAndValid() {
        val id = SupportOfferReceipt.shownId("rvc_0123456789abcdef01234567")
        assertEquals(id, SupportOfferReceipt.shownId("rvc_0123456789abcdef01234567"))
        assertTrue(Regex("^[A-Za-z0-9_-]{8,64}$").matches(id))
        assertEquals("shown_abc", SupportOfferReceipt.shownId("ofc_abc"))
    }

    @Test fun receiptsAndRefreshUseTheReviewRoutes() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val api = SupportAPI({ "pk_live_key" }, object : SupportStore { override var token: String? = null; override var profile: String? = null },
            { mapOf("appUserId" to "user_42") }, { emptyMap() }, SupportTransport { _, url, headers, _ ->
                requests.add(url to headers)
                when {
                    url.endsWith("/session") -> SupportResponse(201, """{"session":"s1","created":true}""")
                    url.contains("/reviews?") -> SupportResponse(200, """{"reviews":[$json,null]}""")
                    else -> SupportResponse(201, """{"eventId":"rve_x","duplicate":false,"review":$json}""")
                }
            }, identityCleared = { false })
        assertEquals(1, api.reviewCards("cv1").size)
        val q = SupportOfferReceipts(Memory())
        q.enqueue(SupportOfferReceipt("cv1", "rvc_0123456789abcdef01234567", "opened", "open_rvc00001", SupportOfferReceipt.iso(System.currentTimeMillis())))
        api.flushOfferReceipts(q)
        assertTrue(q.pending.isEmpty())
        val calls = requests.filterNot { it.first.endsWith("/session") }
        assertTrue(calls[0].first.endsWith("/conversations/cv1/reviews?platform=android"))
        assertTrue(calls[0].second["X-AppRefer-Support-Features"]!!.contains("reviews"))
        assertTrue(calls[1].first.endsWith("/conversations/cv1/reviews/rvc_0123456789abcdef01234567/events?platform=android"))
    }

    @Test fun openerRefusalNeverOpensAndOfflineKeepsTheReceipt() = runBlocking {
        val q = SupportOfferReceipts(Memory())
        val opener = SupportOfferOpener(q, 2_000, send = { throw AppReferSupportException(409, "This review link can't be opened (revoked).") })
        assertEquals(SupportOfferOpenCheck.Refused, opener.check(opener.receipt("rvc_1", "cv1")))
        opener.send = { throw java.io.IOException("offline") }
        assertEquals(SupportOfferOpenCheck.Offline, opener.check(opener.receipt("rvc_1", "cv1")))
        assertEquals(listOf("rvc_1"), q.pending.map { it.cardId })
        q.clear()
        assertTrue(q.pending.isEmpty())
    }
}
