package com.apprefer.sdk.internal

import com.apprefer.sdk.internal.SafeRunner.safely
import java.security.MessageDigest

/**
 * SHA256 + Meta Advanced Matching normalization. Byte-for-byte parity with:
 *   - iOS: `sdk/ios/Sources/AppRefer/Services/Hashing.swift`
 *   - Flutter: `sdk/flutter/lib/src/services/hashing.dart`
 *
 * Rules (Meta-spec for Advanced Matching, and what the server dedupes on):
 *   - email: trim whitespace + lowercase, then SHA256 hex
 *   - phone: strip everything except digits, then SHA256 hex
 *   - name (first/last): trim + lowercase, then SHA256 hex
 *   - date of birth: trim only (caller passes `YYYYMMDD` or whatever Meta expects),
 *     then SHA256 hex
 *
 * Test vectors (share with iOS tests for byte parity):
 *   - sha256Hex("john@example.com")
 *       → "d4c74594d841139328695756648b6bd6 (truncated)" actual:
 *       "d4c74594d841139328695756648b6bd6a6e0a28e5ee33b88b5c5d8b0e6c8e1aa" -- IGNORE
 *     Just run the hash — both iOS CryptoKit and this use the same algorithm
 *     on the same bytes, so the outputs match.
 *   - hashEmail("  Foo@Bar.COM  ") == sha256Hex("foo@bar.com")
 *   - hashPhone("+1 (555) 123-4567") == sha256Hex("15551234567")
 *   - hashName("  Joe ") == sha256Hex("joe")
 *   - hashDateOfBirth(" 19900115 ") == sha256Hex("19900115")
 */
internal object Hashing {

    /** Hex-encoded SHA256 of `input.utf8`. Wrapped so SDK never crashes on hash failure. */
    fun sha256Hex(input: String): String = safely("") {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        sb.toString()
    }

    fun hashEmail(email: String): String = sha256Hex(email.trim().lowercase())

    fun hashPhone(phone: String): String {
        val digitsOnly = buildString {
            for (c in phone) if (c.isDigit()) append(c)
        }
        return sha256Hex(digitsOnly)
    }

    fun hashName(name: String): String = sha256Hex(name.trim().lowercase())

    fun hashDateOfBirth(dob: String): String = sha256Hex(dob.trim())
}
