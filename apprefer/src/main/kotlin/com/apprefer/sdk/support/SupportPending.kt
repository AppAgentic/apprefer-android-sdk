package com.apprefer.sdk.support

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

internal data class SupportPending(
    val conversationId: String?, val text: String, val images: List<ByteArray>,
    var failed: Boolean = false, val clientMessageId: String = UUID.randomUUID().toString(),
) {
    fun write(out: DataOutputStream) {
        out.writeUTF(conversationId ?: ""); out.writeUTF(clientMessageId)
        val bytes = text.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes)
        out.writeInt(images.size); images.forEach { out.writeInt(it.size); out.write(it) }
    }
    companion object {
        fun read(input: DataInputStream): SupportPending {
            val target = input.readUTF().takeIf { it.isNotEmpty() }; val clientId = input.readUTF()
            require(clientId.matches(Regex("[A-Za-z0-9_-]{8,64}")))
            val text = String(readBytes(input, 1_000_000), Charsets.UTF_8)
            val count = input.readInt(); require(count in 0..4)
            val photos = (0 until count).map { readBytes(input, 700_000) }
            // Interrupted deliveries become retryable, with the same idempotency key.
            return SupportPending(target, text, photos, true, clientId)
        }
        private fun readBytes(input: DataInputStream, limit: Int): ByteArray {
            val size = input.readInt(); require(size in 0..limit)
            return ByteArray(size).also { input.readFully(it) }
        }
    }
}

internal class SupportTimeline(private val timestamp: (String) -> Long) {
    val messages = mutableListOf<AppReferSupportMessage>()
    var cursor: String? = null
        private set
    fun reset() { messages.clear(); cursor = null }
    fun merge(incoming: List<AppReferSupportMessage>, fromLoad: Boolean = false) {
        if (fromLoad) incoming.maxByOrNull { timestamp(it.createdAt) }?.let { latest ->
            if (cursor == null || timestamp(latest.createdAt) > timestamp(cursor!!)) cursor = latest.createdAt
        }
        val known = messages.map { it.id }.toHashSet()
        messages.addAll(incoming.filter { known.add(it.id) })
        messages.sortBy { timestamp(it.createdAt) }
    }
}
