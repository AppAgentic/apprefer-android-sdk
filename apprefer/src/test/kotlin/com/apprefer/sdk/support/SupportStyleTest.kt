package com.apprefer.sdk.support

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class SupportStyleTest {
    // English text comes from the generated string resources.
    @Before fun strings() = SupportStringsFixture.install()
    @After fun resetStrings() = SupportStringsFixture.uninstall()

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

    // MARK: - Header style (0.11.1)

    private fun isNeutral(c: Int) = ((c shr 16) and 0xff) == ((c shr 8) and 0xff) && ((c shr 8) and 0xff) == (c and 0xff)

    @Test fun headerStyleDefaultsToAccent() {
        assertEquals(AppReferSupportHeaderStyle.ACCENT, AppReferSupport.headerStyle)
    }

    @Test fun accentHeaderKeepsTheExistingLook() {
        listOf(0xFF7852FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt()).forEach { accent ->
            val p = SupportPalette(accent)
            listOf(true, false).forEach { dark ->
                val h = SupportHeaderLook(AppReferSupportHeaderStyle.ACCENT, p, dark)
                assertFalse(h.plain)
                assertTrue(h.glows)
                assertEquals(p.accent, h.background)
                assertEquals(p.onAccent, h.foreground)
                assertEquals(p.onAccentIsDark, h.foregroundIsDark)
                assertEquals(supportAlpha(p.onAccent, .2f), h.closeFill)
                assertEquals(supportAlpha(p.onAccent, .35f), h.closeStroke)
                assertEquals(supportAlpha(0xFF000000.toInt(), if (p.onAccentIsDark) .06f else .16f), h.pillFill)
                assertEquals(supportAlpha(p.onAccent, .3f), h.pillStroke)
                assertNull(h.avatars)
                assertEquals(0xE6FFFFFF.toInt(), h.avatarRing)
                assertEquals(p.light, h.iconText)
            }
        }
    }

    @Test fun whiteAccentStillGlowsColouredInAccentStyle() {
        // Why PLAIN exists: a white accent's neighbours are tinted (the green/lavender glow).
        val p = SupportPalette(white)
        assertFalse(isNeutral(p.hot) && isNeutral(p.warm))
    }

    @Test fun plainHeaderIsNeutralWithReadableText() {
        listOf(0xFF7852FF.toInt(), white, 0xFFFFD60A.toInt(), 0xFF1FB866.toInt()).forEach { accent ->
            val p = SupportPalette(accent)
            listOf(true, false).forEach { dark ->
                val h = SupportHeaderLook(AppReferSupportHeaderStyle.PLAIN, p, dark)
                assertTrue(h.plain)
                assertFalse("no glow overlays", h.glows)
                assertTrue(isNeutral(h.background))
                assertEquals(SupportHeaderLook.plainBackground(dark), h.background)
                assertEquals(SupportHeaderLook.plainInk(dark), h.foreground)
                assertTrue(isNeutral(h.foreground))
                assertEquals(!dark, h.foregroundIsDark)
                listOf(h.closeFill, h.closeStroke, h.pillFill, h.pillStroke, h.avatarRing, h.iconText).forEach { assertTrue(isNeutral(it)) }
                h.avatars!!.forEach { assertTrue(isNeutral(it)) }
                h.iconTile.forEach { assertTrue(isNeutral(it)) }
                // Text on the header: WCAG AA (4.5:1) or better.
                assertTrue(contrast(h.foreground, h.background) >= 4.5)
                h.avatars!!.forEach { assertTrue(contrast(white, it) >= 4.5) }
            }
        }
    }

    @Test fun continueCardAvatarFollowsHeaderStyle() {
        fun conv(lastFrom: String) = AppReferSupportConversation("a", "s", "open", "p", "2026-10-19T10:00:00Z", lastFrom, 0)
        val persona = AppReferSupportPersona("Riley", "https://example.com/riley.png", null)
        val p = SupportPalette(white)
        listOf(true, false).forEach { dark ->
            val accent = SupportHeaderLook(AppReferSupportHeaderStyle.ACCENT, p, dark)
            val plain = SupportHeaderLook(AppReferSupportHeaderStyle.PLAIN, p, dark)
            // No team names: the chat icon, in the accent's neighbours (unchanged; the reported mint) or neutral greys.
            val iconAccent = SupportContinueAvatar(conv("customer"), persona, emptyList(), accent, p)
            assertArrayEquals(intArrayOf(p.warm, p.hot), iconAccent.colors)
            assertFalse(iconAccent.colors.all(::isNeutral))
            assertTrue(iconAccent.chatIcon)
            val iconPlain = SupportContinueAvatar(conv("customer"), persona, emptyList(), plain, p)
            assertArrayEquals(SupportHeaderLook.PLAIN_AVATAR, iconPlain.colors)
            iconPlain.colors.forEach { assertTrue(isNeutral(it)); assertTrue(contrast(white, it) >= 4.5) }
            assertTrue(iconPlain.chatIcon)
            assertNull(iconPlain.imageUrl)
            // A team member: their initial on the same fill.
            val team = SupportContinueAvatar(conv("team"), persona, listOf("Jay Smith"), plain, p)
            assertEquals("Jay", team.name)
            assertFalse(team.chatIcon)
            team.colors.forEach { assertTrue(isNeutral(it)) }
            // The AI answered last: its portrait is kept in both styles.
            listOf(accent, plain).forEach { h ->
                val bot = SupportContinueAvatar(conv("bot"), persona, listOf("Jay"), h, p)
                assertEquals("https://example.com/riley.png", bot.imageUrl)
                assertEquals("Riley", bot.name)
                assertFalse(bot.chatIcon)
            }
        }
    }

    @Test fun homeEndFollowsTheComposersRealHeight() {
        // Narrow screens: summaries and tile subtitles wrap instead of stopping after a few words.
        assertEquals(3, SupportHomeLayout.SUMMARY_LINES)
        assertEquals(2, SupportHomeLayout.TILE_SUBTITLE_LINES)
        // Before the composer is measured: the old fixed 140 dp.
        assertEquals(420, SupportHomeLayout.endPadding(0, 3f))
        // One line (74 dp) and a five-line composer with a topic chip (230 dp), both at 3x:
        // the page always ends 64 dp above the composer, so the last tiles are never under it.
        assertEquals(74 * 3 + 64 * 3, SupportHomeLayout.endPadding(74 * 3, 3f))
        assertEquals(230 * 3 + 64 * 3, SupportHomeLayout.endPadding(230 * 3, 3f))
    }

    @Test fun homeKeepsItsEndOnlyWhenAlreadyThere() {
        val tol = 24 * 3
        // At the end of a 3000 px page in a 1800 px viewport: the keyboard (or a taller composer) keeps it there.
        assertTrue(SupportHomeLayout.atEnd(1200, 1800, 3000, tol))
        assertTrue(SupportHomeLayout.atEnd(1200 - tol, 1800, 3000, tol))
        // Reading higher up: never moved.
        assertFalse(SupportHomeLayout.atEnd(1200 - tol - 1, 1800, 3000, tol))
        assertFalse(SupportHomeLayout.atEnd(0, 1800, 3000, tol))
        // A page shorter than the screen is already at its end.
        assertTrue(SupportHomeLayout.atEnd(0, 1800, 900, tol))
    }

    @Test fun tileTitlesShrinkToFitLikeTheOtherSdks() {
        assertEquals(0.5f, SupportHomeLayout.TILE_TITLE_MIN_SCALE)
        // Fits: unchanged.
        assertEquals(45f, SupportHomeLayout.fitTextSize(45f, 200f, 300f, 0.8f))
        // A little too wide: shrinks just enough.
        assertEquals(45f * 300f / 330f * 0.98f, SupportHomeLayout.fitTextSize(45f, 330f, 300f, 0.8f), 0.001f)
        // Far too wide: stops at the floor (then ends in "...").
        assertEquals(22.5f, SupportHomeLayout.fitTextSize(45f, 1200f, 300f, 0.5f))
        // "Billing & refunds" at 1.6x in a 320 dp tile needs about 0.55: shown whole.
        assertEquals(45f * 0.55f * 0.98f, SupportHomeLayout.fitTextSize(45f, 300f / 0.55f, 300f, SupportHomeLayout.TILE_TITLE_MIN_SCALE), 0.01f)
    }

    private fun contrast(a: Int, b: Int): Double {
        fun lin(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        fun lum(c: Int) = 0.2126 * lin((c shr 16) and 0xff) + 0.7152 * lin((c shr 8) and 0xff) + 0.0722 * lin(c and 0xff)
        val (x, y) = lum(a) to lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
