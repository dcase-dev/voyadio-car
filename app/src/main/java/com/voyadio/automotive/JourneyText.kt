package com.voyadio.automotive

import android.content.Context
import com.voyadio.automotive.geo.GlobeZoom
import com.voyadio.automotive.geo.Route
import com.voyadio.automotive.geo.TravelTime
import java.text.NumberFormat
import kotlin.math.roundToInt

/** The journey line under a station, shared by the main screen and the voice guide (one wording, 50 languages). */
object JourneyText {
    fun detail(context: Context, route: Route, approximate: Boolean): String {
        val time = TravelTime.of(route.hours)
        val duration = if (time.days > 0) context.getString(R.string.duration_days_hours, time.days, time.hours)
            else context.getString(R.string.duration_hours_minutes, time.hours, time.minutes)
        // The drive's own distance (roads and sea), like the time beside it (design §5-1).
        val km = NumberFormat.getIntegerInstance().format(route.km.roundToInt())
        return buildString {
            // Right around here the globe shows two pins and no drive (GlobeZoom.LOCAL_KM), so no distance either.
            if (route.directKm <= GlobeZoom.LOCAL_KM) append(context.getString(R.string.journey_local))
            else append(context.getString(R.string.journey_detail, km, duration))
            if (route.ferries > 0) append(' ').append(context.getString(R.string.journey_ferries, route.ferries))
            if (approximate) append(" · ").append(context.getString(R.string.journey_approximate))
        }
    }

    /** While the car moves the voice guide keeps it short (2026-10-21): how far, not how long. */
    fun distance(context: Context, route: Route): String =
        if (route.directKm <= GlobeZoom.LOCAL_KM) context.getString(R.string.journey_local)
        else context.getString(R.string.journey_km, NumberFormat.getIntegerInstance().format(route.km.roundToInt()))
}
