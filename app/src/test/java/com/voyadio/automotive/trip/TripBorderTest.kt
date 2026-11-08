package com.voyadio.automotive.trip

import com.voyadio.automotive.drift.Courses
import com.voyadio.automotive.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripBorderTest {
    @Test fun `the first fix is where the trip starts, not a crossing`() {
        val border = TripBorder()
        assertFalse(border.saw("FR"))
        assertEquals("FR", border.country)
    }

    @Test fun `a new country counts once it is seen twice in a row`() {
        val border = TripBorder()
        border.saw("FR")
        assertFalse("one reading over the line may be the GPS wobbling", border.saw("DE"))
        assertTrue(border.saw("DE"))
        assertEquals("DE", border.country)
        assertFalse("already there", border.saw("DE"))
    }

    @Test fun `a wobble back and forth along the border is no crossing`() {
        val border = TripBorder()
        border.saw("FR")
        for (code in listOf("DE", "FR", "DE", "FR", "DE", "FR")) assertFalse(code, border.saw(code))
        assertEquals("FR", border.country)
    }

    @Test fun `at sea or nowhere known changes nothing`() {
        val border = TripBorder()
        assertFalse(border.saw(null))
        assertNull(border.country)
        border.saw("KR")
        assertFalse(border.saw(null))
        assertFalse("a ferry crossing at sea does not reset the count", border.saw("JP"))
        assertFalse(border.saw(null))
        assertTrue(border.saw("JP"))
    }

    @Test fun `the trip course runs from the country underfoot to the destination's`() {
        val berlin = Destination("Berlin Hbf", LatLon(52.525, 13.369))
        assertEquals(Courses.trip("FR", "DE"), Trip(berlin, to = "DE", here = "FR").course)
        assertEquals(listOf("DE"), Trip(berlin, to = "DE", here = "DE").course.codes)
        assertEquals("before the first fix, just the destination", listOf("DE"), Trip(berlin, to = "DE").course.codes)
    }
}
