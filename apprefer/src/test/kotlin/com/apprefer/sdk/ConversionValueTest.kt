package com.apprefer.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionValueTest {
    @Test fun conversionValueIsANoOpEvenBeforeConfigure() {
        // No Context, Android runtime or network is needed for the parity API.
        assertEquals(Unit, AppRefer.updateConversionValue())
        assertEquals(Unit, AppRefer.updateConversionValue())
    }
}
