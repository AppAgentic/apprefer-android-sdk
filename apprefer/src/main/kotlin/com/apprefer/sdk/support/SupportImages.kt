package com.apprefer.sdk.support

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal object SupportImages {
    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, 1600.0 / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
    fun jpeg(context: Context, uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image could not be read" }
        val opts = BitmapFactory.Options()
        while (max(bounds.outWidth, bounds.outHeight) / (opts.inSampleSize.coerceAtLeast(1) * 2) >= 1600) opts.inSampleSize = opts.inSampleSize.coerceAtLeast(1) * 2
        val bitmap = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) } ?: error("This image could not be read")
        return try { jpeg(bitmap) } finally { bitmap.recycle() }
    }
    // Pure policy so the byte limit and quality/resize fallback are covered on the JVM.
    fun compressUnderBudget(width: Int, height: Int, encode: (Int, Int, Int) -> ByteArray): ByteArray {
        var (w, h) = dimensions(width, height)
        var quality = 80
        while (true) {
            val bytes = encode(w, h, quality)
            if (bytes.size <= 700_000) return bytes
            if (quality > 35) { quality -= 15; continue }
            check(w > 1 || h > 1) { "Could not compress this screenshot" }
            w = max(1, w * 3 / 4); h = max(1, h * 3 / 4)
        }
    }
    fun jpeg(source: Bitmap): ByteArray {
        val (w, h) = dimensions(source.width, source.height)
        var bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawBitmap(source, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(3)) }
        try {
            return compressUnderBudget(w, h) { width, height, quality ->
                if (bitmap.width != width || bitmap.height != height) {
                    val smaller = Bitmap.createScaledBitmap(bitmap, width, height, true)
                    bitmap.recycle(); bitmap = smaller
                }
                ByteArrayOutputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)); it.toByteArray() }
            }
        } finally { bitmap.recycle() }
    }
}
