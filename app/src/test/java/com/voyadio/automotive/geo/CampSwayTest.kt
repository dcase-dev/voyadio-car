package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CampSwayTest {
    private val panama = LatLon(9.0, -79.5)

    @Test fun `it starts on the held point`() {
        val p = CampSway.at(panama, 0, 1.0)
        assertEquals(panama.lat, p.lat, 1e-9)
        assertEquals(panama.lon, p.lon, 1e-9)
    }

    @Test fun `it stays within the swing and comes back each period`() {
        for (t in 0L..CampSway.PERIOD_MS step 1_000) {
            val p = CampSway.at(panama, t, 1.0)
            assertTrue(abs(p.lon - panama.lon) <= CampSway.LON_DEG + 1e-9)
            assertTrue(abs(p.lat - panama.lat) <= CampSway.LAT_DEG + 1e-9)
        }
        val again = CampSway.at(panama, CampSway.PERIOD_MS, 1.0)
        assertEquals(panama.lon, again.lon, 1e-9)
    }

    @Test fun `it really moves, slowly`() {
        val quarter = CampSway.at(panama, CampSway.PERIOD_MS / 4, 1.0)
        assertEquals(panama.lon + CampSway.LON_DEG, quarter.lon, 1e-9)
        // About a seventh of a second apart (one frame) the camera moves well under a tenth of a degree.
        val a = CampSway.at(panama, 10_000, 1.0); val b = CampSway.at(panama, 10_150, 1.0)
        assertTrue(abs(b.lon - a.lon) < 0.1)
    }

    @Test fun `a zoomed-in globe swings less`() {
        val wide = CampSway.at(panama, CampSway.PERIOD_MS / 4, 1.0)
        val zoomed = CampSway.at(panama, CampSway.PERIOD_MS / 4, 4.0)
        assertEquals(CampSway.LON_DEG / 4, zoomed.lon - panama.lon, 1e-9)
        assertTrue(abs(zoomed.lon - panama.lon) < abs(wide.lon - panama.lon))
    }

    @Test fun `it wraps across the date line`() {
        val p = CampSway.at(LatLon(-17.7, 178.0), CampSway.PERIOD_MS / 4, 1.0) // Fiji
        assertEquals(-174.0, p.lon, 1e-9)
    }
}
