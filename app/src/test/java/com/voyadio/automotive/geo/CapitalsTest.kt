package com.voyadio.automotive.geo

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class CapitalsTest {
    private val capitals = Capitals.parse(
        """{"source":"test","names":{"VE":{"ko":"카라카스","ja":"カラカス","zh":"加拉加斯","zh-hant":"卡拉卡斯","pt-br":"Caracas BR","id":"Karakas"}}}"""
    )

    @Test fun `the capital in the car's language`() {
        assertEquals("카라카스", capitals.name("VE", "Caracas", Locale.KOREAN))
        assertEquals("カラカス", capitals.name("ve", "Caracas", Locale.JAPANESE))
    }

    @Test fun `English and unknown names fall back to the map`() {
        assertEquals("Caracas", capitals.name("VE", "Caracas", Locale.ENGLISH))
        assertEquals("Caracas", capitals.name("VE", "Caracas", Locale.GERMAN))
        assertEquals("Quito", capitals.name("EC", "Quito", Locale.KOREAN))
    }

    @Test fun `Chinese script and Brazilian Portuguese pick their own names`() {
        assertEquals("卡拉卡斯", capitals.name("VE", "Caracas", Locale.TRADITIONAL_CHINESE))
        assertEquals("卡拉卡斯", capitals.name("VE", "Caracas", Locale.forLanguageTag("zh-Hant")))
        assertEquals("加拉加斯", capitals.name("VE", "Caracas", Locale.SIMPLIFIED_CHINESE))
        assertEquals("Caracas BR", capitals.name("VE", "Caracas", Locale.forLanguageTag("pt-BR")))
    }

    @Test fun `old Android language codes map to the file's keys`() {
        assertEquals("id", Capitals.key(Locale("in")))
        assertEquals("he", Capitals.key(Locale("iw")))
        assertEquals("fil", Capitals.key(Locale("tl")))
    }

    @Test fun `bundled file covers the capitals the app shows`() {
        val file = listOf(File("src/main/assets/geo/capitals.json"), File("app/src/main/assets/geo/capitals.json")).first { it.exists() }
        val bundled = Capitals.parse(file.readText())
        assertEquals("서울", bundled.name("KR", "Seoul", Locale.KOREAN))
        assertEquals("도쿄", bundled.name("JP", "Tokyo", Locale.KOREAN))
        assertEquals("키토", bundled.name("EC", "Quito", Locale.KOREAN))
    }
}
