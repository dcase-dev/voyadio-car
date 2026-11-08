package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorldAtlasTest {
    @Test fun `parses countries, ports and flat outline rings`() {
        val atlas = WorldAtlas.parse("""
            {"version":1,"countries":[
              {"cc":"AA","name":"Alpha","capitalName":"Alphaville","capital":[10.0,20.0],"port":[10.5,20.5],"neighbours":["BB"]},
              {"cc":"BB","name":"Beta","capitalName":"","capital":[11.0,21.0],"port":null,"neighbours":["AA"]}],
             "outlines":{"AA":[[0.0,0.0,0.0,1.0,1.0,1.0,1.0,0.0]]}}
        """.trimIndent())
        val alpha = atlas.countries.getValue("AA")
        assertEquals("Alphaville", alpha.capitalName)
        assertEquals(LatLon(10.0, 20.0), alpha.capital)
        assertEquals(LatLon(10.5, 20.5), alpha.port)
        assertEquals(setOf("BB"), alpha.neighbours)
        assertNull(atlas.countries.getValue("BB").port)
        assertEquals("Beta", atlas.countries.getValue("BB").capitalName)
        assertEquals(8, atlas.outlines.getValue("AA").single().size)
    }

    @Test fun `bundled world data is complete enough to draw and route`() {
        val atlas = WorldAtlas.parse(File("src/main/assets/geo/world.json").readText())
        assertTrue(atlas.countries.size >= 200)
        assertTrue(atlas.outlines.size >= 150)
        assertTrue("KP" in atlas.countries.getValue("KR").neighbours)
        assertTrue(atlas.countries.getValue("JP").neighbours.isEmpty())
        assertNotNull(atlas.countries.getValue("KR").port)
    }
}
