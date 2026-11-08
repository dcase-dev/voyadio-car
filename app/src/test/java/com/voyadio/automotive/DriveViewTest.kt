package com.voyadio.automotive

import org.junit.Assert.*
import org.junit.Test

class DriveViewTest {
    private val from = { city: String -> "${city}에서 오는 방송" }

    @Test fun `country and capital make the journey line`() {
        assertEquals("Uruguay · Montevideo에서 오는 방송", DriveView.subtitle("Uruguay", "Montevideo", from))
    }

    @Test fun `unknown capital keeps just the country`() {
        assertEquals("Uruguay", DriveView.subtitle("Uruguay", null, from))
        assertEquals("Uruguay", DriveView.subtitle("Uruguay", " ", from))
    }

    @Test fun `blank country leaves no stray separator`() {
        assertEquals("Montevideo에서 오는 방송", DriveView.subtitle("", "Montevideo", from))
    }
}
