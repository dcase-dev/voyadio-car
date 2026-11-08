package com.voyadio.automotive.geo

import kotlin.math.ceil

/**
 * Turns hours at the wheel into elapsed hours for a real driver:
 * a 10-minute break after every 2 hours, and at most 12 hours of driving per day —
 * the rest of that day is spent resting, and driving resumes the next day.
 * Ferry time is not driving and is added separately, unchanged.
 */
object DriveSchedule {
    const val STINT_HOURS = 2.0
    const val BREAK_HOURS = 10.0 / 60
    const val DAILY_DRIVING_HOURS = 12.0
    private const val EPSILON = 1e-9

    fun elapsedHours(driving: Double): Double {
        if (driving <= 0) return 0.0
        val restDays = ceil(driving / DAILY_DRIVING_HOURS - EPSILON).toInt() - 1
        val lastDay = driving - restDays * DAILY_DRIVING_HOURS
        val breaks = ceil(lastDay / STINT_HOURS - EPSILON).toInt() - 1
        return restDays * 24.0 + lastDay + breaks * BREAK_HOURS
    }
}
