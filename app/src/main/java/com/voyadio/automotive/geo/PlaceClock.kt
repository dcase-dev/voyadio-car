package com.voyadio.automotive.geo

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The hour where a station broadcasts (2026-10-21): the voice guide's one more sentence while parked —
 * "그곳은 지금 오후 11:05". The country's zones come from the platform (ICU, by the caller); a country with several
 * takes its capital's — named outright for the big ones, else by the capital's name, else the zone whose standard
 * offset is nearest the capital's longitude. Pure (java.time).
 */
object PlaceClock {
    /** Capitals of countries spread over several zones, where the first zone or the nearest offset can be wrong. */
    private val CAPITAL_ZONES = mapOf(
        "US" to "America/New_York", "CA" to "America/Toronto", "MX" to "America/Mexico_City", "BR" to "America/Sao_Paulo",
        "AR" to "America/Argentina/Buenos_Aires", "CL" to "America/Santiago", "EC" to "America/Guayaquil",
        "RU" to "Europe/Moscow", "KZ" to "Asia/Almaty", "MN" to "Asia/Ulaanbaatar", "CN" to "Asia/Shanghai",
        "ID" to "Asia/Jakarta", "AU" to "Australia/Sydney", "NZ" to "Pacific/Auckland", "ES" to "Europe/Madrid",
        "PT" to "Europe/Lisbon", "CD" to "Africa/Kinshasa", "UA" to "Europe/Kiev", "DE" to "Europe/Berlin",
        "GL" to "America/Nuuk", "PG" to "Pacific/Port_Moresby", "MY" to "Asia/Kuala_Lumpur", "UZ" to "Asia/Tashkent",
        "CY" to "Asia/Nicosia", "PS" to "Asia/Hebron", "KI" to "Pacific/Tarawa", "FM" to "Pacific/Pohnpei",
    )

    fun zone(code: String, candidates: List<String>, capitalName: String?, capitalLon: Double?, now: Instant): ZoneId? {
        CAPITAL_ZONES[code]?.let { id -> runCatching { ZoneId.of(id) }.getOrNull()?.let { return it } }
        val zones = candidates.mapNotNull { runCatching { ZoneId.of(it) }.getOrNull() }
        if (zones.size <= 1) return zones.firstOrNull()
        capitalName?.let { name -> zones.firstOrNull { city(it) == name.lowercase(Locale.ROOT) } }?.let { return it }
        val lon = capitalLon ?: return null
        return zones.minByOrNull { abs(it.rules.getStandardOffset(now).totalSeconds / 3600.0 - lon / 15.0) }
    }

    /** "오후 11:05" in the car's language; null when the place keeps the car's own hour (nothing to tell). */
    fun spoken(zone: ZoneId, here: ZoneId, now: Instant, locale: Locale): String? {
        if (zone.rules.getOffset(now) == here.rules.getOffset(now)) return null
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(now)
    }

    /** "America/Mexico_City" → "mexico city". */
    private fun city(zone: ZoneId) = zone.id.substringAfterLast('/').replace('_', ' ').lowercase(Locale.ROOT)
}
