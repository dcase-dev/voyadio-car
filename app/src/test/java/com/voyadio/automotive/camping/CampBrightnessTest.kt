package com.voyadio.automotive.camping

import org.junit.Assert.assertEquals
import org.junit.Test

class CampBrightnessTest {
    @Test fun `each tap goes one step brighter and wraps back to the darkest`() {
        assertEquals(0.4f, CampBrightness.next(0.15f))
        assertEquals(0.7f, CampBrightness.next(0.4f))
        assertEquals(1f, CampBrightness.next(0.7f))
        assertEquals(0.15f, CampBrightness.next(1f))
    }

    @Test fun `an unknown value starts again from the darkest`() {
        assertEquals(0.15f, CampBrightness.next(0.33f))
    }

    @Test fun `a saved value outside the steps falls back to the night default`() {
        assertEquals(0.7f, CampBrightness.valid(0.7f))
        assertEquals(CampBrightness.DEFAULT, CampBrightness.valid(-1f))
        assertEquals(CampBrightness.DEFAULT, CampBrightness.valid(0.5f))
    }
}
