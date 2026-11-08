package com.voyadio.automotive

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AppLanguagesTest {
    private val supported = listOf("en-US", "ko", "ja", "zh", "zh-Hant", "pt", "pt-BR", "de").map(Locale::forLanguageTag)

    @Test fun `each language is named in itself`() {
        assertEquals("한국어", AppLanguages.name(Locale.forLanguageTag("ko"), supported))
        assertEquals("日本語", AppLanguages.name(Locale.forLanguageTag("ja"), supported))
        assertEquals("Deutsch", AppLanguages.name(Locale.forLanguageTag("de"), supported))
        assertEquals("English", AppLanguages.name(Locale.forLanguageTag("en-US"), supported))
    }

    @Test fun `a shared language shows its region or script`() {
        val zh = AppLanguages.name(Locale.forLanguageTag("zh"), supported)
        val hant = AppLanguages.name(Locale.forLanguageTag("zh-Hant"), supported)
        val pt = AppLanguages.name(Locale.forLanguageTag("pt"), supported)
        val br = AppLanguages.name(Locale.forLanguageTag("pt-BR"), supported)
        assertNotEquals(zh, hant)
        assertNotEquals(pt, br)
        assertTrue(br, br.contains("Brasil"))
    }

    @Test fun `the chosen language finds its row`() {
        assertEquals(supported.indexOf(Locale.forLanguageTag("ja")), AppLanguages.indexOf(Locale.forLanguageTag("ja-JP"), supported))
        assertEquals(supported.indexOf(Locale.forLanguageTag("pt-BR")), AppLanguages.indexOf(Locale.forLanguageTag("pt-BR"), supported))
        assertEquals(supported.indexOf(Locale.forLanguageTag("pt")), AppLanguages.indexOf(Locale.forLanguageTag("pt-PT"), supported))
        assertEquals(supported.indexOf(Locale.forLanguageTag("zh-Hant")), AppLanguages.indexOf(Locale.forLanguageTag("zh-TW"), supported))
        assertEquals(supported.indexOf(Locale.forLanguageTag("zh")), AppLanguages.indexOf(Locale.forLanguageTag("zh-CN"), supported))
        assertEquals(-1, AppLanguages.indexOf(Locale.forLanguageTag("eo"), supported))
    }

    @Test fun `sorting keeps every language once`() {
        val sorted = AppLanguages.sorted(supported)
        assertEquals(supported.toSet(), sorted.toSet())
        assertEquals(supported.size, sorted.size)
    }
}
