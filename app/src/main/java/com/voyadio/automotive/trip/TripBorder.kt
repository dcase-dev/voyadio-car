package com.voyadio.automotive.trip

/**
 * The country under the car, steadied (2026-10-21): a new country counts once it is seen [steady] times in a row,
 * so a GPS wobble along a border does not switch the radio back and forth. At sea or outside every known country
 * (null) changes nothing — a ferry crossing keeps counting toward the far shore.
 */
class TripBorder(private val steady: Int = 2) {
    var country: String? = null
        private set
    private var candidate: String? = null
    private var seen = 0

    /** A position fell in [code]. True when the car has crossed into a new country (the first fix is not one). */
    fun saw(code: String?): Boolean {
        if (code == null) return false
        if (country == null) { country = code; return false }
        if (code == country) { candidate = null; seen = 0; return false }
        if (code == candidate) seen++ else { candidate = code; seen = 1 }
        if (seen < steady) return false
        country = code; candidate = null; seen = 0
        return true
    }
}
