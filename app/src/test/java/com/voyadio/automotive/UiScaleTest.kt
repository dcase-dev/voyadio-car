package com.voyadio.automotive

import org.junit.Assert.*
import org.junit.Test

class UiScaleTest {
    @Test fun `a car never shrinks, even a small head unit`() {
        assertEquals(1f, UiScale.factor(1440, 800, automotive = true))
        assertEquals(1f, UiScale.factor(800, 400, automotive = true))
    }

    @Test fun `a roomy tablet keeps full size and nothing grows`() {
        assertEquals(1f, UiScale.factor(1400, 850, automotive = false))
        assertEquals(1f, UiScale.factor(3000, 2000, automotive = false))
    }

    @Test fun `a landscape phone shrinks to fit the low height`() {
        // Galaxy S25 landscape: about 732 × 336 dp between the bars.
        val factor = UiScale.factor(732, 336, automotive = false)
        assertEquals(336f / UiScale.REF_HEIGHT_DP, factor, 0.001f)
        // The scaled screen is wide enough for the side-by-side layout.
        assertTrue(732 / factor >= CampScreen.WIDE_DP)
    }

    @Test fun `a narrow screen is limited by its width`() {
        assertEquals(780f / UiScale.REF_WIDTH_DP, UiScale.factor(780, 900, automotive = false), 0.001f)
    }

    @Test fun `tiny screens stop at the legibility floor`() {
        assertEquals(UiScale.MIN, UiScale.factor(300, 200, automotive = false))
    }

    @Test fun `unknown size leaves the UI alone`() {
        assertEquals(1f, UiScale.factor(0, 0, automotive = false))
    }
}
