package com.voyadio.automotive.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveScheduleTest {
    private val tenMinutes = 10.0 / 60

    @Test fun `no driving takes no time`() {
        assertEquals(0.0, DriveSchedule.elapsedHours(0.0), 1e-9)
    }

    @Test fun `up to two hours needs no break`() {
        assertEquals(1.5, DriveSchedule.elapsedHours(1.5), 1e-9)
        assertEquals(2.0, DriveSchedule.elapsedHours(2.0), 1e-9)
    }

    @Test fun `a ten-minute break after every two hours, none after the last stretch`() {
        assertEquals(3.0 + tenMinutes, DriveSchedule.elapsedHours(3.0), 1e-9)
        assertEquals(6.0 + 2 * tenMinutes, DriveSchedule.elapsedHours(6.0), 1e-9)
    }

    @Test fun `a full twelve-hour day has five breaks and no overnight stop`() {
        assertEquals(12.0 + 5 * tenMinutes, DriveSchedule.elapsedHours(12.0), 1e-9)
    }

    @Test fun `beyond twelve hours the rest of the day is spent resting`() {
        assertEquals(24.0 + 1.0, DriveSchedule.elapsedHours(13.0), 1e-9)
        assertEquals(48.0 + 6.0 + 2 * tenMinutes, DriveSchedule.elapsedHours(30.0), 1e-9)
    }

    @Test fun `floating error at a day boundary does not add a day`() {
        assertEquals(12.0 + 5 * tenMinutes, DriveSchedule.elapsedHours(0.1 * 120), 1e-9)
    }
}
