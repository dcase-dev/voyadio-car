package com.voyadio.automotive

import com.voyadio.automotive.drift.DriftState

import android.app.Application
import com.voyadio.automotive.data.CampRepository
import com.voyadio.automotive.data.SecureVault
import com.voyadio.automotive.data.VoyadioApi
import com.voyadio.automotive.geo.Capitals
import com.voyadio.automotive.geo.CountryLocator
import com.voyadio.automotive.geo.RoadNet
import com.voyadio.automotive.geo.RouteFinder
import com.voyadio.automotive.geo.SeaGrid
import com.voyadio.automotive.geo.WorldAtlas
import com.voyadio.automotive.pleos.PleosBridge
import com.voyadio.automotive.pleos.PleosConnect
import com.voyadio.automotive.trip.Trip
import com.voyadio.automotive.voyage.Voyage
import kotlinx.coroutines.flow.MutableStateFlow

class VoyadioApplication : Application() {
    val repository by lazy { CampRepository(VoyadioApi(BuildConfig.API_BASE), SecureVault(this)) }
    /** Pleos 차에서만 연결되는 Pleos Connect SDK 창구(2026-10-21) — 그 밖에서는 null(지금 동작 그대로). */
    val pleos: PleosConnect? by lazy { runCatching { PleosBridge.connect(this) }.getOrNull() }
    /** 지금 항로·최근 들은 방송 — 앱 화면과 재생 서비스가 함께 쓴다(2026-10-15 표류). */
    val drift by lazy { DriftState(getSharedPreferences("drift", MODE_PRIVATE)) }
    /** 길 위의 라디오(2026-10-21): 내비가 가는 길 — 재생 서비스가 목적지·지금 나라를 채운다. 목적지가 없으면(Pleos 밖도) null. */
    val trip = MutableStateFlow<Trip?>(null)
    /** 오늘의 항해(2026-10-22): 정차하는 순간 재생 서비스가 채우고, 다음 출발 때 비운다 — 정차 화면 지구본의 작은 별과 한 줄. */
    val voyage = MutableStateFlow<Voyage.Log?>(null)

    /** Bundled map, parsed once for the camp screen and the radio service. Null until [loadAtlas] has run. */
    @Volatile var atlas: WorldAtlas? = null
        private set

    /** Parses on first use, so call it off the main thread (~165 KB JSON). */
    fun loadAtlas(): WorldAtlas = atlas ?: synchronized(this) {
        atlas ?: assets.open("geo/world.json").bufferedReader().use { WorldAtlas.parse(it.readText()) }.also { atlas = it }
    }

    /** Capital names in the 50 app languages (~170 KB, parsed once; call off the main thread). Null until loaded or unreadable. */
    @Volatile var capitals: Capitals? = null
        private set

    fun loadCapitals(): Capitals? = capitals ?: synchronized(this) {
        capitals ?: runCatching { assets.open("geo/capitals.json").bufferedReader().use { Capitals.parse(it.readText()) } }.getOrNull()?.also { capitals = it }
    }

    @Volatile private var sea: SeaGrid? = null

    /**
     * Sea lanes that keep ferries off the land (~260 KB, parsed once; call off the main thread). Null when the
     * bundled file cannot be read — routes then fall back to straight ferry lines instead of losing the globe.
     */
    fun loadSea(): SeaGrid? = sea ?: synchronized(this) {
        sea ?: runCatching { assets.open("geo/sea.json").bufferedReader().use { SeaGrid.parse(it.readText()) } }.getOrNull()?.also { sea = it }
    }

    /** Route finder and country locator for the voice guide's distance (2026-10-21); built once, off the main thread. */
    @Volatile private var journeys: Pair<RouteFinder, CountryLocator>? = null

    fun loadJourneys(): Pair<RouteFinder, CountryLocator> = journeys ?: synchronized(this) {
        journeys ?: loadAtlas().let { atlas -> RouteFinder(atlas, loadSea(), loadRoads()) to CountryLocator(atlas) }.also { journeys = it }
    }

    @Volatile private var roads: RoadNet? = null

    /**
     * Major roads the land part of a drive follows (~1 MB, parsed once; call off the main thread). Null when the
     * bundled file cannot be read — land legs then stay straight lines.
     */
    fun loadRoads(): RoadNet? = roads ?: synchronized(this) {
        roads ?: runCatching { assets.open("geo/roads.json").bufferedReader().use { RoadNet.parse(it.readText()) } }.getOrNull()?.also { roads = it }
    }
}
