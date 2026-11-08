package com.voyadio.automotive.camping

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryGuardTest {
    @Test fun `above the floor never stops`() {
        val guard = BatteryGuard()
        assertFalse(guard.shouldStop(percent = 21, playing = true, charging = false))
    }

    @Test fun `at the floor while playing stops once`() {
        val guard = BatteryGuard()
        assertTrue(guard.shouldStop(20, playing = true, charging = false))
        // 사용자가 다시 틀었다: 같은 캠핑 화면에서는 다시 끄지 않는다.
        assertFalse(guard.shouldStop(18, playing = true, charging = false))
    }

    @Test fun `nothing playing does not use up the one stop`() {
        val guard = BatteryGuard()
        assertFalse(guard.shouldStop(15, playing = false, charging = false))
        assertTrue(guard.shouldStop(15, playing = true, charging = false))
    }

    @Test fun `charging never stops`() {
        assertFalse(BatteryGuard().shouldStop(10, playing = true, charging = true))
    }

    @Test fun `entering camping again re-arms`() {
        val guard = BatteryGuard()
        assertTrue(guard.shouldStop(19, playing = true, charging = false))
        guard.reset()
        assertTrue(guard.shouldStop(19, playing = true, charging = false))
    }
}
