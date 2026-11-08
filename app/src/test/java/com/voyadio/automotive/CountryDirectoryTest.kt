package com.voyadio.automotive

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CountryDirectoryTest {
    private val korean = Locale.KOREAN
    private val codes = listOf("JP", "KR", "US", "GB", "BR", "UY", "NL", "ZA", "AU", "IN", "CN", "AQ")
    private val continents = mapOf(
        "JP" to Continent.ASIA, "KR" to Continent.ASIA, "IN" to Continent.ASIA, "CN" to Continent.ASIA,
        "US" to Continent.NORTH_AMERICA, "BR" to Continent.SOUTH_AMERICA, "UY" to Continent.SOUTH_AMERICA,
        "GB" to Continent.EUROPE, "NL" to Continent.EUROPE, "ZA" to Continent.AFRICA, "AU" to Continent.OCEANIA,
    )
    private val directory = CountryDirectory({ CountryDirectory.platformNames(it, korean) }, { continents[it] ?: Continent.OTHER }, korean)

    @Test fun `Korean, English, code and spoken requests all find Japan first`() {
        for (q in listOf("일본", "일본 라디오", "일본 라디오 틀어줘", "보야디오에서 일본 라디오 틀어줘", "Japan", "japan radio", "JP", "일본의 방송")) {
            assertEquals(q, "JP", directory.find(q, codes).firstOrNull())
        }
    }

    @Test fun `partial names find by start, and filler alone finds nothing`() {
        assertEquals("NL", directory.find("네덜", codes).first())
        assertEquals("UY", directory.find("urug", codes).first())
        assertTrue(directory.find("라디오", codes).isEmpty())
        assertTrue(directory.find("  ", codes).isEmpty())
        assertTrue(directory.find("아틀란티스", codes).isEmpty())
    }

    @Test fun `a spoken particle stuck to the name still finds the country, the longest name first`() {
        // The talk button hears "일본으로 바꿔줘" — "일본으로" is no country's name until the particle comes off.
        assertEquals("JP", directory.find("일본으로 바꿔줘", codes).firstOrNull())
        assertEquals("US", directory.find("미국으로 가자", codes).firstOrNull())
        // The demo video's spoken request (PO script, 2026-10-22).
        assertEquals("US", directory.find("미국 채널을 찾아줘", codes).firstOrNull())
        assertEquals("인도네시아로 is Indonesia, not India", "ID", directory.find("인도네시아로", codes + "ID").firstOrNull())
        assertTrue(directory.find("아틀란티스로", codes).isEmpty())
    }

    @Test fun `a whole name wins over a name that only contains it`() {
        // "인도" is India, and also inside "인도네시아"; India must come first.
        val found = directory.find("인도", codes + "ID")
        assertEquals("IN", found.first())
        assertTrue("ID" in found)
    }

    @Test fun `lists sort by the device language with recent and favourite countries on top`() {
        val list = directory.sorted(codes, first = listOf("UY", "JP", "XX"))
        assertEquals(listOf("UY", "JP"), list.take(2))
        val rest = list.drop(2).map(directory::label)
        assertEquals(rest.sortedWith(java.text.Collator.getInstance(korean)), rest)
        assertEquals("일본", directory.label("JP"))
    }

    @Test fun `countries group by continent in a fixed order`() {
        val groups = directory.byContinent(codes)
        assertEquals(listOf(Continent.ASIA, Continent.EUROPE, Continent.AFRICA, Continent.NORTH_AMERICA, Continent.SOUTH_AMERICA, Continent.OCEANIA, Continent.OTHER),
            groups.map { it.first })
        assertEquals(listOf("AQ"), groups.last().second)
        assertEquals(setOf("JP", "KR", "IN", "CN"), groups.first().second.toSet())
    }

    @Test fun `the bundled continent table covers every country on the map`() {
        val table = org.json.JSONObject(java.io.File("src/main/assets/geo/continents.json").readText())
        val world = org.json.JSONObject(java.io.File("src/main/assets/geo/world.json").readText()).getJSONArray("countries")
        val missing = (0 until world.length()).map { world.getJSONObject(it).getString("cc") }.filterNot(table::has)
        assertEquals(emptyList<String>(), missing)
        for (code in table.keys()) Continent.valueOf(table.getString(code)) // every value is a known continent
        assertEquals("ASIA", table.getString("KR")); assertEquals("SOUTH_AMERICA", table.getString("UY"))
        assertEquals("NORTH_AMERICA", table.getString("MX")); assertEquals("OCEANIA", table.getString("NZ"))
    }
}
