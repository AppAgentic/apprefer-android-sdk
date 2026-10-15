package com.apprefer.sdk.support
import org.junit.Assert.*
import org.junit.Test
class SupportImagesTest {
    @Test fun boundsAndAspectRatio() {
        assertEquals(1600 to 800, SupportImages.dimensions(4000,2000))
        assertEquals(800 to 1600, SupportImages.dimensions(2000,4000))
        assertEquals(600 to 300, SupportImages.dimensions(600,300))
        assertEquals(1 to 1600, SupportImages.dimensions(1,10000))
    }
    @Test fun lowersQualityThenShrinksUntilUnderBudget() {
        val calls = mutableListOf<Triple<Int,Int,Int>>()
        val bytes = SupportImages.compressUnderBudget(4000,2000) { w,h,q ->
            calls.add(Triple(w,h,q)); ByteArray(if(w == 1600) 800_000 else 600_000)
        }
        assertEquals(600_000,bytes.size)
        assertEquals(listOf(80,65,50,35),calls.take(4).map { it.third })
        assertEquals(Triple(1200,600,35),calls.last())
    }
    @Test fun leavesSmallJpegAtInitialQuality() {
        var calls = 0
        assertEquals(400,SupportImages.compressUnderBudget(100,100) { _,_,q -> calls++; assertEquals(80,q); ByteArray(400) }.size)
        assertEquals(1,calls)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidImage() { SupportImages.dimensions(0,20) }
}
