package com.apprefer.sdk.billing

/** Standard Base64 SHA-256, without an Android API dependency (min SDK 23). */
internal fun legacyPlayAccountToken(user: String): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(user.toByteArray(Charsets.UTF_8))
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString {
        for (i in bytes.indices step 3) {
            val a = bytes[i].toInt() and 255
            val b = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 255 else 0
            val c = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 255 else 0
            val bits = (a shl 16) or (b shl 8) or c
            append(alphabet[(bits ushr 18) and 63])
            append(alphabet[(bits ushr 12) and 63])
            append(if (i + 1 < bytes.size) alphabet[(bits ushr 6) and 63] else '=')
            append(if (i + 2 < bytes.size) alphabet[bits and 63] else '=')
        }
    }
}
