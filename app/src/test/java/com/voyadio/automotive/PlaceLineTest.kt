package com.voyadio.automotive

import org.junit.Assert.*
import org.junit.Test

class PlaceLineTest {
    @Test fun `country and capital on one line`() {
        assertEquals("마르티니크 · Fort-de-France", PlaceLine.of("마르티니크", "Fort-de-France", "Martinique"))
    }

    @Test fun `a country the map gives no capital shows only its name`() {
        // The atlas falls back to the country's own name when there is no capital (Israel, Palestine, …).
        assertEquals("이스라엘", PlaceLine.of("이스라엘", "Israel", "Israel"))
        assertEquals("Chad", PlaceLine.of("Chad", null, null))
        assertEquals("Chad", PlaceLine.of("Chad", "", "Chad"))
    }

    @Test fun `flag emoji from a country code`() {
        assertEquals("🇰🇷", Flags.emoji("KR"))
        assertEquals("🇲🇶", Flags.emoji("mq"))
    }

    @Test fun `no flag for anything that is not two letters`() {
        assertNull(Flags.emoji(""))
        assertNull(Flags.emoji("KOR"))
        assertNull(Flags.emoji("1A"))
    }
}
