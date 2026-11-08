package com.voyadio.automotive

import android.content.Context
import com.voyadio.automotive.data.Station
import org.json.JSONObject
import java.util.Locale

/** The device's [CountryDirectory]: platform country names, continents from the bundled `geo/continents.json`. */
internal object Countries {
    @Volatile private var continents: Map<String, Continent>? = null

    /** A directory for the device language; [stations] add their own spelling of each country's name. */
    fun directory(context: Context, stations: Collection<Station>): CountryDirectory {
        val locale = Locale.getDefault()
        val spelled = stations.filter { it.countryCode.isNotBlank() && it.country.isNotBlank() }
            .associate { it.countryCode to it.country }
        val table = continents(context)
        return CountryDirectory({ code -> CountryDirectory.platformNames(code, locale, spelled[code].orEmpty()) },
            { table[it] ?: Continent.OTHER }, locale)
    }

    /** Country code → continent (scripts/build-car-continents.py, from the IANA time zone table); ~5 KB, read once. */
    private fun continents(context: Context): Map<String, Continent> = continents ?: synchronized(this) {
        continents ?: runCatching {
            val json = JSONObject(context.assets.open("geo/continents.json").bufferedReader().use { it.readText() })
            json.keys().asSequence().associateWith { code -> Continent.valueOf(json.getString(code)) }
        }.getOrDefault(emptyMap()).also { continents = it }
    }

    fun label(context: Context, continent: Continent): String = context.getString(when (continent) {
        Continent.ASIA -> R.string.continent_asia
        Continent.EUROPE -> R.string.continent_europe
        Continent.AFRICA -> R.string.continent_africa
        Continent.NORTH_AMERICA -> R.string.continent_north_america
        Continent.SOUTH_AMERICA -> R.string.continent_south_america
        Continent.OCEANIA -> R.string.continent_oceania
        Continent.OTHER -> R.string.continent_other
    })
}
