package com.voyadio.automotive.trip

import com.voyadio.automotive.drift.Course
import com.voyadio.automotive.drift.Courses
import com.voyadio.automotive.geo.LatLon

/** The navigation's destination (Pleos NaviHelper): its name and place. */
data class Destination(val name: String, val place: LatLon)

/**
 * The road's radio (2026-10-21, design docs/superpowers/specs/2026-10-21-car-pleos-sdk-design.md §4): the trip the
 * car's navigation is on — the destination, its country [to], and the country under the car now [here] (null until
 * the first fix). Country by country: the stations carry no position finer than their country yet.
 */
data class Trip(val destination: Destination, val to: String, val here: String? = null) {
    /** The '가는 길' course: the country underfoot, then the destination's. */
    val course: Course.Countries get() = Courses.trip(here, to)
}
