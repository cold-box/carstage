package com.carstage.app.vehicle

import android.car.hardware.CarPropertyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightModeReaderTest {
    @Test
    fun onlyAnAvailableBooleanSaysNightOrDay() {
        assertEquals(true, NightModeReader.valueOf(CarPropertyValue.STATUS_AVAILABLE, true))
        assertEquals(false, NightModeReader.valueOf(CarPropertyValue.STATUS_AVAILABLE, false))
        assertNull("unavailable", NightModeReader.valueOf(CarPropertyValue.STATUS_UNAVAILABLE, true))
        assertNull("an error", NightModeReader.valueOf(CarPropertyValue.STATUS_ERROR, false))
        assertNull("not a Boolean", NightModeReader.valueOf(CarPropertyValue.STATUS_AVAILABLE, 1))
        assertNull(NightModeReader.valueOf(CarPropertyValue.STATUS_AVAILABLE, null))
    }
}
