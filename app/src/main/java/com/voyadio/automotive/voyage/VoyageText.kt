package com.voyadio.automotive.voyage

import android.content.Context
import com.voyadio.automotive.R
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Today's voyage in words (50 languages): the parked screen's line and the car's sentence back in P. */
object VoyageText {
    /** "오늘의 항해 · 7개 나라 · 가장 먼 곳 나미비아 10,499km" */
    fun line(context: Context, log: Voyage.Log, name: (String) -> String): String = listOfNotNull(
        context.getString(R.string.voyage_title),
        countries(context, log),
        farthest(log, name)?.let { (place, km) -> context.getString(R.string.voyage_farthest, place, km) },
    ).joinToString(" · ")

    /** "오늘 달리는 동안 7개 나라의 방송을 만났어요. 가장 먼 곳은 나미비아, 10,499km" */
    fun spoken(context: Context, log: Voyage.Log, name: (String) -> String): String = listOfNotNull(
        context.getString(R.string.voyage_spoken, countries(context, log)),
        farthest(log, name)?.let { (place, km) -> context.getString(R.string.voyage_farthest_spoken, place, km) },
    ).joinToString(". ")

    private fun countries(context: Context, log: Voyage.Log) =
        context.resources.getQuantityString(R.plurals.voyage_countries, log.countries.size, log.countries.size)

    private fun farthest(log: Voyage.Log, name: (String) -> String): Pair<String, String>? {
        val code = log.farthest ?: return null
        val km = log.farthestKm ?: return null
        return name(code) to NumberFormat.getIntegerInstance().format(km.roundToInt())
    }
}
