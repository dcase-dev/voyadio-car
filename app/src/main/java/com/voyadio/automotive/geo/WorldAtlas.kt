package com.voyadio.automotive.geo

import org.json.JSONArray
import org.json.JSONObject

data class Country(
    val code: String, val name: String, val capitalName: String,
    val capital: LatLon, val port: LatLon?, val neighbours: Set<String>,
)

/** Bundled map data. Outline rings are flat [lat, lon, lat, lon, …] arrays. */
class WorldAtlas(val countries: Map<String, Country>, val outlines: Map<String, List<DoubleArray>>) {
    companion object {
        fun parse(text: String): WorldAtlas {
            val root = JSONObject(text)
            val list = root.getJSONArray("countries")
            val countries = (0 until list.length()).associate { i ->
                val o = list.getJSONObject(i)
                val code = o.getString("cc")
                val neighbours = o.getJSONArray("neighbours")
                code to Country(
                    code = code,
                    name = o.getString("name"),
                    capitalName = o.optString("capitalName").ifBlank { o.getString("name") },
                    capital = o.getJSONArray("capital").latLon(),
                    port = o.optJSONArray("port")?.latLon(),
                    neighbours = (0 until neighbours.length()).map(neighbours::getString).toSet(),
                )
            }
            val shapes = root.getJSONObject("outlines")
            val outlines = shapes.keys().asSequence().associateWith { key ->
                val rings = shapes.getJSONArray(key)
                (0 until rings.length()).map { r ->
                    val flat = rings.getJSONArray(r)
                    DoubleArray(flat.length()) { flat.getDouble(it) }
                }
            }
            return WorldAtlas(countries, outlines)
        }

        private fun JSONArray.latLon() = LatLon(getDouble(0), getDouble(1))
    }
}
