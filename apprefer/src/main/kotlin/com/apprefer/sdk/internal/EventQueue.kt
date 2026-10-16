package com.apprefer.sdk.internal

import com.apprefer.sdk.AppReferEventResult
import kotlinx.coroutines.*
import java.util.UUID

internal data class EventResponse(val status: Int?, val retryAfter: Long = 0)
/** One durable copy per event, with at-least-once delivery and stable eventId. */
internal class EventQueue(
    private var read: () -> String?,
    private var write: (String?) -> Unit,
    private var enabled: () -> Boolean,
    private var send: suspend (Map<String, Any?>) -> Any?,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
    private var quarantine: (String) -> Unit = {},
) {
    private val lock = Any()
    private var generation = 0
    private var running = false
    private var retry: Job? = null
    fun replace(read: () -> String?, write: (String?) -> Unit, enabled: () -> Boolean,
        send: suspend (Map<String, Any?>) -> Any?, quarantine: (String) -> Unit) = synchronized(lock) {
        generation++; running = false; retry?.cancel(); retry = null
        this.read = read; this.write = write; this.enabled = enabled; this.send = send; this.quarantine = quarantine
    }
    private fun load(): MutableList<MutableMap<String, Any?>> {
        val raw = read() ?: return mutableListOf()
        try {
            @Suppress("UNCHECKED_CAST")
            val values = JsonCodec.decodeObject(raw)["events"] as? List<Map<String, Any?>> ?: error("Corrupt queue")
            require(values.all { it["id"] is String && it["body"] is Map<*, *> && it["created"] is Number && it["next"] is Number && it["attempts"] is Number })
            return values.filter { now() - (it["created"] as Number).toLong() < 7 * 86400000L }.takeLast(1000).map { it.toMutableMap() }.toMutableList()
        } catch (_: Exception) {
            quarantine(raw); write(null); return mutableListOf()
        }
    }
    private fun save(values: List<Map<String, Any?>>) { write(JsonCodec.encode(mapOf("events" to values.takeLast(1000)))) }
    suspend fun clear() = withContext(Dispatchers.IO) { SafeRunner.safelyRun { synchronized(lock) {
        generation++; running = false; retry?.cancel(); retry = null; write(null)
    } } }
    fun generationSnapshot(): Int = synchronized(lock) { generation }
    suspend fun enqueue(body: Map<String, Any?>, actionEpoch: Int = generationSnapshot()): AppReferEventResult {
        return withContext(Dispatchers.IO) {
        SafeRunner.safely(AppReferEventResult.DROPPED) {
            val id = UUID.randomUUID().toString()
            val epoch = synchronized(lock) {
                if (actionEpoch != generation || !enabled()) return@safely AppReferEventResult.DROPPED
                val values = load()
                val copy = JsonCodec.decodeObject(JsonCodec.encode(body + ("eventId" to id)))
                values.add(mutableMapOf("id" to id, "body" to copy, "created" to now(), "attempts" to 0, "next" to 0L))
                save(values); generation
            }
            val results = flush(id)
            synchronized(lock) {
                if (epoch != generation) AppReferEventResult.DROPPED
                else results[id]?.let { if (it in 200..299) AppReferEventResult.SENT else AppReferEventResult.DROPPED } ?: AppReferEventResult.QUEUED
            }
        }
    }
    }
    suspend fun flush(only: String? = null): Map<String, Int> = withContext(Dispatchers.IO) {
        SafeRunner.safely(emptyMap()) { drain(only) }
    }
    private suspend fun drain(only: String?): Map<String, Int> {
        val epoch = synchronized(lock) {
            if (running) return emptyMap()
            if (!enabled()) { write(null); return emptyMap() }
            running = true; generation
        }
        val results = mutableMapOf<String, Int>()
        try {
            val entries = synchronized(lock) { load().also { save(it) } }
            for (entry in entries) {
                if (synchronized(lock) { epoch != generation || !enabled() }) break
                currentCoroutineContext().ensureActive()
                if ((only != null && entry["id"] != only) || (entry["next"] as Number).toLong() > now()) continue
                @Suppress("UNCHECKED_CAST")
                val response = send(entry["body"] as Map<String, Any?>)
                val status = if (response is EventResponse) response.status else response as? Int
                val retryAfter = (response as? EventResponse)?.retryAfter ?: 0
                val retryable = status == null || status == 408 || status == 429 || status in 500..599
                synchronized(lock) {
                    if (epoch != generation) return@synchronized
                    val current = load()
                    if (status != null && !retryable) {
                        current.removeAll { it["id"] == entry["id"] }; results[entry["id"] as String] = status
                    } else {
                        current.find { it["id"] == entry["id"] }?.let {
                            val attempts = (it["attempts"] as Number).toInt() + 1; it["attempts"] = attempts
                            val next = now() + maxOf(retryAfter, minOf(3600000L, 1000L * (1L shl minOf(attempts, 12))))
                            current.forEach { item -> item["next"] = maxOf((item["next"] as Number).toLong(), next) }
                        }
                    }
                    save(current)
                }
                if (retryable) break
            }
        } finally {
            synchronized(lock) {
                if (epoch == generation) {
                    running = false; retry?.cancel()
                    SafeRunner.safelyRun {
                        load().minOfOrNull { (it["next"] as Number).toLong() }?.let { next ->
                            retry = scope.launch { delay(maxOf(100L, next - now())); flush() }
                        }
                    }
                }
            }
        }
        return results
    }
}
