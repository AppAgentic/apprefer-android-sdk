package com.apprefer.sdk.support

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colours for the chat, built from the app's accent (same maths as the iOS SupportPalette).
 * Pure Kotlin so it runs in JVM unit tests (android.graphics.Color is stubbed there).
 */
internal class SupportPalette(val accent: Int) {
    /** Lighter, warmer and hotter neighbours of the accent for the hero and avatars. */
    val light: Int
    val hot: Int
    val warm: Int
    val deep: Int
    /** Text and icons drawn on the accent: near-black on a bright accent such as yellow, else white. */
    val onAccent: Int
    val onAccentIsDark: Boolean

    init {
        val (h, s, b) = hsb(accent)
        light = color(h - 0.02f, s * 0.72f, b * 1.08f + 0.05f)
        hot = color(h + 0.23f, max(0.2f, s * 0.6f), 1f)
        warm = color(h + 0.38f, max(0.2f, s * 0.62f), 1f)
        deep = color(h + 0.005f, s * 1.05f, b * 0.86f)
        onAccentIsDark = luminance(accent) > 0.62
        onAccent = if (onAccentIsDark) 0xFF141414.toInt() else 0xFFFFFFFF.toInt()
    }

    companion object {
        fun luminance(c: Int): Double =
            0.2126 * ((c shr 16) and 0xff) / 255.0 + 0.7152 * ((c shr 8) and 0xff) / 255.0 + 0.0722 * (c and 0xff) / 255.0

        /** Hue, saturation and brightness in 0..1 (UIColor.getHue semantics). */
        fun hsb(c: Int): FloatArray {
            val r = ((c shr 16) and 0xff) / 255f; val g = ((c shr 8) and 0xff) / 255f; val b = (c and 0xff) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
            val s = if (mx == 0f) 0f else d / mx
            var h = when {
                d == 0f -> 0f
                mx == r -> ((g - b) / d) % 6f
                mx == g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } / 6f
            if (h < 0) h += 1f
            return floatArrayOf(h, s, mx)
        }

        /** Opaque ARGB from hue (wraps) and saturation/brightness (clamped to 0..1). */
        fun color(hue: Float, sat: Float, bri: Float): Int {
            val h = ((hue % 1f) + 1f) % 1f
            val s = sat.coerceIn(0f, 1f); val v = bri.coerceIn(0f, 1f)
            val i = (h * 6f).toInt() % 6; val f = h * 6f - (h * 6f).toInt()
            val p = v * (1 - s); val q = v * (1 - f * s); val t = v * (1 - (1 - f) * s)
            val (r, g, b) = when (i) {
                0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t)
                3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q)
            }
            return (0xff shl 24) or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
        }
    }
}

/** [color] with its alpha replaced by [alpha] (0..1). */
internal fun supportAlpha(color: Int, alpha: Float): Int =
    (color and 0x00ffffff) or ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24)

internal class SupportColors(dark: Boolean) {
    val background = if (dark) 0xFF0D0D12.toInt() else 0xFFF5F4F9.toInt()
    val card = if (dark) 0xFF1F1D24.toInt() else 0xFFFFFFFF.toInt()
    val muted = if (dark) 0xFF9E9E9E.toInt() else 0xFF8A8698.toInt()
    val ink = if (dark) 0xFFFFFFFF.toInt() else 0xFF111114.toInt()
    val divider = if (dark) 0x1FFFFFFF else 0x14000000
    val shadow = 0xFF29145A.toInt()
    val error = 0xFFE5383B.toInt()
}

internal object SupportText {
    /** First name only; never shows an email address. */
    fun firstName(name: String?): String? {
        val trimmed = name?.trim()
        if (trimmed.isNullOrEmpty() || trimmed.contains("@")) return null
        return trimmed.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun initial(name: String?): String = name?.trim()?.firstOrNull()?.uppercase() ?: "•"

    /** Two-letter tile text for apps without a usable launcher icon. */
    fun initials(appName: String): String = appName.trim().take(2).uppercase().ifEmpty { "?" }

    fun greeting(name: String?): String = firstName(name)?.let { "Hi $it 👋" } ?: "Hi there 👋"

    fun presence(teamNames: List<String>, replyTime: String): String {
        val names = teamNames.take(3)
        val first = names.firstOrNull() ?: return "We usually reply in $replyTime"
        return if (teamNames.size == 1) "$first · usually replies in $replyTime" else "$first and the team · usually $replyTime"
    }
}

/** The home screen's conversation sections. */
internal class SupportHomeSections(conversations: List<AppReferSupportConversation>) {
    /** Open conversations first, then newest. */
    val all: List<AppReferSupportConversation> = conversations.sortedWith(
        compareBy<AppReferSupportConversation> { it.status == "closed" }.thenByDescending { it.lastMessageAt })

    /** The open conversation to pick up again: unread first, then the newest. */
    val current: AppReferSupportConversation? = all.filter { it.status != "closed" }
        .maxWithOrNull(compareBy<AppReferSupportConversation> { it.unread > 0 }.thenBy { it.lastMessageAt })

    val others: List<AppReferSupportConversation> = all.filter { it.id != current?.id }
}
