package com.apprefer.sdk.support

/**
 * App data sent with a new conversation as `context.app` (0.14.0): shown to the team and the AI
 * assistant, never to the customer. The server applies the same rules.
 */
internal object SupportAppContext {
    const val MAX_KEYS = 20
    const val MAX_VALUE_LENGTH = 500
    private val KEY = Regex("^[a-z0-9_]{1,40}$")

    /**
     * Keys matching `^[a-z0-9_]{1,40}$`, sorted, at most 20. Control characters become spaces,
     * values are trimmed and cut to 500 characters, and empty values are dropped.
     */
    fun clean(values: Map<String, String>?): Map<String, String> {
        if (values.isNullOrEmpty()) return emptyMap()
        val sorted = java.util.TreeMap<String, String>()
        for ((key, value) in values) {
            // Java callers can put nulls in a Map<String, String>.
            val k = (key as Any?) as? String ?: continue
            if (!KEY.matches(k)) continue
            val v = cleanValue((value as Any?) as? String ?: continue) ?: continue
            sorted[k] = v
        }
        val out = LinkedHashMap<String, String>()
        for ((k, v) in sorted) { if (out.size >= MAX_KEYS) break; out[k] = v }
        return out
    }

    private fun cleanValue(raw: String): String? {
        val spaced = buildString(raw.length) { raw.forEach { append(if (Character.isISOControl(it)) ' ' else it) } }.trim()
        if (spaced.isEmpty()) return null
        if (spaced.length <= MAX_VALUE_LENGTH) return spaced
        // Never end on half of a surrogate pair.
        val end = if (Character.isHighSurrogate(spaced[MAX_VALUE_LENGTH - 1])) MAX_VALUE_LENGTH - 1 else MAX_VALUE_LENGTH
        return spaced.substring(0, end)
    }
}
