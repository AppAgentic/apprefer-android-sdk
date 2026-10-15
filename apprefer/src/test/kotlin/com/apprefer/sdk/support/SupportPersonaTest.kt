package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupportPersonaTest {
    private fun api(vararg bodies: String): SupportAPI {
        val replies = java.util.ArrayDeque(bodies.toList())
        val store = object : SupportStore { override var token: String? = "s1"; override var profile: String? = "{}" }
        return SupportAPI({ "pk_test" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { _, _, _, _ -> SupportResponse(200, replies.removeFirst()) })
    }

    @Test fun personaPresentNullAndMissing() = runBlocking {
        val sam = AppReferSupportPersona("Sam", "https://apprefer.com/api/support/avatar/sam", "Jay")
        val a = api(
            """{"conversations":[],"persona":{"name":"Sam","avatarUrl":"https://apprefer.com/api/support/avatar/sam","handoffTo":"Jay"}}""",
            """{"conversations":[],"persona":null}""",
            """{"conversation":{"id":"c"},"messages":[],"persona":{"name":"Sam","avatarUrl":null,"handoffTo":null}}""",
            """{"conversation":{"id":"c"},"messages":[]}""",
        )
        a.conversations(); assertEquals(sam, a.persona)
        a.conversations(); assertNull(a.persona)
        assertEquals(AppReferSupportPersona("Sam", null, null), a.thread("c").persona); assertEquals(AppReferSupportPersona("Sam", null, null), a.persona)
        assertNull(a.thread("c").persona); assertNull(a.persona) // older servers omit the key
        assertNull(AppReferSupportPersona.from(JsonCodec.decodeObject("""{"persona":{"name":"  "}}""")))
    }

    @Test fun handoffPresentAndMissing() {
        val t = SupportThread(JsonCodec.decodeObject("""{"conversation":{"id":"c"},"messages":[
            {"id":"1","from":"bot","authorName":"Sam","text":"I'll get Jay","createdAt":"","attachments":[],"handoff":true},
            {"id":"2","from":"bot","authorName":"Sam","text":"Try restore","createdAt":"","attachments":[]},
            {"id":"3","from":"bot","authorName":null,"text":"Old answer","createdAt":"","attachments":[]}]}"""))
        assertTrue(t.messages[0].isHandoff); assertEquals(true, t.messages[0].handoff); assertEquals("Sam", t.messages[0].authorName)
        assertFalse(t.messages[1].isHandoff); assertNull(t.messages[1].handoff)
        assertNull(t.messages[2].authorName)
    }

    @Test fun presenceWithAndWithoutPersona() {
        assertEquals("Sam replies instantly · Jay and the team in under an hour", SupportText.presence(listOf("Jay", "Ana"), "under an hour", "Sam"))
        assertEquals("Sam replies instantly · the team in under an hour", SupportText.presence(emptyList(), "under an hour", "Sam"))
        assertEquals("Jay · usually replies in a day", SupportText.presence(listOf("Jay"), "a day", null))
        assertEquals("We usually reply in a day", SupportText.presence(emptyList(), "a day", null))
        assertEquals("Message Sam…", SupportText.homeHint(null, "Sam"))
        assertEquals("What went wrong?", SupportText.homeHint("What went wrong?", "Sam"))
        assertEquals("Ask us anything…", SupportText.homeHint(null, null))
        assertEquals("Passed to Jay · usually replies in under an hour", SupportText.handoff("Jay", "under an hour"))
        assertEquals("Passed to the team · usually replies in a day", SupportText.handoff(null, "a day"))
    }

    @Test fun quickRepliesHiddenAfterHandoff() {
        fun m(from: String, handoff: Boolean? = null) = AppReferSupportMessage("x", from, "Sam", "hi", "", emptyList(), handoff)
        assertTrue(supportOffersQuickReplies(m("bot")))
        assertTrue(supportOffersQuickReplies(m("team")))
        assertFalse(supportOffersQuickReplies(m("bot", handoff = true)))
        assertFalse(supportOffersQuickReplies(m("customer")))
        assertFalse(supportOffersQuickReplies(null))
    }
}
