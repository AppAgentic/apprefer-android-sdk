package com.apprefer.sdk.support

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class SupportStateTest {
    @Test fun interruptedAndFailedSendsRestoreTextPhotosAndIdentity() {
        listOf(null, "conversation").forEach { conversation ->
            listOf(false, true).forEach { failed ->
                val original = SupportPending(conversation, "Hello 🌍", listOf(byteArrayOf(1, 2, 3)), failed)
                val file = File.createTempFile("support-test", ".draft")
                try {
                    DataOutputStream(file.outputStream()).use { original.write(it) }
                    val restored = DataInputStream(file.inputStream()).use { SupportPending.read(it) }
                    assertEquals(original.conversationId, restored.conversationId)
                    assertEquals(original.clientMessageId, restored.clientMessageId)
                    assertEquals(original.text, restored.text)
                    assertArrayEquals(original.images.single(), restored.images.single())
                    assertTrue(restored.failed)
                } finally { file.delete() }
            }
        }
    }
    @Test fun sendCannotSkipEarlierTeamReplyAndMergeDeduplicates() {
        fun message(id: String, at: String) = AppReferSupportMessage(id, "customer", null, id, at, emptyList())
        val timeline = SupportTimeline { it.toLong() }
        timeline.merge(listOf(message("old", "1")), true)
        timeline.merge(listOf(message("sent", "3")))
        assertEquals("1", timeline.cursor)
        timeline.merge(listOf(message("team", "2"), message("sent", "3"), message("sent", "3")), true)
        assertEquals(listOf("old", "team", "sent"), timeline.messages.map { it.id })
        assertEquals("3", timeline.cursor)
        timeline.merge(listOf(message("old", "1")), true)
        assertEquals("3", timeline.cursor)
    }
}
