package com.apprefer.sdk.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.apprefer.sdk.internal.SafeRunner.safely
import java.util.Locale
import java.util.TimeZone

/**
 * Collects the `device_info` map sent in the configure request body. Keys match the
 * Flutter Android plugin's Dart-side `AppReferDeviceInfo.getDeviceInfo()` shape
 * (see `sdk/flutter/lib/src/services/device_info.dart`) so the normalized server
 * contract (`web/lib/normalize-body.ts`) accepts us identically.
 */
internal class DeviceInfo(private val context: Context, private val logger: Logger) {

    /** Returns a map safe to pass straight into [JsonCodec] — all values are strings, ints, booleans. */
    fun collect(): Map<String, Any?> = safely(mapOf<String, Any?>("platform" to "android")) {
        val pkgInfo: PackageInfo? = safely<PackageInfo?>(null) {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

        val appVersion: String = safely("") { pkgInfo?.versionName ?: "" }
        val appBuild: String = safely("") {
            @Suppress("DEPRECATION")
            (pkgInfo?.longVersionCodeCompat() ?: 0L).toString()
        }

        val locale: String = safely("") {
            val l = Locale.getDefault()
            val region = l.country ?: ""
            if (region.isNotBlank()) "${l.language}_$region" else l.language
        }

        val timezone: String = safely("") { TimeZone.getDefault().id ?: "" }

        mapOf(
            "platform" to "android",
            "app_version" to appVersion,
            "app_build" to appBuild,
            "bundle_id" to (pkgInfo?.packageName ?: context.packageName),
            "locale" to locale,
            "timezone" to timezone,
            "os_version" to (Build.VERSION.RELEASE ?: ""),
            "sdk_int" to Build.VERSION.SDK_INT,
            "model" to (Build.MODEL ?: ""),
            "manufacturer" to (Build.MANUFACTURER ?: ""),
            "device_name" to (Build.DEVICE ?: ""),
            "is_physical_device" to isPhysicalDevice(),
        )
    }

    /**
     * Heuristic match for physical-vs-emulator. Mirrors what `device_info_plus` does on
     * Android: checks fingerprint / model / brand / product for common emulator
     * signatures. Returns true when we *think* the device is physical.
     */
    private fun isPhysicalDevice(): Boolean = safely(true) {
        val fp = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val product = Build.PRODUCT ?: ""
        val hardware = Build.HARDWARE ?: ""
        val brand = Build.BRAND ?: ""
        !(fp.startsWith("generic") ||
            fp.startsWith("unknown") ||
            model.contains("google_sdk", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            brand.startsWith("generic") && Build.DEVICE?.startsWith("generic") == true ||
            product.contains("sdk_gphone", ignoreCase = true) ||
            product == "google_sdk" ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu"))
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
        else versionCode.toLong()
}
