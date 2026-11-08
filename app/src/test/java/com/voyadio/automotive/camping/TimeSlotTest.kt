package com.voyadio.automotive.camping

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeSlotTest {
    @Test fun `boundaries match the server`() {
        // cloudflare/moods.js slotOf: 아침 5–9 · 낮 10–17 · 저녁 18–21 · 밤 22–4
        assertEquals(TimeSlot.NIGHT, TimeSlot.of(4))
        assertEquals(TimeSlot.MORNING, TimeSlot.of(5))
        assertEquals(TimeSlot.MORNING, TimeSlot.of(9))
        assertEquals(TimeSlot.DAY, TimeSlot.of(10))
        assertEquals(TimeSlot.DAY, TimeSlot.of(17))
        assertEquals(TimeSlot.EVENING, TimeSlot.of(18))
        assertEquals(TimeSlot.EVENING, TimeSlot.of(21))
        assertEquals(TimeSlot.NIGHT, TimeSlot.of(22))
        assertEquals(TimeSlot.NIGHT, TimeSlot.of(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `24 is not an hour`() { TimeSlot.of(24) }
}
