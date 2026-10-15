package com.apprefer.sdk.support

/** Access-ordered and bounded by decoded bytes; eviction never recycles a bitmap still on screen. */
internal class SupportImageCache<T>(private val budget: Int, private val size: (T) -> Int) {
    private val entries = LinkedHashMap<String, T>(16, .75f, true)
    private var bytes = 0L
    @Synchronized fun get(url: String): T? = entries[url]
    @Synchronized fun put(url: String, value: T) {
        entries.remove(url)?.let { bytes -= size(it) }
        entries[url] = value; bytes += size(value)
        val iterator = entries.entries.iterator()
        while (bytes > budget && iterator.hasNext()) {
            bytes -= size(iterator.next().value); iterator.remove()
        }
    }
}
