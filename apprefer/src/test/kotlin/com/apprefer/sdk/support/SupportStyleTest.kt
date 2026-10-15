package com.apprefer.sdk.support

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupportStyleTest {
    private val white = 0xFFFFFFFF.toInt()
    private val nearBlack = 0xFF141414.toInt()

    @Test fun brightYellowAccentGetsDarkText() {
        val palette = SupportPalette(0xFFFFD60A.toInt())
        assertTrue(palette.onAccentIsDark)
        assertEquals(nearBlack, palette.onAccent)
    }

    @Test fun purpleAccentGetsWhiteText() {
        val palette = SupportPalette(0xFF7852FF.toInt())
        assertFalse(palette.onAccentIsDark)
        assertEquals(white, palette.onAccent)
    }

    @Test fun paletteMathsMatchesIOS() {
        // HSB round trip and hue wrap-around.
        listOf(0xFF7852FF.toInt(), 0xFFFFD60A.toInt(), 0xFF1FB866.toInt(), 0xFF000000.toInt(), white).forEach { c ->
            val (h, s, b) = SupportPalette.hsb(c)
            assertEquals(c, SupportPalette.color(h, s, b))
        }
        assertEquals(SupportPalette.color(0.1f, .5f, .5f), SupportPalette.color(1.1f, .5f, .5f))
        assertEquals(SupportPalette.color(0.98f, .5f, .5f), SupportPalette.color(-0.02f, .5f, .5f))
        assertEquals(SupportPalette.color(.3f, 1f, 1f), SupportPalette.color(.3f, 1.4f, 2f))
        val purple = SupportPalette(0xFF7852FF.toInt())
        val (h, s, b) = SupportPalette.hsb(purple.accent)
        assertEquals(SupportPalette.color(h - 0.02f, s * 0.72f, b * 1.08f + 0.05f), purple.light)
        assertEquals(SupportPalette.color(h + 0.23f, maxOf(0.2f, s * 0.6f), 1f), purple.hot)
        assertEquals(SupportPalette.color(h + 0.38f, maxOf(0.2f, s * 0.62f), 1f), purple.warm)
        assertEquals(SupportPalette.color(h + 0.005f, s * 1.05f, b * 0.86f), purple.deep)
        assertEquals(0x80123456.toInt(), supportAlpha(0xFF123456.toInt(), 0.5f))
    }

    private fun conversation(id: String, at: String, unread: Int = 0, status: String = "open") =
        AppReferSupportConversation(id, "Subject $id", status, "preview", at, "team", unread)

    @Test fun continueCardPrefersUnreadThenNewestOpen() {
        val older = conversation("older-unread", "2026-10-14T09:00:00.000Z", unread = 2)
        val newest = conversation("newest", "2026-10-15T09:00:00.000Z")
        val closed = conversation("closed", "2026-10-15T10:00:00.000Z", unread = 1, status = "closed")
        val sections = SupportHomeSections(listOf(newest, closed, older))
        assertEquals("older-unread", sections.current?.id)
        assertEquals(listOf("newest", "closed"), sections.others.map { it.id })

        val readOnly = SupportHomeSections(listOf(conversation("a", "2026-10-14T09:00:00.000Z"), conversation("b", "2026-10-15T09:00:00.000Z"), closed))
        assertEquals("b", readOnly.current?.id)
        assertEquals(listOf("a", "closed"), readOnly.others.map { it.id })

        val allClosed = SupportHomeSections(listOf(closed))
        assertNull(allClosed.current)
        assertEquals(listOf("closed"), allClosed.others.map { it.id })
        assertNull(SupportHomeSections(emptyList()).current)
    }

    @Test fun presenceGreetingAndNames() {
        assertEquals("We usually reply in under an hour", SupportText.presence(emptyList(), "under an hour"))
        assertEquals("Jay · usually replies in a day", SupportText.presence(listOf("Jay"), "a day"))
        assertEquals("Jay and the team · usually under an hour", SupportText.presence(listOf("Jay", "Ana"), "under an hour"))
        assertEquals("Hi María 👋", SupportText.greeting("María López"))
        assertEquals("Hi there 👋", SupportText.greeting(null))
        assertEquals("Hi there 👋", SupportText.greeting("maria@example.com"))
        assertNull(SupportText.firstName("jay@apprefer.com"))
        assertEquals("J", SupportText.initial("jay"))
        assertEquals("SP", SupportText.initials("Sparkly"))
    }

    @Test fun topicsDefaultAndSubjectIsSent() = runBlocking {
        assertEquals(listOf("Billing & refunds", "Restore purchase", "Report a bug", "Suggest an idea"), AppReferSupportTopic.defaults.map { it.title })
        assertEquals(0xFF6E4AFF.toInt(), AppReferSupportTopic.billing.tint)
        val bodies = mutableListOf<String?>()
        val store = object : SupportStore { override var token: String? = "s1"; override var profile: String? = "{}" }
        val api = SupportAPI({ "pk_test" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { _, _, _, bytes ->
            bodies.add(bytes?.toString(Charsets.UTF_8)); SupportResponse(200, """{"conversation":{"id":"c"},"messages":[]}""")
        })
        api.start("hi", emptyList(), "client-123", "Report a bug")
        api.start("hi", emptyList(), "client-456")
        assertEquals("Report a bug", com.apprefer.sdk.internal.JsonCodec.decodeObject(bodies[0])["subject"])
        assertFalse(com.apprefer.sdk.internal.JsonCodec.decodeObject(bodies[1]).containsKey("subject"))
    }
}
