package com.voyadio.automotive

import android.Manifest
import android.app.Dialog
import android.content.ComponentName
import android.graphics.*
import android.app.LocaleConfig
import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.annotation.RequiresApi
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.voyadio.automotive.pleos.PleosBridge
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.voyadio.automotive.camping.BatteryGuard
import com.voyadio.automotive.camping.CampBrightness
import com.voyadio.automotive.camping.Mood
import com.voyadio.automotive.data.ApiFailure
import com.voyadio.automotive.data.CampState
import com.voyadio.automotive.data.Station
import com.voyadio.automotive.drift.Course
import com.voyadio.automotive.drift.CourseLabels
import com.voyadio.automotive.drift.Courses
import com.voyadio.automotive.drift.DriftState
import com.voyadio.automotive.energy.EnergyReading
import com.voyadio.automotive.energy.VehicleEnergy
import com.voyadio.automotive.energy.batteryPercent
import com.voyadio.automotive.geo.CountryLocator
import com.voyadio.automotive.geo.FavoriteGlow
import com.voyadio.automotive.geo.LatLon
import com.voyadio.automotive.geo.NightData
import com.voyadio.automotive.geo.NightLights
import com.voyadio.automotive.geo.Route
import com.voyadio.automotive.speech.VoiceGuide
import com.voyadio.automotive.voice.VoiceCommand
import com.voyadio.automotive.geo.RouteFinder
import com.voyadio.automotive.geo.WorldAtlas
import com.voyadio.automotive.voyage.Voyage
import com.voyadio.automotive.voyage.VoyageText
import kotlinx.coroutines.*
import java.text.NumberFormat
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToInt

@UnstableApi
class CampActivity : ComponentActivity() {
    private val repo get() = (application as VoyadioApplication).repository
    private val drift get() = (application as VoyadioApplication).drift
    /** The road's radio (2026-10-21): the navigation's trip, kept by the radio service; null without a destination. */
    private val trip get() = (application as VoyadioApplication).trip
    private val voyage get() = (application as VoyadioApplication).voyage
    private var browser: MediaBrowser? = null
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private lateinit var gate: ParkedGate
    private var parked = false
    private lateinit var ui: CampScreen
    /** The one open dialog (a [CarSheet] or a remaining platform dialog); closed when driving starts and in onStop. */
    private var dialog: Dialog? = null
    private var linking: Job? = null
    private var atlas: WorldAtlas? = null
    private var routeFinder: RouteFinder? = null
    private var locator: CountryLocator? = null
    private var journeyKey: String? = null
    private var journeyJob: Job? = null
    private var askedLocation = false
    private var cachedHere: LatLon? = null
    private var hereReadAt = 0L
    private var locating = false
    private var locateAskedAt = 0L
    private var locateSignal: androidx.core.os.CancellationSignal? = null
    private lateinit var energy: VehicleEnergy
    private lateinit var camping: CampingScreen
    /** 캠핑 화면이 떠 있는가(정차 모드 안의 화면 하나, 설계 2026-10-13 §1). */
    private var inCamp = false
    private var moods: List<Mood> = emptyList()
    private var moodId: String? = null
    private var moodsFailed = false
    private var moodJob: Job? = null
    private var reading: EnergyReading? = null
    private var askedEnergy = false
    /** 이번 캠핑 화면에서 배터리 보호가 라디오를 껐다 — 다시 틀 때까지 안내를 화면에 남긴다. */
    private var batterySaved = false
    private val guard = BatteryGuard()
    /** 캠핑 화면 밝기(2026-10-15): 기본 15%, 해 버튼으로 바꾸고 기억한다. */
    private var campBrightness = CampBrightness.DEFAULT
    /**
     * 켜면 나온다(PO 2026-10-21): 운전 중에 '듣기 시작'을 찾아 누르는 게 더 위험하다.
     * 사용자가 아이콘을 눌러 앱을 새로 열었을 때만 — 그 자체가 앱을 고른 행동이다(구글 MA-1·Pleos '명시적 사용자 행동').
     * 차가 재생 서비스에 붙기만 한 경우·화면 회전·돌아오기는 해당하지 않는다. 0 = 기다리지 않음.
     */
    private var autoStartUntil = 0L
    private val energyRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && inCamp) energy.start() else renderCamping()
    }
    private val locationRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        (application as VoyadioApplication).pleos?.onPermissionsChanged()
        journeyKey = null; hereReadAt = 0L
        if (!locating) locateAskedAt = 0L   // permission just arrived: ask now, unless a request is in flight
        askForFix()
        render(repo.state.value)
    }

    /** Phones and small tablets get the same layout at a smaller density ([UiScale]); a car stays at full size. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.hasCategory(android.content.Intent.CATEGORY_LAUNCHER) == true) {
            autoStartUntil = SystemClock.elapsedRealtime() + AUTO_START_WINDOW_MS
        }
        // The system can rebuild the screen right after launch (2026-10-21: the first start after changing the
        // per-app language, CONFIG_SCREEN_LAYOUT) — the pending start carries over; one already used or cancelled is 0.
        savedInstanceState?.getLong(AUTO_START)?.let { autoStartUntil = it }
        askedLocation = savedInstanceState?.getBoolean("asked_location") ?: false
        inCamp = savedInstanceState?.getBoolean("camping") ?: false
        moodId = savedInstanceState?.getString("camp_mood")
        campBrightness = CampBrightness.valid(getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(BRIGHTNESS, CampBrightness.DEFAULT))
        energy = VehicleEnergy(this) { value -> runOnUiThread { reading = value; renderCamping() } }
        buildScreen()
        gate = ParkedGate(this) { allowed -> runOnUiThread {
            parked = allowed
            if (!allowed) blackout(false) // 주행 중엔 잠금 화면이 덮는다; 다시 정차하면 캠핑 화면이 밝게 보인다
            // The globe keeps turning only on the parked main screen; driving shows the still lock screen, as before.
            showLayers()
            if (allowed) askLocationOnce() else { linking?.cancel(); dialog?.dismiss() }
        } }
        val future = MediaBrowser.Builder(this, SessionToken(this, ComponentName(this, RadioService::class.java)))
            .setListener(object : MediaBrowser.Listener {
                // The talk button appears once the car's recognizer is up (it starts with the radio service).
                override fun onAvailableSessionCommandsChanged(controller: MediaController, commands: SessionCommands) {
                    ui.talk.isVisible = commands.contains(VoiceCommand.TALK)
                }
            }).buildAsync()
        browserFuture = future
        future.addListener({
            if (isDestroyed) return@addListener
            runCatching { future.get() }.onSuccess { connected ->
                browser = connected
                ui.talk.isVisible = connected.isSessionCommandAvailable(VoiceCommand.TALK)
                connected.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { render(repo.state.value) }
                })
                render(repo.state.value)
            }.onFailure { ui.status.setText(R.string.network_error) }
        }, ContextCompat.getMainExecutor(this))
        lifecycleScope.launch {
            val (loaded, sea, roads) = withContext(Dispatchers.IO) {
                // Sea lanes keep ferries off the land (2026-10-13), roads shape the land (2026-10-15);
                // only the camp screen plans routes, so it loads them.
                val app = application as VoyadioApplication
                app.loadCapitals()
                Triple(app.loadAtlas(), app.loadSea(), app.loadRoads())
            }
            atlas = loaded; routeFinder = RouteFinder(loaded, sea, roads); locator = CountryLocator(loaded)
            ui.globe.atlas = loaded
            renderVoyage()
            render(repo.state.value)
            // Decoration only, but ~5k points: never on the main thread (ANR lesson, 2026-09-20).
            val night = withContext(Dispatchers.Default) { NightData.of(NightLights.lights(loaded), NightLights.web(loaded)) }
            ui.globe.setNight(night)
        }
        lifecycleScope.launch { repo.state.collect(::render) }
        // The navigation's destination: a small flag on the globe, and '가는 길' in the course button while it plays.
        lifecycleScope.launch { trip.collect { ui.globe.tripEnd = it?.destination?.place; render(repo.state.value) } }
        // Today's voyage (2026-10-22): back in P, the countries met on the road light up as small stars, with one line.
        lifecycleScope.launch { voyage.collect { renderVoyage(it) } }
        // 밤을 넘기는 캠핑: 시간대가 바뀌면(저녁 → 밤) 분위기도 바뀐다. 1분에 한 번만 본다(기억이 싱싱하면 아무것도 안 함).
        lifecycleScope.launch { while (isActive) { delay(60_000); if (inCamp && parked) loadMoods() } }
    }
    override fun onStart() {
        super.onStart(); gate.start()
        lifecycleScope.launch {
            repo.sync()
            // 폰에서 항로를 바꿨으면 차도 따른다(계정 항로 공유).
            runCatching { repo.loadCourse() }.getOrNull()?.let { if (drift.adoptFromServer(it)) render(repo.state.value) }
        }
        if (inCamp) { dim(campBrightness); loadMoods(); startCampEnergy() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putBoolean("asked_location", askedLocation)
        outState.putBoolean("camping", inCamp); outState.putString("camp_mood", moodId)
        outState.putLong(AUTO_START, autoStartUntil)
    }
    private fun askLocationOnce() {
        // Pleos cars also need their own location permission (Pleos FusedLocation SDK) — one dialog for both.
        val wanted = listOf(Manifest.permission.ACCESS_COARSE_LOCATION) + PleosBridge.permissions
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (!askedLocation && missing.isNotEmpty()) {
            askedLocation = true
            locationRequest.launch(missing.toTypedArray())
        }
    }
    override fun onStop() {
        // Left before the list was ready: coming back is not a new launch. A rebuild (isChangingConfigurations) keeps it.
        if (!isChangingConfigurations) autoStartUntil = 0L
        linking?.cancel(); dialog?.dismiss(); energy.stop(); gate.stop()
        reading = null // 화면이 가려지기 전 값으로 보호 판단을 하지 않는다
        super.onStop()
    }
    override fun onDestroy() { locateSignal?.cancel(); browserFuture?.let(MediaBrowser::releaseFuture); super.onDestroy() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    /** Every on-screen control only works while parked (the gate also hides the screen while driving). */
    private fun View.onTap(action: () -> Unit) = setOnClickListener { if (parked) action() }
    private fun buildScreen() {
        ui = CampScreen(this)
        // In-app language (2026-10-21) — the system's per-app language store, so it matches Settings › Apps › Language.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ui.language.text = languageName()
            ui.language.contentDescription = getString(R.string.language) + " · " + ui.language.text
            ui.language.onTap { showLanguages() }
        } else ui.language.isVisible = false
        // Voice guide (2026-10-21): the radio says where each new station comes from; this switch silences it.
        renderVoice()
        ui.voice.onTap { VoiceGuide.set(this, !VoiceGuide.isOn(this)); renderVoice() }
        ui.globe.tripEnd = trip.value?.destination?.place
        // Talk (2026-10-21): the car listens for one request — the same as the button on the car's media screen.
        ui.talk.isVisible = browser?.isSessionCommandAvailable(VoiceCommand.TALK) == true
        ui.talk.onTap { browser?.sendCustomCommand(VoiceCommand.TALK, Bundle.EMPTY) }
        ui.account.onTap { if (repo.state.value.accountName == null) showLink() else showDisconnect() }
        // 재생 중엔 금색 버튼이 '저장'이 된다(PO 2026-10-21) — 멈춤은 운전대·차 미디어 화면·폰 알림이 맡는다.
        ui.play.onTap { if (browser?.playWhenReady == true) toggleSave() else togglePlayback() }
        ui.next.onTap { nextStation() }
        ui.favorites.onTap { showStations(repo.state.value.favorites, getString(R.string.favorites)) }
        ui.countries.onTap { showCourses() }
        // 배터리를 못 주는 차에서도 캠핑 화면은 쓸 수 있다 — 배터리 줄만 숨긴다(설계 2026-10-13 §2).
        ui.camp.onTap { enterCamp() }
        camping = CampingScreen(this)
        camping.play.onTap { campPlay() }
        camping.other.onTap { nextStation() }
        camping.screenOff.onTap { blackout(true) }
        camping.exit.onTap { exitCamp() }
        camping.retry.onTap { loadMoods() }
        camping.blackout.setOnClickListener { blackout(false) }
        camping.brightness.onTap {
            campBrightness = CampBrightness.next(campBrightness)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(BRIGHTNESS, campBrightness).apply()
            if (inCamp && !camping.blackout.isVisible) dim(campBrightness)
            renderBrightness()
        }
        renderBrightness()
        // 잠금 화면(lock) 바로 아래: 주행 중엔 잠금 화면이 캠핑 화면도 덮는다.
        ui.root.addView(camping.root, ui.root.indexOfChild(ui.lock), FrameLayout.LayoutParams(-1, -1))
        showLayers()
        setContentView(ui.root)
        UiScale.hideStatusBar(window, this)
    }
    /** The station's own country (camping screen fallback when the media item has no journey line). */
    private fun country(item: MediaItem?): String? = item?.let { repo.find(it.mediaId) }?.country?.ifBlank { null }
    /** "country · capital" and the country code of [station], kept until the station or the map changes. */
    private var place: Triple<String, String?, String?>? = null
    /**
     * The flag and "country · capital" line (PO 2026-10-21), shared by the main and camping screens.
     * The country in the car's language; the capital from the map (English, GeoNames).
     */
    private fun placeOf(station: Station?): Pair<String?, String?> {
        if (station == null) return null to null
        val capitals = (application as VoyadioApplication).capitals
        val key = "${station.id}:${atlas != null}:${capitals != null}"
        place?.takeIf { it.first == key }?.let { return it.second to it.third }
        val code = station.countryCode.takeIf { it.isNotBlank() }
        val target = code?.let { atlas?.countries?.get(it) }
        val name = code?.let { Countries.directory(this, repo.state.value.stations).label(it) }?.takeIf { it != code } ?: station.country
        // The capital in the car's language (Wikidata, 2026-10-21); a country without one shows only its name.
        val capital = target?.takeIf { it.capitalName != it.name }?.let { capitals?.name(it.code, it.capitalName) ?: it.capitalName }
        val text = PlaceLine.of(name, capital, target?.name).ifBlank { null }
        place = Triple(key, text, code)
        return text to code
    }
    /** Puts [text] on [view] with the flag of [code] in front (null = no flag); the flag is redrawn only when it changes. */
    private fun showPlace(view: TextView, text: String, code: String?, lines: Int) {
        view.put(text)
        view.maxLines = lines
        val tag = code ?: ""
        if (view.tag == tag) return
        view.tag = tag
        val flag = code?.let { Flags.drawable(resources, it, (view.textSize * 1.05f).toInt()) }
        view.setCompoundDrawablesRelative(flag, null, null, null)
    }
    private fun renderPlace(player: Player?) {
        val buffering = player?.playbackState == Player.STATE_BUFFERING
        val (text, code) = placeOf(player?.currentMediaItem?.mediaId?.let(repo::find))
        when {
            buffering -> showPlace(ui.country, getString(R.string.loading), null, 2)
            text != null -> showPlace(ui.country, text, code, 1)
            else -> showPlace(ui.country, getString(R.string.camp_caption), null, 2)
        }
    }
    private fun render(state: CampState) {
        if (!::ui.isInitialized) return
        autoStart(state)
        val player = browser
        ui.globe.setPlaying(player?.isPlaying == true)
        ui.title.text = player?.currentMediaItem?.mediaMetadata?.title ?: getString(R.string.welcome)
        renderPlace(player)
        val current = player?.currentMediaItem?.mediaId
        val playing = player?.playWhenReady == true
        ui.play.setText(when {
            !playing -> R.string.play
            state.favorites.any { it.id == current } -> R.string.saved
            else -> R.string.save
        })
        // '저장' only once the station is actually heard — saving while it is still searching kept dead stations.
        ui.play.isEnabled = player != null && state.stations.isNotEmpty() && (!playing || (current != null && player.isPlaying))
        ui.countries.text = getString(R.string.course_button, courseLabel(drift.steering))
        // Nickname last, so a long one is what gets cut (end ellipsis), never the mileage.
        val name = state.accountName
        val mileage = state.mileage
        ui.account.text = when {
            name == null -> getString(R.string.link_phone)
            mileage == null -> getString(R.string.connected, name)
            else -> getString(R.string.connected_mileage, name, mileage)
        }
        ui.account.contentDescription = when {
            name == null -> getString(R.string.link_phone)
            mileage == null -> getString(R.string.account_connected_talk, name)
            else -> getString(R.string.account_mileage_talk, name, mileage)
        }
        ui.status.text = if (player?.playerError != null) getString(R.string.playback_error) else when (state.status) {
            "sync_done" -> getString(R.string.sync_done)
            "sync_pending" -> getString(R.string.sync_pending)
            "sync_conflict" -> getString(R.string.sync_conflict)
            "session_expired" -> getString(R.string.session_expired)
            "network_error", "storage_error" -> getString(R.string.network_error)
            else -> ""
        }
        updateJourney(state)
        renderCamping()
    }
    /** Presses '듣기 시작' once the player is connected and stations are known; never interrupts what is already playing. */
    private fun autoStart(state: CampState) {
        if (autoStartUntil == 0L) return
        val player = browser ?: return
        if (player.playWhenReady || SystemClock.elapsedRealtime() > autoStartUntil) { autoStartUntil = 0L; return }
        if (state.stations.isEmpty()) return
        autoStartUntil = 0L
        togglePlayback()
    }
    /** 지금 방송을 즐겨찾기에 넣거나(저장) 이미 있으면 뺀다(저장됨 → 다시 누르면 해제). */
    private fun toggleSave() {
        val station = browser?.currentMediaItem?.mediaId?.let(repo::find) ?: return
        lifecycleScope.launch { runCatching { repo.toggleFavorite(station) }.onFailure { message(R.string.network_error) } }
    }
    private fun togglePlayback() {
        val player = browser ?: return
        if (player.playWhenReady) { player.pause(); return }
        // Another device listened more recently: continue that channel instead of the paused queue.
        val handoff = repo.takeHandoff()
        when {
            handoff != null && handoff.id != player.currentMediaItem?.mediaId -> playStation(handoff)
            player.mediaItemCount > 0 -> { player.prepare(); player.play() }
            // 켜면 나온다: 마지막으로 듣던 방송, 없으면 나침반 한 칸(지금 항로로 표류).
            else -> (repo.state.value.lastPlayed ?: drift.next(repo.state.value.stations, null, 1).firstOrNull())?.let(::playStation)
        }
    }
    private fun nextStation() {
        val player = browser ?: return
        // 버튼으로 넘긴다: 대기열은 재생 서비스가 표류로 채워 두므로 다음 칸으로. 비었으면 지금 항로로 한 칸.
        if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play() }
        else drift.next(repo.state.value.stations, player.currentMediaItem?.mediaId?.let(repo::find), 1).firstOrNull()?.let(::playStation)
    }
    /** 방송 하나를 튼다 — 그 뒤 대기열은 재생 서비스가 표류로 채운다(같은 나라만 이어지지 않게, 2026-10-15). */
    private fun playStation(station: Station) = playQueue(listOf(station))

    /** [stations] 순서 그대로 대기열을 만들고 첫 방송부터 튼다. */
    private fun playQueue(stations: List<Station>) {
        val player = browser ?: return
        if (stations.isEmpty()) return
        player.setMediaItems(stations.take(80).map { MediaItem.Builder().setMediaId(it.id).build() })
        player.prepare(); player.play()
    }
    /** Opens [sheet] as the only dialog: whatever was open closes first. */
    private fun open(sheet: CarSheet): Dialog { dialog?.dismiss(); return sheet.show().also { dialog = it } }
    private fun showStations(stations: List<Station>, heading: String) {
        // Empty: the sheet still opens and says how stations get here (a toast vanished before a driver read it).
        if (stations.isEmpty()) { open(CarSheet(this).title(heading).message(R.string.favorites_empty).action(R.string.cancel)); return }
        // Each row: the station, then the flag and country in the car's language (PO 2026-10-21).
        val directory = Countries.directory(this, repo.state.value.stations)
        val rows = stations.map { station ->
            val code = station.countryCode.takeIf { it.isNotBlank() }
            val country = code?.let(directory::label)?.takeIf { it != code } ?: station.country.ifBlank { null }
            CarSheet.Entry(station.name, country, station.id, flag = code)
        }
        open(CarSheet(this).title(heading).groups(listOf(null to rows)) { picked ->
            if (parked) stations.firstOrNull { it.id == picked.key }?.let(::playStation)
        }.action(R.string.cancel))
    }
    /**
     * 항로 카드(2026-10-15 설계 §3) — 방송이 아니라 **방향**을 고른다: 전체 / 내용 / 언어권 / 나라.
     * 검색하지 않는다(2026-10-22 이야기 정렬): 나라는 '최근 들은 나라'와 대륙 → 나라 두 단계로 고른다.
     * [continent]가 있으면 그 대륙의 나라 창(맨 위 '‹ 항로'로 돌아간다). 줄을 누르면 창이 닫히므로 단계마다 새 창을 연다.
     */
    private fun showCourses(continent: Continent? = null) {
        val state = repo.state.value
        val byCode = state.stations.filter { it.countryCode.isNotBlank() }.groupBy { it.countryCode }
        if (byCode.isEmpty()) { message(R.string.empty); return }
        val directory = Countries.directory(this, state.stations)
        val current = drift.steering
        val way = trip.value
        fun entry(course: Course, detail: String? = null) = CarSheet.Entry(courseLabel(course),
            if (course == current) getString(R.string.course_current) else detail, course.id)
        fun countries(codes: List<String>) = codes.map { code ->
            entry(Courses.country(code), resources.getQuantityString(R.plurals.station_count, byCode.getValue(code).size, byCode.getValue(code).size))
        }
        val back = Any()
        val groups = if (continent != null) listOf(
            null to listOf(CarSheet.Entry("‹ " + getString(R.string.course), key = back)),
            null to countries(directory.byContinent(byCode.keys).firstOrNull { it.first == continent }?.second.orEmpty()),
        ) else {
            // '최근 들은 나라'(2026-10-21): 실제로 들은 기록만. 즐겨찾기를 섞었더니 들은 적만 있는 나라(아루바)를
            // 즐겨찾기한 나라로 오해했다 — 즐겨찾기는 메인의 '즐겨찾기'에 있다.
            val recent = (listOfNotNull(state.lastPlayed) + drift.heard().mapNotNull(repo::find))
                .map { it.countryCode }.filter { it in byCode }.distinct().take(RECENT_COUNTRIES)
            listOfNotNull(
                // '가는 길'(2026-10-21) on top while the navigation has a destination — its name beneath.
                null to listOfNotNull(way?.let { entry(it.course, it.destination.name) }, entry(Course.Any)),
                getString(R.string.course_section_content) to Courses.CONTENT.map { entry(it) },
                getString(R.string.course_section_language) to Courses.LANGUAGE.map { entry(it) },
                recent.takeIf { it.isNotEmpty() }?.let { getString(R.string.list_recent) to countries(it) },
                getString(R.string.course_section_countries) to directory.byContinent(byCode.keys).map { (place, _) ->
                    CarSheet.Entry(Countries.label(this, place), key = place)
                },
            )
        }
        val title = continent?.let { Countries.label(this, it) } ?: getString(R.string.course)
        open(CarSheet(this).title(title).groups(groups) { picked ->
            if (!parked) return@groups
            when (val key = picked.key) {
                back -> showCourses()
                is Continent -> showCourses(key)
                is String -> Courses.byId(key)?.let(::setCourse)
            }
        }.action(R.string.cancel))
    }

    /** Today's voyage on the parked screen: its countries as small stars on the globe, and one line under the station. */
    private fun renderVoyage(log: Voyage.Log? = voyage.value) {
        if (!::ui.isInitialized) return
        ui.globe.voyage = log?.countries.orEmpty().mapNotNull { atlas?.countries?.get(it)?.capital }
        ui.voyage.text = log?.let { VoyageText.line(this, it) { code -> Countries.directory(this, repo.state.value.stations).label(code) } }.orEmpty()
    }

    /** 항로를 정하고 그 방향으로 바로 표류를 시작한다(사용자가 누른 동작). 폰과 나누는 항로면 계정에도 저장. */
    private fun setCourse(course: Course) {
        drift.choose(course)
        if (Courses.shared(course)) lifecycleScope.launch {
            runCatching { repo.saveCourse(course.id); drift.pushed(course.id) }
        }
        drift.next(repo.state.value.stations, null, 1).firstOrNull()?.let(::playStation)
        render(repo.state.value)
    }

    private fun courseLabel(course: Course): String =
        CourseLabels.label(this, course) { code -> Countries.directory(this, repo.state.value.stations).label(code) }

    /** The 50 languages the build declares (androidResources.generateLocaleConfig). */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun supportedLanguages(): List<Locale> {
        val list = LocaleConfig(this).supportedLocales ?: return emptyList()
        return (0 until list.size()).map(list::get)
    }

    /** The language the app shows now, named in itself. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun languageName(): String {
        val supported = supportedLanguages()
        val now = resources.configuration.locales[0]
        val index = AppLanguages.indexOf(now, supported)
        return if (index >= 0) AppLanguages.name(supported[index], supported) else now.getDisplayLanguage(now).replaceFirstChar { it.titlecase(now) }
    }

    /**
     * Every language in itself (the one picked here is gold); picking one restarts the screen in it — the radio keeps
     * playing. "Device language" is a button under the list, not its first row: the list opens scrolled to the
     * chosen language, which pushed that row out of sight (2026-10-21 phone test).
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun showLanguages() {
        val manager = getSystemService(LocaleManager::class.java) ?: return
        val supported = AppLanguages.sorted(supportedLanguages())
        val chosen = manager.applicationLocales.takeUnless { it.isEmpty }?.get(0)
        val selected = chosen?.let { AppLanguages.indexOf(it, supported) }?.takeIf { it >= 0 }
        open(CarSheet(this).title(R.string.language).list(supported.map { AppLanguages.name(it, supported) }, selected) { index ->
            if (parked) manager.applicationLocales = LocaleList(supported[index])
        }.action(R.string.language_system) {
            if (parked) manager.applicationLocales = LocaleList.getEmptyLocaleList()
        }.action(R.string.cancel))
    }
    private fun showDisconnect() {
        open(CarSheet(this).title(R.string.disconnect).message(R.string.disconnect_message)
            .action(R.string.cancel)
            .action(R.string.disconnect, primary = true) { if (parked) lifecycleScope.launch {
                browser?.stop(); browser?.clearMediaItems()
                runCatching { repo.disconnect() }.onFailure { message(R.string.network_error) }
            } })
    }
    private fun showLink() {
        linking?.cancel()
        val image = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val countdown = ui.text(getString(R.string.loading), 24f, CampScreen.GOLD, shadow = false)
        val steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        steps.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_phone); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        steps.addView(ui.text(getString(R.string.link_steps), 24f, shadow = false).apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        steps.addView(ui.text(getString(R.string.link_benefit), 24f, CampScreen.MUTED, shadow = false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        steps.addView(countdown, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        // QR beside the steps; on a narrow screen the steps go under the code.
        val wide = resources.configuration.screenWidthDp >= CampScreen.WIDE_DP
        val panel = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        }
        panel.addView(image, LinearLayout.LayoutParams(dp(220), dp(220)))
        panel.addView(steps, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(28) }
            else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        open(CarSheet(this).title(R.string.link_phone).content(panel).action(R.string.cancel)
            .secure().onDismiss { linking?.cancel() })
        linking = lifecycleScope.launch {
            try {
                val link = repo.startLink()
                val matrix = MultiFormatWriter().encode(link.qr, BarcodeFormat.QR_CODE, 440, 440)
                val bitmap = Bitmap.createBitmap(440, 440, Bitmap.Config.RGB_565)
                for (y in 0 until 440) for (x in 0 until 440) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                image.setImageBitmap(bitmap)
                while (isActive && parked) {
                    val seconds = ((link.expiresAt - System.currentTimeMillis()) / 1000).toInt()
                    if (seconds <= 0) break
                    countdown.text = getString(R.string.link_countdown, seconds)
                    delay(2000)
                    try {
                        if (repo.claimLink(link)) { dialog?.dismiss(); return@launch }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (error is ApiFailure && error.status in listOf(400, 410)) break
                        countdown.setText(R.string.network_error)
                        delay(if (error is ApiFailure && error.status == 429) 10_000 else 3000)
                    }
                }
                image.setImageDrawable(null); countdown.setText(R.string.link_expired)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                image.setImageDrawable(null); countdown.setText(R.string.network_error)
            }
        }
    }
    private fun updateJourney(state: CampState) {
        if (!::ui.isInitialized) return
        val globe = ui.globe
        val journey = ui.journey
        globe.glow = FavoriteGlow.levels(state.favorites)
        val world = atlas ?: return
        val finder = routeFinder ?: return
        val find = locator ?: return
        val channel = browser?.currentMediaItem?.mediaId
        val station = channel?.let(repo::find)
        val target = station?.countryCode?.let(world.countries::get)
        globe.highlight = target?.code
        val here = here()
        val origin = here ?: world.countries[Locale.getDefault().country]?.capital
        // Ask for a fresh fix while a station plays, so the route starts at the real car.
        if (here == null && target != null) askForFix()
        if (target == null) {
            journeyJob?.cancel(); journey.text = ""
            // No station: the globe spins at the car's latitude (only re-aimed when the place changes).
            val key = "idle:${origin?.let { "${(it.lat * 10).roundToInt()}:${(it.lon * 10).roundToInt()}" }}"
            if (key != journeyKey) { journeyKey = key; globe.showJourney(origin, null, null) }
            return
        }
        if (origin == null) {
            journeyJob?.cancel(); journeyKey = null
            globe.showJourney(null, null, null, channel)
            journey.text = ""   // the capital is already on the place line
            return
        }
        // The channel is part of the key: a new channel in the same country still glides the globe (2 s).
        val key = "$channel:${target.code}:${(origin.lat * 10).roundToInt()}:${(origin.lon * 10).roundToInt()}:${here == null}"
        if (key == journeyKey) return
        journeyKey = key
        journeyJob?.cancel()
        journeyJob = lifecycleScope.launch {
            val route = withContext(Dispatchers.Default) {
                find.locate(origin)?.let { finder.find(origin, it, target.code) }
            }
            globe.showJourney(origin, target.capital, route, channel)
            journey.text = route?.let { journeyText(it, here == null) } ?: ""
        }
    }

    private fun here(): LatLon? {
        val now = SystemClock.elapsedRealtime()
        if (now - hereReadAt > 30_000 || (cachedHere == null && hereReadAt == 0L)) {
            // A fresh fix from askForFix() must survive a later empty last-known read.
            // Pleos cars: the car's own fused position first (Pleos FusedLocation SDK, 2026-10-21).
            ((application as VoyadioApplication).pleos?.location ?: CarLocation.current(this))?.let { cachedHere = it }
            hereReadAt = now
        }
        return cachedHere
    }

    /**
     * Requests one fresh coarse fix when there is no last-known location. At most one ask per 60 s;
     * the request itself times out (~30 s) and is cancelled in onDestroy, so it cannot outlive the screen.
     */
    private fun askForFix() {
        if (!CarLocation.granted(this)) return
        val now = SystemClock.elapsedRealtime()
        if (locateAskedAt != 0L && now - locateAskedAt < 60_000) return
        locateAskedAt = now
        locateSignal?.cancel()
        val signal = androidx.core.os.CancellationSignal()
        locateSignal = signal
        locating = true
        val started = CarLocation.requestOnce(this, signal) { fix ->
            locating = false
            if (fix != null) {
                cachedHere = fix; hereReadAt = SystemClock.elapsedRealtime()
                journeyKey = null
                render(repo.state.value)
            }
        }
        if (!started) locating = false
    }

    /** The drive alone — where it comes from is the place line above (country · capital). */
    private fun journeyText(route: Route, approximate: Boolean) = JourneyText.detail(this, route, approximate)

    private fun renderVoice() {
        val on = VoiceGuide.isOn(this)
        ui.voice.setCompoundDrawablesRelativeWithIntrinsicBounds(if (on) R.drawable.ic_volume else R.drawable.ic_volume_off, 0, 0, 0)
        ui.voice.alpha = if (on) 1f else 0.6f
        ui.voice.contentDescription = getString(if (on) R.string.voice_guide_on else R.string.voice_guide_off)
    }

    // ── 캠핑 화면 (설계 2026-10-13 §2) ──

    /** 정차 중 "캠핑": 어둡고 움직이지 않는 화면으로. 지구본은 숨겨 그리기를 멈춘다(배터리). */
    private fun enterCamp() {
        inCamp = true
        guard.reset(); batterySaved = false
        showLayers(); dim(campBrightness)
        loadMoods(); startCampEnergy()
        renderCamping()
    }

    private fun exitCamp() {
        inCamp = false
        camping.blackout.isVisible = false
        energy.stop(); reading = null
        drift.along = null // 메인 화면은 다시 내 항로로 표류
        // 캠핑에서 채운 분위기 대기열은 지금 방송 뒤로 비운다 — 다음 칸부터 내 항로(비면 재생 서비스·nextStation이 채움).
        browser?.let { player ->
            val after = player.currentMediaItemIndex + 1
            if (player.mediaItemCount > after) player.removeMediaItems(after, player.mediaItemCount)
        }
        showLayers(); dim(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        render(repo.state.value)
    }

    /** 주행 중엔 잠금 화면만, 정차 중엔 메인(지구본) 또는 캠핑 화면 하나만. */
    private fun showLayers() {
        val main = parked && !inCamp
        ui.content.visibility = if (main) View.VISIBLE else View.GONE
        // The camp screen shows the same globe, standing still on the station's country (2026-10-15).
        ui.globe.visibility = if (parked) View.VISIBLE else View.INVISIBLE
        ui.globe.still(inCamp)
        // The camp globe sways slowly while the camp screen shows (2026-10-21) — not under the screen-off cover.
        ui.globe.sway = parked && inCamp && !camping.blackout.isVisible
        camping.root.isVisible = parked && inCamp
        ui.lock.visibility = if (parked) View.GONE else View.VISIBLE
    }

    /** 배터리는 캠핑 화면에 있는 동안만 읽는다. 권한은 처음 들어올 때 한 번만 묻는다. */
    private fun startCampEnergy() {
        if (!VehicleEnergy.supported(this)) return
        if (VehicleEnergy.granted(this)) energy.start()
        else if (!askedEnergy) { askedEnergy = true; energyRequest.launch(VehicleEnergy.PERMISSION) }
    }

    private fun renderBrightness() {
        val label = NumberFormat.getPercentInstance().format(campBrightness.toDouble())
        // "화면 밝기 15%" on the button itself (2026-10-21) — a sun and a number alone did not say what it changes.
        val spoken = getString(R.string.camping_brightness, label)
        camping.brightness(spoken, spoken)
    }

    private fun dim(level: Float) { window.attributes = window.attributes.apply { screenBrightness = level } }

    /** 화면 끄기: 검은 덮개 + 최저 밝기(차량 시스템은 앱이 화면을 완전히 끄게 하지 않는다). 라디오는 계속. */
    private fun blackout(on: Boolean) {
        if (!::camping.isInitialized) return
        camping.blackout.isVisible = on && inCamp
        ui.globe.sway = parked && inCamp && !camping.blackout.isVisible
        dim(when { on && inCamp -> BLACKOUT_BRIGHTNESS; inCamp -> campBrightness; else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE })
    }

    /** 지금 시간대의 분위기. 기억이 싱싱하면 그대로, 실패하면 저장소가 마지막 목록을 준다. */
    private fun loadMoods() {
        if (moodJob?.isActive == true) return
        val hour = LocalTime.now().hour
        if (moods.isNotEmpty() && repo.moodsFresh(hour)) return
        moodsFailed = false
        moodJob = lifecycleScope.launch {
            try { moods = repo.moods(hour); if (inCamp) drift.along = campCourse() } catch (error: Exception) {
                if (error is CancellationException) throw error
                moodsFailed = true
            }
            renderCamping()
        }
        renderCamping()
    }

    /**
     * 캠핑 항로(2026-10-15 설계 §4): 분위기를 고르지 않았으면 지금 시간대 분위기 3개를 섞어서, 골랐으면 그 분위기로
     * (8번에 1번은 다른 분위기 — 뜻밖의 신호).
     */
    private fun campCourse(): Course? {
        if (moods.isEmpty()) return null
        val chosen = moods.find { it.id == moodId }
        if (chosen != null) return Course.Stations(chosen.id, chosen.stations, moods.filter { it !== chosen }.flatMap { it.stations })
        val lists = moods.map { it.stations }
        val mixed = (0 until (lists.maxOf { it.size })).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        return Course.Stations("camp_now", mixed)
    }

    /** 캠핑 항로로 표류 시작. */
    /** 캠핑 항로로 새 대기열 — [after]가 있으면 그 방송(과 그 나라) 다음 칸부터. */
    private fun startCamp(after: Station? = null) {
        val course = campCourse() ?: return
        drift.along = course
        playQueue(drift.next(repo.state.value.stations, after, DriftState.FIRST + 1, course))
    }

    /** 듣기 시작 — 켜면 나온다: 지금 방송이 캠핑 분위기 방송이면 이어서, 아니면 캠핑 항로로 새로 시작. */
    private fun campPlay() {
        val player = browser ?: return
        // 재생 중엔 '저장'(PO 2026-10-21, 메인 화면과 같은 규칙) — 멈춤은 차 미디어 화면·운전대·폰 알림이 맡는다.
        if (player.playWhenReady) { toggleSave(); return }
        val current = player.currentMediaItem?.mediaId
        if (current != null && moods.any { mood -> mood.stations.any { it.id == current } }) { player.prepare(); player.play() }
        else startCamp()
    }

    /** 분위기 버튼: 그 분위기로 항로를 좁히고 바로 그 항로로. 켜진 분위기를 다시 누르면 같은 분위기의 다른 방송. */
    private fun playMood(mood: Mood) {
        if (!parked) return
        // 켜진 분위기를 다시 누르면 끄지 않고 같은 분위기의 다른 방송으로(PO 2026-10-21) — 지금 방송·나라는 피한다.
        val again = moodId == mood.id
        moodId = mood.id
        startCamp(if (again) browser?.currentMediaItem?.mediaId?.let(repo::find) else null)
        renderCamping()
    }

    private fun renderCamping() {
        if (!::camping.isInitialized) return
        val player = browser
        val item = player?.currentMediaItem
        // put(): only on change — the car reports energy often, and an unchanged setText still redraws the
        // still camp screen (2026-10-15: 1 frame/s on the car emulator).
        camping.title.put(item?.mediaMetadata?.title ?: getString(R.string.camping_start_hint))
        // 미디어 항목의 아티스트 줄 = "나라 · 수도에서 오는 방송"(Drive View와 같은 문장).
        // 메인 화면과 같은 국기 + "나라 · 수도" 한 줄(2026-10-21). 오류·불러오는 중엔 그 문장.
        val (placeText, placeCode) = placeOf(item?.mediaId?.let(repo::find))
        when {
            player?.playerError != null -> showPlace(camping.place, getString(R.string.playback_error), null, 2)
            player?.playbackState == Player.STATE_BUFFERING -> showPlace(camping.place, getString(R.string.loading), null, 2)
            item == null -> showPlace(camping.place, "", null, 2)
            placeText != null -> showPlace(camping.place, placeText, placeCode, 1)
            else -> showPlace(camping.place, item.mediaMetadata.artist?.toString() ?: country(item).orEmpty(), null, 2)
        }
        val playing = player?.playWhenReady == true
        val saved = item != null && repo.state.value.favorites.any { it.id == item.mediaId }
        camping.play.put(getString(when { !playing -> R.string.play; saved -> R.string.saved; else -> R.string.save }))
        // '저장'은 소리가 실제로 나올 때부터(메인과 같게) — 신호를 찾는 중엔 흐리게.
        camping.play.isEnabled = player != null && (item != null || repo.state.value.stations.isNotEmpty()) &&
            (!playing || (item != null && player.isPlaying))
        camping.other.isEnabled = player != null && item != null
        // 캠핑 분위기 밖에서 튼 방송이면(즐겨찾기·이어 듣기) 분위기 금색 표시를 푼다. 뜻밖의 신호(다른 분위기)는 그대로 둔다.
        if (moodId != null && item != null && moods.none { mood -> mood.stations.any { it.id == item.mediaId } }) moodId = null
        camping.moods(moods, moodId,
            status = when { moods.isNotEmpty() -> null; moodsFailed -> getString(R.string.camping_moods_failed); else -> getString(R.string.loading) },
            retry = moods.isEmpty() && moodsFailed, onPick = ::playMood)
        val now = reading
        val percent = now?.let { batteryPercent(it.levelWh, it.capacityWh) }
        camping.battery(percent?.let { getString(R.string.camping_battery, it, guard.floor) })
        if (inCamp && now != null && percent != null && guard.shouldStop(percent, player?.playWhenReady == true, now.charging)) {
            player?.pause()
            batterySaved = true
        }
        // 사용자가 다시 틀면 안내를 거둔다(그 뒤로는 끄지 않는다).
        if (player?.playWhenReady == true) batterySaved = false
        camping.notice(if (batterySaved) getString(R.string.camping_battery_saved, guard.floor) else null)
    }

    private fun message(id: Int) { Toast.makeText(this, id, Toast.LENGTH_LONG).show() }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    private companion object {
        const val PREFS = "camping"
        /** 항로의 '최근 들은 나라' 줄 수. */
        const val RECENT_COUNTRIES = 6
        const val BRIGHTNESS = "brightness"
        const val BLACKOUT_BRIGHTNESS = 0.01f // 화면 끄기 덮개(0은 일부 기기에서 '시스템 기본'으로 읽힌다)
        /** 앱을 연 뒤 이 안에 목록이 준비될 때만 스스로 튼다 — 한참 뒤 통신이 돌아와 갑자기 소리가 나지 않게. */
        const val AUTO_START_WINDOW_MS = 60_000L
        const val AUTO_START = "auto_start_until"
    }
}
