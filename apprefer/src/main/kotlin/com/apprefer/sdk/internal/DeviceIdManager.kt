package com.apprefer.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.apprefer.sdk.internal.SafeRunner.safely
import java.util.UUID

/**
 * Resolves (and persists) the AppRefer device ID.
 *
 * Order of preference:
 *   1. Cached device_id in SharedPreferences (stable across app launches)
 *   2. `Settings.Secure.ANDROID_ID` — unless blacklisted or looks like an emulator
 *   3. `UUID.randomUUID()` fallback
 *
 * Blacklisted values we reject:
 *   - null / empty
 *   - `9774d56d682e549c` — well-known buggy value shared across many Android 2.x devices
 *   - all-zero or all-same-character strings (emulator leftovers)
 */
internal class DeviceIdManager(
    private val context: Context,
    private val storage: AppReferStorage,
    private val logger: Logger,
) {

    fun getOrCreate(): String = safely(fallbackUuid()) {
        storage.getDeviceId()?.takeIf { it.isNotBlank() }?.let {
            logger.debug("Device ID from cache: $it")
            return@safely it
        }
        val fresh = resolveFresh()
        storage.setDeviceId(fresh)
        logger.info("Device ID created: $fresh")
        fresh
    }

    private fun resolveFresh(): String {
        val androidId = safely<String?>(null) { readAndroidId() }
        return if (androidId != null && isAcceptable(androidId)) {
            androidId
        } else {
            logger.debug("ANDROID_ID unusable, generating UUID fallback")
            fallbackUuid()
        }
    }

    @SuppressLint("HardwareIds")
    private fun readAndroidId(): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    private fun isAcceptable(value: String): Boolean {
        if (value.isBlank()) return false
        if (value in BLACKLIST) return false
        // Reject "0000000000000000" and other all-same-char emulator leftovers
        if (value.toSet().size <= 1) return false
        return true
    }

    private fun fallbackUuid(): String = safely(UUID.randomUUID().toString()) {
        UUID.randomUUID().toString()
    }

    companion object {
        // Values that have been flagged unreliable across the Android ecosystem.
        private val BLACKLIST = setOf(
            "9774d56d682e549c", // Android 2.x Galaxy Tab shared ID
            "0000000000000000", // emulator
            "dead00beef",        // test harness
        )
    }
}
