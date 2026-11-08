package com.voyadio.automotive.voyage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoyageTest {
    @Test fun `nothing is kept before the car leaves P`() {
        val voyage = Voyage()
        voyage.heard("JP", 1_000.0)
        assertFalse(voyage.sailing)
        assertNull("not sailing, nothing to dock", voyage.dock())
    }

    @Test fun `the countries heard on the road, each once, in order`() {
        val voyage = Voyage()
        voyage.depart()
        voyage.heard("NA", 10_499.0); voyage.heard("JP", 1_200.0); voyage.heard("NA", 10_400.0); voyage.heard("FR", null)
        val log = voyage.dock()!!
        assertEquals(listOf("NA", "JP", "FR"), log.countries)
        assertEquals("NA", log.farthest)
        assertEquals(10_499.0, log.farthestKm!!, 0.1)
        assertFalse("back in P", voyage.sailing)
    }

    @Test fun `one country is no voyage to tell`() {
        val voyage = Voyage()
        voyage.depart()
        voyage.heard("KR", 20.0); voyage.heard("KR", 25.0)
        assertNull(voyage.dock())
    }

    @Test fun `without the car's place there is no farthest`() {
        val voyage = Voyage()
        voyage.depart()
        voyage.heard("DE", null); voyage.heard("FR", null)
        val log = voyage.dock()!!
        assertEquals(listOf("DE", "FR"), log.countries)
        assertNull(log.farthest); assertNull(log.farthestKm)
    }

    @Test fun `a new drive starts a new voyage`() {
        val voyage = Voyage()
        voyage.depart(); voyage.heard("DE", 900.0); voyage.heard("FR", 950.0); voyage.dock()
        voyage.depart()
        assertTrue(voyage.sailing)
        voyage.heard("JP", 1_200.0); voyage.heard("KR", 10.0)
        assertEquals(listOf("JP", "KR"), voyage.dock()!!.countries)
    }

    @Test fun `a parking manoeuvre meets nothing, a short hop meets one`() {
        val voyage = Voyage()
        voyage.depart()
        assertNull("R and back to P", voyage.dock())
        assertEquals(0, voyage.met)
        voyage.depart(); voyage.heard("KR", 3.0); voyage.heard("KR", 4.0)
        assertNull(voyage.dock())
        assertEquals(1, voyage.met)
    }

    @Test fun `a blank country code is not a country`() {
        val voyage = Voyage()
        voyage.depart(); voyage.heard("", 5.0); voyage.heard("JP", 1.0); voyage.heard("KR", 2.0)
        assertEquals(listOf("JP", "KR"), voyage.dock()!!.countries)
    }
}
