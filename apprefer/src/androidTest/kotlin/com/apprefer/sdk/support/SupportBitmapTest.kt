package com.apprefer.sdk.support

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual Android JPEG codec coverage; run connectedDebugAndroidTest on an emulator. */
@RunWith(AndroidJUnit4::class)
class SupportBitmapTest {
    @Test fun testCompressedJpegBoundsAndByteLength() {
        val bitmap = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(42)
        val row = IntArray(2400)
        for (y in 0 until 1800) {
            for (x in row.indices) row[x] = random.nextInt() or 0xff000000.toInt()
            bitmap.setPixels(row, 0, 2400, 0, y, 2400, 1)
        }
        val jpeg = SupportImages.jpeg(bitmap)
        assertTrue(jpeg.size <= 700_000)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 1600)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }
}
