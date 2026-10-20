package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Offer cards (0.12.0): model, receipts, opening, API and the onOffer callback. */
class SupportOfferTest {
    @Before fun strings() { SupportStringsFixture.install(); SupportStrings.forcedLanguage = "en" }
    @After fun reset() { SupportStrings.forcedLanguage = null; SupportStringsFixture.uninstall(); AppReferSupport.onOffer = null }

    private val cardJson = """{"cardId":"ofc_0123456789abcdef01234567","offeringId":"main","packageId":null,"status":"available","reason":null,"eligibility":"checked",
        "packages":[{"id":"${'$'}ar_annual","packageType":"annual","displayName":null,"productId":"pro:annual","current":false},
                    {"id":"${'$'}ar_monthly","packageType":"monthly","displayName":"Monthly Pro","productId":"pro:monthly","current":true}]}"""
    private fun card(json: String = cardJson) = AppReferSupportOfferCard.from(JsonCodec.decodeObject(json))!!
    private fun withStatus(status: String) = card(cardJson.replace("\"status\":\"available\"", "\"status\":\"$status\""))
    private class Memory : SupportOfferReceiptBacking { override var raw: String? = null }

    @Test fun decodesCardsAndOldPayloadsWithoutThem() {
        val m = AppReferSupportMessage(JsonCodec.decodeObject("""{"id":"m1","from":"team","authorName":"Jay","text":"","createdAt":"2026-10-19T12:00:00.000Z","attachments":[],"offer":$cardJson}"""))
        val offer = m.offer!!
        assertEquals("main", offer.offeringId)
        assertTrue(offer.isAvailable)
        assertEquals(listOf(false, true), offer.packages.map { it.current })
        assertNull(AppReferSupportMessage(JsonCodec.decodeObject("""{"id":"m2","from":"team","text":"Hi","createdAt":"x","attachments":[]}""")).offer)
        // Something that isn't a card is no card at all (never a broken one).
        assertNull(AppReferSupportOfferCard.from(mapOf("cardId" to "ofc_x")))
        assertNull(AppReferSupportOfferCard.from("offer"))
    }

    @Test fun lookFollowsServerStateAndHandler() {
        assertEquals(SupportOfferLook.OPEN, SupportOfferLook.of(card(), true))
        assertEquals(SupportOfferLook.UNSUPPORTED, SupportOfferLook.of(card(), false))
        assertEquals("Update the app to open this", SupportOfferLook.UNSUPPORTED.notice)
        assertEquals(SupportOfferLook.OWNED, SupportOfferLook.of(withStatus("owned"), true))
        assertEquals(SupportOfferLook.UNAVAILABLE, SupportOfferLook.of(withStatus("unavailable"), true))
        assertEquals("These options are no longer available", SupportOfferLook.UNAVAILABLE.notice)
        assertEquals(SupportOfferLook.UNAVAILABLE, SupportOfferLook.of(withStatus("paused"), true))
    }

    @Test fun packageLabelsNeverInventAName() {
        val c = card()
        assertEquals("Yearly", SupportOfferText.label(c.packages[0], "Pro Yearly"))
        assertEquals("Monthly Pro", SupportOfferText.label(c.packages[1], null))
        val custom = AppReferSupportOfferCard.Package("gold", "custom", null, "p", false)
        assertEquals("Gold Pack", SupportOfferText.label(custom, "Gold Pack"))
        assertEquals("Option", SupportOfferText.label(custom, null))
        SupportStrings.forcedLanguage = "de"
        assertEquals("Jährlich", SupportOfferText.label(c.packages[0], null))
        assertEquals("Optionen ansehen", SupportStrings.text("support.offer.open"))
        assertEquals(listOf("${'$'}ar_annual"), card(cardJson.replace("\"packageId\":null", "\"packageId\":\"${'$'}ar_annual\"")).shownPackages.map { it.id })
    }

    /** Ordinary full-price cards never read like a deal, in any language. */
    @Test fun cardCopyIsNeutralInEveryLanguage() {
        val deal = listOf("offer", "angebot", "offre", "oferta", "offerta", "deal", "discount", "rabatt", "скидк", "предложени", "オファー", "優惠", "优惠", "折扣", "할인", "割引")
        for (language in listOf("en", "de", "fr", "es", "it", "pt-BR", "nl", "ru", "ja", "ko", "zh-Hans", "zh-Hant")) {
            SupportStrings.forcedLanguage = language
            for (key in listOf("support.offer.title", "support.offer.open", "support.offer.unavailable", "support.offer.unsupported", "support.offer.a11y.hint")) {
                val text = SupportStrings.text(key).lowercase()
                assertTrue("$language $key: $text", deal.none { text.contains(it) })
            }
        }
    }

    /** The price carries the store's own billing period; one-time purchases and unusual periods show the bare store price. */
    @Test fun priceShowsTheStoresOwnPeriod() {
        assertEquals("£29.99/year", SupportOfferText.price(SupportOfferPrice("£29.99", null, "P1Y")))
        assertEquals("£4.99/month", SupportOfferText.price(SupportOfferPrice("£4.99", null, "P1M")))
        assertEquals("£2.99/week", SupportOfferText.price(SupportOfferPrice("£2.99", null, "P1W")))
        assertEquals("£9.99 every 3 months", SupportOfferText.price(SupportOfferPrice("£9.99", null, "P3M")))
        assertEquals("£9.99 every 4 weeks", SupportOfferText.price(SupportOfferPrice("£9.99", null, "P4W")))
        assertEquals("£4.99", SupportOfferText.price(SupportOfferPrice("£4.99", null, null, oneTime = true)))
        assertEquals("£1.99", SupportOfferText.price(SupportOfferPrice("£1.99", null, "P3D")))
        assertEquals("£1.99", SupportOfferText.price(SupportOfferPrice("£1.99", null, "nonsense")))
        SupportStrings.forcedLanguage = "de"
        assertEquals("29,99 €/Jahr", SupportOfferText.price(SupportOfferPrice("29,99 €", null, "P1Y")))
    }

    /** An owned subscription is the current plan; an owned one-off (credit pack, lifetime) is purchased. */
    @Test fun ownedTagFitsWhatWasBought() {
        val sub = AppReferSupportOfferCard.Package("${'$'}ar_annual", "annual", null, "p", true)
        assertEquals("Current plan", SupportOfferText.ownedTag(sub, SupportOfferPrice("£29.99", null, "P1Y")))
        assertEquals("Current plan", SupportOfferText.ownedTag(sub, null))
        assertEquals("Purchased", SupportOfferText.ownedTag(AppReferSupportOfferCard.Package("${'$'}ar_lifetime", "lifetime", null, "l", true), null))
        assertEquals("Purchased", SupportOfferText.ownedTag(AppReferSupportOfferCard.Package("gold", "custom", null, "g", true), SupportOfferPrice("£4.99", null, null, true)))
        assertNull(SupportOfferText.ownedTag(AppReferSupportOfferCard.Package("m", "monthly", null, "m", false), null))
    }

    @Test fun receiptQueuePersistsDedupesAndDropsOldOrRetriedTooOften() {
        val backing = Memory()
        var now = 1_760_000_000_000L
        val q = SupportOfferReceipts(backing) { now }
        val a = SupportOfferReceipt("cv1", "ofc_a", "shown", "shown_a", SupportOfferReceipt.iso(now))
        q.enqueue(a); q.enqueue(a)
        assertEquals(listOf(a), SupportOfferReceipts(backing) { now }.pending)
        q.enqueue(SupportOfferReceipt("cv1", "ofc_b", "opened", "open_old", SupportOfferReceipt.iso(now - 8 * 86_400_000L)))
        assertEquals(listOf(a), q.pending)
        repeat(SupportOfferReceipts.MAX_ATTEMPTS - 1) { q.failed(a) }
        assertEquals(SupportOfferReceipts.MAX_ATTEMPTS - 1, q.pending.single().attempts)
        q.failed(a)
        assertTrue(q.pending.isEmpty())
        repeat(60) { q.enqueue(SupportOfferReceipt("cv1", "ofc_$it", "opened", "open_$it", SupportOfferReceipt.iso(now))) }
        assertEquals(SupportOfferReceipts.MAX_ITEMS, q.pending.size)
        q.clear(); assertNull(backing.raw)
        now += 1
        assertEquals("shown_0123456789abcdef01234567", SupportOfferReceipt.shownId("ofc_0123456789abcdef01234567"))
    }

    @Test fun openerRecordsRefusesOrOpensOffline() = runBlocking {
        val q = SupportOfferReceipts(Memory())
        val opener = SupportOfferOpener(q, 2_000, send = { null })
        val r1 = opener.receipt(card(), "cv1")
        assertEquals("opened", r1.kind); assertTrue(r1.clientEventId.startsWith("open_"))
        assertEquals(SupportOfferOpenCheck.Recorded(null), opener.check(r1))
        assertTrue(q.pending.isEmpty())
        opener.send = { throw AppReferSupportException(409, "This offer can't be opened (revoked).") }
        assertEquals(SupportOfferOpenCheck.Refused, opener.check(opener.receipt(card(), "cv1")))
        assertTrue(q.pending.isEmpty())
        opener.send = { throw java.io.IOException("offline") }
        val r3 = opener.receipt(card(), "cv1")
        assertEquals(SupportOfferOpenCheck.Offline, opener.check(r3))
        assertEquals(listOf(r3.clientEventId), q.pending.map { it.clientEventId })
        opener.timeoutMs = 50
        opener.send = { delay(5_000); null }
        val started = System.currentTimeMillis()
        assertEquals(SupportOfferOpenCheck.Offline, opener.check(opener.receipt(card(), "cv1")))
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertEquals(2, q.pending.size)
    }

    private class Store : SupportStore { override var token: String? = null; override var profile: String? = null }
    private class Harness {
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        val bodies = mutableListOf<Map<String, Any?>>()
        var reply: (String, String) -> SupportResponse = { _, _ -> SupportResponse(200, "{}") }
        val api = SupportAPI({ "pk_live_key" }, Store(), { mapOf("appUserId" to "user_42") }, { emptyMap() }, SupportTransport { method, url, headers, bytes ->
            requests.add(Triple(method, url, headers)); bodies.add(JsonCodec.decodeObject(bytes?.toString(Charsets.UTF_8)))
            if (url.endsWith("/session")) SupportResponse(201, """{"session":"s1","created":true}""") else reply(method, url)
        }, identityCleared = { false })
    }

    @Test fun requestsAnnounceCardsAndAskForThePlatformsProducts() = runBlocking {
        val h = Harness()
        h.reply = { _, url ->
            if (url.contains("/offers?")) SupportResponse(200, """{"offers":[$cardJson,null]}""")
            else SupportResponse(200, """{"conversation":{"id":"cv1","subject":"s","status":"open","preview":"p","lastMessageAt":"x","lastFrom":"team","unread":0},"messages":[],"persona":null}""")
        }
        SupportOffers.hasHandler = false
        h.api.thread("cv1", "2026-10-19T12:00:00.000Z")
        AppReferSupport.onOffer = { }
        val cards = h.api.offerCards("cv1")
        AppReferSupport.onOffer = null
        assertEquals(listOf("ofc_0123456789abcdef01234567"), cards.map { it.cardId })
        val calls = h.requests.filterNot { it.second.endsWith("/session") }
        assertEquals("offers, reviews, testimonials", calls[0].third["X-AppRefer-Support-Features"])
        assertTrue(calls[0].second.endsWith("/conversations/cv1?platform=android&after=2026-10-19T12%3A00%3A00.000Z"))
        assertTrue(calls[1].second.endsWith("/conversations/cv1/offers?platform=android"))
        assertEquals("offers, offer-handler, reviews, testimonials", calls[1].third["X-AppRefer-Support-Features"])
        assertFalse(SupportOffers.hasHandler)
    }

    @Test fun flushSendsOnceKeepsTransientFailuresAndDropsRefusals() = runBlocking {
        val q = SupportOfferReceipts(Memory())
        val now = SupportOfferReceipt.iso(System.currentTimeMillis())
        listOf("ok", "flaky", "gone").forEach { q.enqueue(SupportOfferReceipt("cv1", "ofc_$it", if (it == "ok") "shown" else "opened", "evt_${it}_00001", now)) }
        val h = Harness()
        h.reply = { _, url ->
            when {
                url.contains("ofc_flaky") -> SupportResponse(503, """{"error":"busy"}""")
                url.contains("ofc_gone") -> SupportResponse(404, """{"error":"Not found"}""")
                else -> SupportResponse(201, """{"eventId":"ofe_x","duplicate":false,"offer":null}""")
            }
        }
        h.api.flushOfferReceipts(q)
        assertEquals(listOf("ofc_flaky"), q.pending.map { it.cardId })
        assertEquals(1, q.pending.single().attempts)
        val i = h.requests.indexOfFirst { it.second.contains("ofc_ok") }
        assertEquals("POST", h.requests[i].first)
        assertTrue(h.requests[i].second.endsWith("/conversations/cv1/offers/ofc_ok/events?platform=android"))
        assertEquals(mapOf("kind" to "shown", "clientEventId" to "evt_ok_00001", "occurredAt" to now), h.bodies[i])
    }

    @Test fun onOfferIsAnnouncedAndNothingOpensWithoutIt() {
        var closed = 0
        assertFalse(AppReferSupport.deliver(AppReferSupportOffer("ofc_a", "cv1", "main", null)) { closed++ })
        assertEquals(0, closed)
        AppReferSupport.onOffer = { }
        assertTrue(SupportOffers.hasHandler)
        AppReferSupport.onOffer = null
        assertFalse(SupportOffers.hasHandler)
    }
}
