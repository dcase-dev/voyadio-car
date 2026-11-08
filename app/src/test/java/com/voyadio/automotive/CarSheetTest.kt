package com.voyadio.automotive

import org.junit.Assert.assertEquals
import org.junit.Test

class CarSheetTest {
    @Test fun `card keeps its width on a wide screen`() {
        assertEquals(860, CarSheet.width(860, 1920))
        assertEquals(1060, CarSheet.width(1060, 1440))
    }

    @Test fun `card leaves a 32 dp margin each side on a narrow screen`() {
        assertEquals(736, CarSheet.width(860, 800))
        assertEquals(800 - 64, CarSheet.width(1060, 800))
    }

    @Test fun `card width never goes negative`() {
        assertEquals(0, CarSheet.width(860, 40))
    }

    @Test fun `low wide screens list in two columns`() {
        assertEquals(2, CarSheet.columns(1444, 667))  // phone in landscape at the UiScale density
        assertEquals(2, CarSheet.columns(1440, 540))  // 1920×720 head unit at 1.33
    }

    @Test fun `screens with room keep one column`() {
        assertEquals(1, CarSheet.columns(1440, 800))  // the reference car display
        assertEquals(1, CarSheet.columns(1000, 600))  // too narrow for two
        assertEquals(1, CarSheet.columns(1444, 0))    // unknown height
    }
}
