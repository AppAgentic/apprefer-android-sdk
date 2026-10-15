package com.apprefer.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.apprefer.sdk.internal.SafeRunner.safely
import java.util.UUID

/**
 * Resolves (and persists) the AppRefer device ID.
 *
 * Order of preference (a new random id is only used once it is saved):
 *   1. Cached device_id in SharedPreferences (stable across app launches)
 *   2. `Settings.Secure.ANDROID_ID` — unless blacklisted or looks like an emulator
 *   3. `UUID.randomUUID()` fallback
 *
 * Blacklisted values we reject:
 *   - null / empty
 *   - `9774d56d682e549c` — well-known buggy value shared across many Android 2.x devices
 *   - all-zero or all-same-character strings (emulator leftovers)
 */
internal interface DeviceIdStore {
    /** The saved device id, or null when there is none. Throws when it can't be read. */
    fun readDeviceId(): String?

    /** Saves the id; false if it did not reach disk. */
    fun writeDeviceId(value: String): Boolean
}

internal class DeviceIdManager(
    private val storage: DeviceIdStore,
    private val androidId: () -> String?,
    private val logger: Logger,
) {
    constructor(context: Context, storage: AppReferStorage, logger: Logger) :
        this(storage, { readAndroidId(context) }, logger)

    /**
     * The saved device id, minting one only when none is saved. Returns null when storage
     * can't be read (after one retry): a new id would replace the device's identity and
     * split its attribution, so the caller skips this launch's request instead.
     * Serialized process-wide, so concurrent first-launch callers get the same id.
     */
    fun getOrCreate(): String? = synchronized(LOCK) {
        val existing = try {
            read()
        } catch (t: Throwable) {
            logger.error("Device ID unreadable (${t.javaClass.simpleName}); not creating a new one")
            return null
        }
        existing?.let {
            logger.debug("Device ID from cache: $it")
            return it
        }
        val (fresh, stable) = resolveFresh()
        if (!storage.writeDeviceId(fresh)) {
            if (!stable) {
                // A random id that isn't saved would be replaced by another one next launch,
                // splitting this device's attribution: skip until one can be saved.
                logger.error("Device ID could not be saved; not using an unsaved random id")
                return null
            }
            // ANDROID_ID is the same next launch, so it is safe to use unsaved.
            logger.error("Device ID could not be saved; it will be saved on the next launch")
        }
        logger.info("Device ID created: $fresh")
        fresh
    }

    /** One retry: SharedPreferences can fail transiently while its file is being loaded. */
    private fun read(): String? = try {
        storage.readDeviceId()
    } catch (_: Throwable) {
        storage.readDeviceId()
    }?.takeIf { it.isNotBlank() }

    /** A new id, and whether it is stable (ANDROID_ID) rather than random. */
    private fun resolveFresh(): Pair<String, Boolean> {
        val androidId = safely<String?>(null) { androidId() }
        return if (androidId != null && isAcceptable(androidId)) {
            androidId to true
        } else {
            logger.debug("ANDROID_ID unusable, generating UUID fallback")
            fallbackUuid() to false
        }
    }

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
        private val LOCK = Any()

        @SuppressLint("HardwareIds")
        private fun readAndroidId(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        // Values that have been flagged unreliable across the Android ecosystem.
        private val BLACKLIST = setOf(
            "9774d56d682e549c", // Android 2.x Galaxy Tab shared ID
            "0000000000000000", // emulator
            "dead00beef",        // test harness
        )
    }
}
