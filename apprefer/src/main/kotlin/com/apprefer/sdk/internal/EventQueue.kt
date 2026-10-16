package com.apprefer.sdk.internal

import com.apprefer.sdk.AppReferEventResult
import kotlinx.coroutines.*
import java.util.UUID

/** One durable copy per event, with at-least-once delivery and stable eventId. */
internal class EventQueue(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
    private val enabled: () -> Boolean,
    private val send: suspend (Map<String, Any?>) -> Int?,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var generation = 0
    private var running = false
    private var retry: Job? = null
    private fun load(): MutableList<MutableMap<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val values = JsonCodec.decodeObject(read())["events"] as? List<Map<String, Any?>> ?: emptyList()
        return values.filter { now() - (it["created"] as Number).toLong() < 7 * 86400000L }.takeLast(1000).map { it.toMutableMap() }.toMutableList()
    }
    private fun save(values: List<Map<String, Any?>>) { write(JsonCodec.encode(mapOf("events" to values.takeLast(1000)))) }
    fun clear() = synchronized(lock) {
        generation++; retry?.cancel(); retry = null; write(null)
    }
    suspend fun enqueue(body: Map<String, Any?>): AppReferEventResult {
        val id = UUID.randomUUID().toString()
        val epoch = synchronized(lock) {
            if (!enabled()) { clear(); return AppReferEventResult.DROPPED }
            val values = load()
            val copy = JsonCodec.decodeObject(JsonCodec.encode(body + ("eventId" to id)))
            values.add(mutableMapOf("id" to id, "body" to copy, "created" to now(), "attempts" to 0, "next" to 0L))
            save(values); generation
        }
        val results = flush()
        return synchronized(lock) {
            if (epoch != generation) AppReferEventResult.DROPPED
            else results[id]?.let { if (it in 200..299) AppReferEventResult.SENT else AppReferEventResult.DROPPED } ?: AppReferEventResult.QUEUED
        }
    }
    suspend fun flush(): Map<String, Int> {
        val epoch: Int
        val entries: List<Map<String, Any?>>
        synchronized(lock) {
            if (!enabled()) { clear(); return emptyMap() }
            if (running) return emptyMap()
            running = true; epoch = generation; entries = load(); save(entries)
        }
        val results = mutableMapOf<String, Int>()
        try {
            for (entry in entries) {
                if (synchronized(lock) { epoch != generation || !enabled() }) break
                currentCoroutineContext().ensureActive()
                if ((entry["next"] as Number).toLong() > now()) continue
                @Suppress("UNCHECKED_CAST")
                val status = send(entry["body"] as Map<String, Any?>)
                synchronized(lock) {
                    if (epoch != generation) return@synchronized
                    val current = load()
                    if (status != null && status !in 500..599) {
                        current.removeAll { it["id"] == entry["id"] }
                        results[entry["id"] as String] = status
                    } else {
                        current.find { it["id"] == entry["id"] }?.let {
                            val attempts = (it["attempts"] as Number).toInt() + 1
                            it["attempts"] = attempts
                            it["next"] = now() + minOf(3600000L, 1000L * (1L shl minOf(attempts, 12)))
                        }
                    }
                    save(current)
                }
            }
        } finally {
            synchronized(lock) {
                running = false
                if (epoch == generation) {
                    retry?.cancel()
                    load().minOfOrNull { (it["next"] as Number).toLong() }?.let { next ->
                        retry = scope.launch { delay(maxOf(100L, next - now())); flush() }
                    }
                }
            }
        }
        return results
    }
}
