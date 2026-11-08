package com.voyadio.automotive.geo

import kotlin.math.roundToInt

/** A day or more: days + whole hours. Under a day: hours + minutes rounded to 10. */
data class TravelTime(val days: Int, val hours: Int, val minutes: Int) {
    companion object {
        fun of(totalHours: Double): TravelTime {
            val totalMinutes = (totalHours * 60).roundToInt()
            if (totalMinutes >= 24 * 60) {
                var days = totalMinutes / (24 * 60)
                var hours = ((totalMinutes % (24 * 60)) / 60.0).roundToInt()
                if (hours == 24) { days += 1; hours = 0 }
                return TravelTime(days, hours, 0)
            }
            var hours = totalMinutes / 60
            var minutes = ((totalMinutes % 60) / 10.0).roundToInt() * 10
            if (minutes == 60) { hours += 1; minutes = 0 }
            if (hours == 24) return TravelTime(1, 0, 0)
            return TravelTime(0, hours, minutes)
        }
    }
}
