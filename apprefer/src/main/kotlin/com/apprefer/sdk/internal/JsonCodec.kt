package com.apprefer.sdk.internal

import com.apprefer.sdk.models.Attribution
import com.apprefer.sdk.internal.SafeRunner.safely
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-rolled JSON serializer/parser built on `org.json.JSONObject` (Android stdlib).
 *
 * Why not Moshi / kotlinx.serialization? Plan §3/§6: zero heavy deps.
 * Host apps already ship their own JSON library; we don't want to force a version.
 */
internal object JsonCodec {

    /**
     * Serialize a nested map/list/primitive tree into a compact JSON string.
     * `null` values are included as JSON `null` — matching what JSONSerialization does
     * on iOS. The server's `normalizeConfigureBody.ts` treats missing and null
     * identically.
     */
    fun encode(value: Any?): String = safely("{}") {
        when (value) {
            is Map<*, *> -> mapToJson(value).toString()
            is List<*> -> listToJson(value).toString()
            null -> "null"
            else -> JSONObject.wrap(value)?.toString() ?: "null"
        }
    }

    /** Parse a JSON string as a top-level object. Returns an empty map on failure. */
    fun decodeObject(json: String?): Map<String, Any?> = safely(emptyMap()) {
        if (json.isNullOrBlank()) emptyMap()
        else jsonObjectToMap(JSONObject(json))
    }

    /**
     * Parse the `attribution` portion of a configure response into an [Attribution]
     * instance. Field names match the server's camel/snake output and the iOS decoder
     * (see `sdk/ios/Sources/AppRefer/Models/Attribution.swift` + Flutter `Attribution.fromJson`).
     */
    fun parseAttribution(obj: Map<String, Any?>): Attribution? = safely(null) {
        val network = obj["network"] as? String ?: "unknown"
        val matchType = obj["match_type"] as? String
            ?: obj["matchType"] as? String
            ?: "organic"

        val queryParams = (obj["query_params"] ?: obj["queryParams"])
            ?.let { toStringMap(it) }
            ?: emptyMap()

        val customData = (obj["custom_data"] ?: obj["customData"])
            ?.let { toStringMap(it) }
            ?: emptyMap()

        val createdAtMs: Long = (obj["created_at"] ?: obj["createdAt"])?.let { raw ->
            when (raw) {
                is Number -> raw.toLong()
                is String -> parseIsoToMillis(raw) ?: System.currentTimeMillis()
                else -> null
            }
        } ?: System.currentTimeMillis()

        Attribution(
            network = network,
            campaign = obj["campaign"] as? String,
            matchType = matchType,
            attributionId = (obj["attribution_id"] ?: obj["attributionId"]) as? String,
            campaignId = (obj["campaign_id"] ?: obj["campaignId"]) as? String,
            campaignName = (obj["campaign_name"] ?: obj["campaignName"]) as? String,
            adGroupId = (obj["ad_group_id"] ?: obj["adGroupId"]) as? String,
            adId = (obj["ad_id"] ?: obj["adId"]) as? String,
            keyword = obj["keyword"] as? String,
            fbclid = obj["fbclid"] as? String,
            gclid = obj["gclid"] as? String,
            ttclid = obj["ttclid"] as? String,
            queryParams = queryParams,
            customData = customData,
            createdAt = createdAtMs,
        )
    }

    /** Serialize an [Attribution] to a snake_case JSON string (for SharedPreferences cache). */
    fun encodeAttribution(a: Attribution): String = safely("{}") {
        val map = mapOf(
            "network" to a.network,
            "campaign" to a.campaign,
            "match_type" to a.matchType,
            "attribution_id" to a.attributionId,
            "campaign_id" to a.campaignId,
            "campaign_name" to a.campaignName,
            "ad_group_id" to a.adGroupId,
            "ad_id" to a.adId,
            "keyword" to a.keyword,
            "fbclid" to a.fbclid,
            "gclid" to a.gclid,
            "ttclid" to a.ttclid,
            "query_params" to a.queryParams,
            "custom_data" to a.customData,
            "created_at" to a.createdAt,
        )
        mapToJson(map).toString()
    }

    // region helpers

    private fun mapToJson(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) {
            val key = k?.toString() ?: continue
            obj.put(key, toJsonValue(v))
        }
        return obj
    }

    private fun listToJson(list: List<*>): JSONArray {
        val arr = JSONArray()
        for (v in list) arr.put(toJsonValue(v))
        return arr
    }

    private fun toJsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> mapToJson(v)
        is List<*> -> listToJson(v)
        is Array<*> -> listToJson(v.toList())
        is Number, is Boolean, is String -> v
        else -> v.toString()
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = unwrap(obj.opt(k))
        }
        return out
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val out = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) out.add(unwrap(arr.opt(i)))
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> jsonArrayToList(v)
        else -> v
    }

    private fun toStringMap(any: Any?): Map<String, String> = safely(emptyMap()) {
        when (any) {
            is Map<*, *> -> any.entries
                .filter { it.key != null && it.value != null }
                .associate { it.key.toString() to it.value.toString() }
            else -> emptyMap()
        }
    }

    private fun parseIsoToMillis(s: String): Long? = safely(null) {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (fmt in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return@safely sdf.parse(s)?.time
            } catch (_: Exception) {}
        }
        null
    }

    // endregion
}
