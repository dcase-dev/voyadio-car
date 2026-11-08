package com.voyadio.automotive

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.voyadio.automotive.camping.Mood
import com.voyadio.automotive.drift.Course
import com.voyadio.automotive.drift.CourseLabels
import com.voyadio.automotive.drift.Courses
import com.voyadio.automotive.drift.Drift
import com.voyadio.automotive.drift.DriftState
import com.voyadio.automotive.data.Station
import com.voyadio.automotive.speech.AndroidSpeaker
import com.voyadio.automotive.speech.AnnounceGate
import com.voyadio.automotive.speech.Recognizer
import com.voyadio.automotive.speech.Speaker
import com.voyadio.automotive.speech.SpokenJourney
import com.voyadio.automotive.speech.VoiceGuide
import com.voyadio.automotive.geo.LatLon
import com.voyadio.automotive.trip.Destination
import com.voyadio.automotive.trip.Trip
import com.voyadio.automotive.trip.TripBorder
import com.voyadio.automotive.voyage.Voyage
import com.voyadio.automotive.voyage.VoyageText
import com.voyadio.automotive.geo.Route
import com.voyadio.automotive.voice.Heard
import com.voyadio.automotive.voice.Spoken
import com.voyadio.automotive.voice.VoiceAction
import com.voyadio.automotive.voice.VoiceCommand
import com.voyadio.automotive.voice.VoiceResult
import com.voyadio.automotive.voice.VoiceResult.Outcome
import com.voyadio.automotive.geo.PlaceClock
import java.util.Locale
import kotlinx.coroutines.*
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@UnstableApi
class RadioService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as VoyadioApplication
    private val repo get() = app.repository
    /** Drive View art (the car's own media screen): the night globe instead of the platform's blank note. */
    private val artwork by lazy {
        Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).authority(packageName)
            .appendPath("drawable").appendPath(resources.getResourceEntryName(R.drawable.art_globe)).build()
    }
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private var listened: Job? = null
    private var retry: Job? = null
    private var retries = 0
    /** Dead stations skipped in a row; reset once audio actually plays. */
    private var skips = 0
    private var accountId: Long? = null
    /** Voice guide (2026-10-21): says where a new station comes from once its sound starts — the car's own voice on
     *  Pleos, Android's engine elsewhere. */
    private val gate = AnnounceGate()
    private var voice: Speaker? = null
    private var ownVoice: AndroidSpeaker? = null
    private var speech: Job? = null
    /** The talk button's ear (2026-10-21): Pleos cars whose recognizer hears the car's language; null elsewhere. */
    private var ear: Recognizer? = null
    private var talking = false
    private var cue: ToneGenerator? = null
    /** The road's radio (2026-10-21): the country under the car, steadied, while the navigation has a destination. */
    private var border = TripBorder()
    private var borderWatch: Job? = null
    private var destroyed = false
    /** Said first in the voice guide for this station: "독일에 들어왔어요" / "목적지에 도착했어요". */
    private var intro: Pair<String, String>? = null
    /** When the trip course last ended because the destination went — an arrival reported just after still greets. */
    private var tripEnded = 0L
    private var tripEnding: Job? = null
    /** Today's voyage (2026-10-22): from leaving P to P again — the gear on Pleos cars, else the car's driving restriction. */
    private val voyage = Voyage()
    private var voyageEar: Job? = null
    private var gateParked: Boolean? = null
    private var parkedGate: ParkedGate? = null
    private var heardProbe: BroadcastReceiver? = null
    companion object {
        const val ROOT = "root"
        const val RADIO = "radio"
        const val FAVORITES = "favorites"
        const val COURSES = "courses"
        /** 누르면 지금 항로로 표류 시작(채널 목록 대신, 2026-10-15). */
        const val DRIFT_NOW = "drift:now"
        private const val COURSE = "course:"
        private const val MOOD_PLAY = "moodplay:"
        private const val CONTINENT = "continent:"
        private const val MOOD = "mood:"
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        /** The radio's volume while the voice guide speaks. */
        private const val DUCKED = 0.25f
        /** The talk button's beep, when the car starts to listen. */
        private const val CUE_MS = 150
        private const val CUE_VOLUME = 70
        /** How often the road's radio looks at the country under the car (two looks in a row make a crossing). */
        private const val BORDER_CHECK_MS = 20_000L
        private const val ARRIVAL_GRACE_MS = 15_000L
        private const val TRIP_END_GRACE_MS = 10_000L
    }
    override fun onCreate() {
        super.onCreate()
        val streams = DefaultHttpDataSource.Factory().setUserAgent("VoyadioCamp/0.1")
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8000).setReadTimeoutMs(12000)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(streams)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, CampActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, player, LibraryCallbacks()).setSessionActivity(activity).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listened?.cancel()
                repo.markPlaying(isPlaying)
                if (isPlaying) {
                    retries = 0; skips = 0
                    val id = player.currentMediaItem?.mediaId
                    if (gate.onPlaying(id)) announce(id)
                    listened = scope.launch {
                        delay(2000)
                        if (player.isPlaying && player.currentMediaItem?.mediaId == id) {
                            id?.let(app.drift::remember)
                            repo.find(id.orEmpty())?.let { runCatching { repo.recordPlayed(it) } }
                        }
                    }
                }
                hearOnRoad(if (isPlaying) player.currentMediaItem?.mediaId else null)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retry?.cancel(); retries = 0; topUp()
                gate.onTransition(mediaItem?.mediaId); quiet()
            }
            // The queue was cut short (leaving the camp screen empties the mood queue): refill it so "next" works at once.
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) topUp()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) retry?.cancel()
            }
            // A dead station must not leave silence in the car (2026-10-15): retry, or move on in the queue — PlaybackRecovery.
            override fun onPlayerError(error: PlaybackException) {
                if (!player.playWhenReady) return
                val id = player.currentMediaItem?.mediaId
                retry?.cancel()
                when (val action = PlaybackRecovery.decide(error.errorCode, retries, skips, player.hasNextMediaItem(), online())) {
                    is PlaybackRecovery.Action.Retry -> {
                        retries++
                        retry = scope.launch {
                            delay(action.delayMs)
                            if (player.playWhenReady && player.currentMediaItem?.mediaId == id) player.prepare()
                        }
                    }
                    PlaybackRecovery.Action.Skip -> { skips++; player.seekToNextMediaItem(); player.prepare() }
                    PlaybackRecovery.Action.GiveUp -> Unit // the error stays on the car's media screen and the camp screen
                }
            }
        })
        scope.launch(Dispatchers.IO) { app.loadAtlas(); app.loadCapitals() } // capital names for the Drive View journey line
        voice = runCatching { app.pleos?.speaker }.getOrNull() ?: AndroidSpeaker(this).also { ownVoice = it }
        ear = runCatching { app.pleos?.recognizer }.getOrNull()
        ear?.whenReady(::offerTalk)
        // The road's radio (2026-10-21): the navigation's destination makes the trip course; arriving ends it.
        app.pleos?.let { pleos ->
            scope.launch { pleos.destination.collect { destinationChanged(it) } }
            scope.launch { pleos.arrivals.collect { arrived(it) } }
            scope.launch { pleos.driving.collect { moved() } }
        }
        parkedGate = ParkedGate(this) { parked -> gateParked = parked; moved() }.also { it.start() }
        // Debug builds only: the talk button's own path for words heard from a recorded voice — the Pleos emulator has
        // no microphone (demo video, 2026-10-22). Not exported: only this app (SttProbe) can send it; release never listens.
        if (BuildConfig.DEBUG) heardProbe = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                val words = intent.getStringExtra(Heard.WORDS) ?: return
                scope.launch { answer(understand(words)) }
            }
        }.also { ContextCompat.registerReceiver(this, it, IntentFilter(Heard.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED) }
        scope.launch {
            repo.initialize()
            accountId = repo.state.value.accountId
            launch {
                repo.state.collect { state ->
                    if (accountId != state.accountId) {
                        accountId = state.accountId
                        player.stop(); player.clearMediaItems()
                    }
                    for (parent in listOf(ROOT, RADIO, COURSES, FAVORITES)) {
                        session.notifyChildrenChanged(parent, children(parent).size, null)
                    }
                }
            }
            repo.refresh()
            refreshMoods()
            // No refresh button since 2026-10-21: a pool that failed to load (no signal at the campsite) retries itself.
            while (isActive) {
                delay(30_000)
                if (repo.state.value.stations.isEmpty()) repo.refresh() else repo.sync()
                refreshMoods()
            }
        }
    }
    private var moodJob: Job? = null
    /** 차량 미디어 화면 '항로' 안의 "지금 어울리는 분위기": 지금 시간대 목록을 받아 두고, 바뀌면 항로 폴더를 새로 알린다. 기억이 싱싱하면 아무것도 안 한다. */
    private fun refreshMoods() {
        val hour = LocalTime.now().hour
        if (moodJob?.isActive == true || repo.moodsFresh(hour)) return
        moodJob = scope.launch {
            val before = repo.lastMoods
            runCatching { repo.moods(hour) }
            if (repo.lastMoods != before) session.notifyChildrenChanged(COURSES, children(COURSES).size, null)
        }
    }

    /** 대기열이 바닥나기 전에 표류로 더 붙인다 — "다음"이 끝나지 않게(2026-10-15). */
    private fun topUp() {
        val left = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (player.mediaItemCount == 0 || left >= DriftState.LOW) return
        val current = player.currentMediaItem?.mediaId?.let(repo::find)
        val more = app.drift.next(repo.state.value.stations, current, DriftState.TOP_UP)
        if (more.isNotEmpty()) player.addMediaItems(more.map(::stationItem))
    }

    /** 고른 방송 하나 뒤에 표류를 붙인 대기열. */
    private fun drifting(first: Station): List<MediaItem> =
        (listOf(first) + app.drift.next(repo.state.value.stations, first, DriftState.FIRST)).map(::stationItem)

    private fun online(): Boolean {
        val network = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = network.getNetworkCapabilities(network.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Always the session (2026-10-21). Car media centers connect through the legacy MediaBrowser API, and Media3
    // calls this for that bind with a placeholder controller ("android.media.session.MediaController", uid -1,
    // untrusted) before the real caller is known — the old trusted-only check refused every car media screen
    // (Pleos Connect's media card stayed empty; the plain AAOS media browser was blank). Callers are still kept
    // from injecting streams: onAddMediaItems/onSetMediaItems resolve server-owned station IDs only.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build()).build()
    private fun stationItem(station: Station) = MediaItem.Builder().setMediaId(station.id).setUri(station.url)
        // Drive View (the car's screen while moving) shows the artist line under the name, so it carries the journey:
        // "Uruguay · Montevideo에서 오는 방송". The camp screen reads the plain country from the station instead.
        .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name)
            .setArtist(DriveView.subtitle(localCountry(station), localCapital(station)) {
                getString(R.string.journey_from, it)
            })
            .setArtworkUri(artwork)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()).build()
    /** The station's country in the car's language (the server spells it in English). */
    private fun localCountry(station: Station): String =
        station.countryCode.takeIf { it.isNotBlank() }?.let { CountryDirectory.platformNames(it, Locale.getDefault()).firstOrNull() }
            ?: station.country
    /** The capital in the car's language; null where the map has none (it stands the country's name in). */
    private fun localCapital(station: Station): String? {
        val country = app.atlas?.countries?.get(station.countryCode)?.takeIf { it.capitalName != it.name } ?: return null
        return app.capitals?.name(country.code, country.capitalName) ?: country.capitalName
    }
    /** 누르면 재생되는 항목(방송이 아니라 표류의 시작점) — 지금 표류·항로·분위기. */
    private fun playable(id: String, title: String) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtworkUri(artwork)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION).build()).build()

    private fun courseLabel(course: Course) = CourseLabels.label(this, course) { directory().label(it) }

    /**
     * 차량 미디어 화면 목록 — 이야기 정렬(2026-10-22): 운전 중에 고를 것은 셋뿐 — 지금 표류 · 항로 · 즐겨찾기.
     * 항로 하나에 방향을 다 모은다: (가는 길) · 지금 어울리는 분위기 · 내용 · 언어권 · 나라(대륙 폴더 → 그 나라 항로).
     * '듣던 방송 이어듣기'는 없앴다 — 켜면 이미 이어진다(2026-10-09).
     */
    private fun children(parent: String): List<MediaItem> {
        val state = repo.state.value
        return when (parent) {
            ROOT -> listOf(folder(RADIO, getString(R.string.radio)))
            RADIO -> listOf(playable(DRIFT_NOW, getString(R.string.drift_now, courseLabel(app.drift.steering))),
                folder(COURSES, getString(R.string.course)), folder(FAVORITES, getString(R.string.favorites)))
            // '가는 길'(2026-10-21) first, while the navigation has a destination. 분위기 3개는 서버가 지금 시간대에
            // 고른 목록 — 아직 없으면 받기를 시작하고 그 칸만 비운다.
            COURSES -> listOfNotNull(app.trip.value?.course).map(::courseItem) +
                grouped(getString(R.string.camping_moods), repo.lastMoods.ifEmpty { refreshMoods(); emptyList() }
                    .map { playable("$MOOD_PLAY${it.id}", it.title()) }) +
                grouped(getString(R.string.course_section_content), Courses.CONTENT.map(::courseItem)) +
                grouped(getString(R.string.course_section_language), Courses.LANGUAGE.map(::courseItem)) +
                // Continents first (2026-10-13): a driver can't scroll 200 countries; the car also caps long lists while moving.
                grouped(getString(R.string.course_section_countries), directory().byContinent(countryCodes()).map { (continent, _) ->
                    folder("$CONTINENT${continent.name}", Countries.label(this, continent))
                })
            FAVORITES -> state.favorites.map(::stationItem)
            else -> when {
                // 나라를 누르면 그 나라 항로로 표류 — 그 나라 채널 목록은 없다.
                parent.startsWith(CONTINENT) -> {
                    val continent = Continent.entries.firstOrNull { it.name == parent.removePrefix(CONTINENT) }
                    directory().byContinent(countryCodes()).firstOrNull { it.first == continent }?.second.orEmpty()
                        .map { code -> courseItem(Courses.country(code)) }
                }
                else -> emptyList()
            }
        }
    }
    private fun courseItem(course: Course) = playable("$COURSE${course.id}", courseLabel(course))

    /** Rows under a heading on the car's media screen (the platform's group title hint; ignored where unsupported). */
    private fun grouped(title: String, items: List<MediaItem>): List<MediaItem> = items.map { item ->
        item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
            .setExtras(Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title) }).build()).build()
    }
    private fun countryCodes() = repo.state.value.stations.mapNotNullTo(LinkedHashSet()) { it.countryCode.ifBlank { null } }

    /** Country names in the device language, rebuilt only when the station list changes. */
    private var named: Pair<List<Station>, CountryDirectory>? = null
    private fun directory(): CountryDirectory {
        val stations = repo.state.value.stations
        return named?.takeIf { it.first === stations }?.second ?: Countries.directory(this, stations).also { named = stations to it }
    }

    /**
     * Voice and the car's search box (2026-10-13): "일본 라디오 틀어줘" → the Japan course. Never station names
     * (2026-10-22 — the app does not search): moods ("모닥불 재즈 틀어줘"), then courses, then the countries the words
     * name — each a way to drift.
     */
    private fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        // "모닥불 재즈"·"클래식"·"일본 라디오" → 분위기·항로를 찾는다(채널 목록이 아니라 표류의 방향).
        val moods = Mood.matching(query, repo.lastMoods).map { playable("$MOOD_PLAY${it.id}", it.title()) }
        val words = CountryDirectory.clean(query).joinToString(" ").ifBlank { query.trim() }
        val named = (listOfNotNull(app.trip.value?.course) + Courses.CONTENT + Courses.LANGUAGE).filter { courseLabel(it).contains(words, true) }
            .map { playable("$COURSE${it.id}", courseLabel(it)) }
        val countries = directory().find(query, countryCodes()).map { code ->
            Courses.country(code).let { playable("$COURSE${it.id}", courseLabel(it)) }
        }
        return (moods + named + countries).distinctBy { it.mediaId }.take(20)
    }

    /**
     * The car's search box: words that name no way to drift ("BBC") get 'drift now' instead of nothing — the Pleos
     * media screen kept spinning "검색 중…" on an empty result (2026-10-22). The talk button keeps [search] itself, so
     * words it could not make out still ask the driver again.
     */
    private fun searchResults(query: String): List<MediaItem> = search(query).ifEmpty {
        if (query.isBlank()) emptyList() else listOf(playable(DRIFT_NOW, getString(R.string.drift_now, courseLabel(app.drift.steering))))
    }

    /**
     * 하나를 골랐을 때의 대기열: 지금 표류·항로(항로를 바꾸고 표류)·분위기(그 분위기 순서)·방송(그 뒤는 표류).
     * 음성 질의는 분위기·항로·나라로만 푼다 — 방송 이름("KBS 틀어줘")으로 찾지 않고, 모르는 말이면 지금 항로로 표류(2026-10-22).
     */
    private fun start(item: MediaItem): List<MediaItem>? {
        val id = item.mediaId
        return when {
            id == DRIFT_NOW -> driftNow()
            id.startsWith(COURSE) -> Courses.byId(id.removePrefix(COURSE))?.let(::steer)
            id.startsWith(MOOD_PLAY) -> repo.lastMoods.firstOrNull { it.id == id.removePrefix(MOOD_PLAY) }?.stations?.map(::stationItem)
            // A station — even one from a camp mood — drifts on along the current course; the camp screen's mood
            // course rides on DriftState.along instead (2026-10-15: "continue listening" kept a stale mood order).
            repo.find(id) != null -> drifting(repo.find(id)!!)
            else -> item.requestMetadata.searchQuery?.let { query -> search(query).firstOrNull()?.let(::start) ?: driftNow() }
        }
    }

    /** One station along the course, the queue drifting on behind it. */
    private fun driftNow(): List<MediaItem>? = app.drift.next(repo.state.value.stations, null, 1).firstOrNull()?.let(::drifting)

    /** A new course — remembered, and shared with the phone when it is one of the shared ones — then the drift along it. */
    private fun steer(course: Course): List<MediaItem>? {
        app.drift.choose(course)
        if (Courses.shared(course)) scope.launch { runCatching { repo.saveCourse(course.id); app.drift.pushed(course.id) } }
        session.notifyChildrenChanged(RADIO, children(RADIO).size, null)
        return driftNow()
    }

    /**
     * A voice action (Gleo, or the talk button) — the same drift the buttons give — answered with what came of it.
     * A spoken course is tried against the pool first: a country with nothing on the air is "none", not elsewhere.
     */
    private suspend fun act(action: VoiceAction): VoiceResult {
        if (action != VoiceAction.Save) {
            repo.initialize() // a cold start (Gleo woke the radio) has no pool in memory yet
            if (repo.state.value.stations.isEmpty()) runCatching { repo.refresh() }
        }
        val announced = VoiceGuide.isOn(this) && voice != null && gate.announcesNext
        fun play(items: List<MediaItem>?, course: Course? = null): VoiceResult {
            if (items.isNullOrEmpty()) return VoiceResult(Outcome.NO_STATIONS, courseId = course?.id)
            player.setMediaItems(items); player.prepare(); player.play()
            return VoiceResult(Outcome.PLAYING, items.first().mediaId, course?.id, announced)
        }
        return when (action) {
            VoiceAction.Next -> if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem(); player.prepare(); player.play()
                VoiceResult(Outcome.PLAYING, player.currentMediaItem?.mediaId, announced = announced)
            } else play(driftNow())
            is VoiceAction.Steer -> if (!Drift.reaches(repo.state.value.stations, action.course)) {
                VoiceResult(Outcome.NO_STATIONS, courseId = action.course.id)
            } else {
                app.drift.along = null // the driver's own words beat the camp screen's mood course
                play(steer(action.course), action.course)
            }
            VoiceAction.Save -> save()
        }
    }

    /** "이 방송 저장": the favourite is kept on the car at once; the sync after it may wait for a signal. */
    private suspend fun save(): VoiceResult {
        val station = player.currentMediaItem?.mediaId?.let(repo::find) ?: return VoiceResult(Outcome.NOTHING_PLAYING)
        fun saved() = repo.state.value.favorites.any { it.id == station.id }
        if (saved()) return VoiceResult(Outcome.ALREADY_SAVED, station.id)
        runCatching { repo.toggleFavorite(station) }
        return VoiceResult(if (saved()) Outcome.SAVED else Outcome.FAILED, station.id)
    }

    /**
     * Voice actions (2026-10-21) go to this app's own controllers only — the Gleo receiver. The talk button goes to
     * everyone (the car's media screen sends it) once the car can hear its language; it only starts listening.
     */
    private fun commandsFor(controller: MediaSession.ControllerInfo,
        base: SessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS): SessionCommands =
        base.buildUpon().apply {
            if (canTalk()) add(VoiceCommand.TALK)
            if (controller.uid == Process.myUid()) add(VoiceCommand.SESSION)
        }.build()

    private fun canTalk() = ear?.hears(Locale.getDefault()) == true

    /** The talk button on the car's media screen — shown once the recognizer is up, and only for a language it hears. */
    private fun offerTalk() {
        if (destroyed) return // the recognizer outlives the service (it belongs to the app) and may call back late
        val button = CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(R.drawable.ic_mic)
            .setDisplayName(getString(R.string.talk)).setSessionCommand(VoiceCommand.TALK).setSlots(CommandButton.SLOT_OVERFLOW).build()
        session.setMediaButtonPreferences(if (canTalk()) listOf(button) else emptyList())
        for (controller in session.connectedControllers) runCatching {
            session.setAvailableCommands(controller, commandsFor(controller), MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        }
    }

    /**
     * The talk button (2026-10-21): the radio hushes, a beep, the car hears one request and the radio does it — "저장"
     * saves; a country, language, kind or mood steers (the media screen's search); "다음"·"다른 나라" drifts on.
     */
    private fun talk() {
        val ear = ear?.takeIf { canTalk() } ?: return
        if (talking) return
        talking = true
        quiet(); player.volume = 0f
        // The beep when the microphone is really open — the engine takes a moment, and words before it are lost.
        ear.listen(Locale.getDefault(), listening = {
            cue = cue ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, CUE_VOLUME) }.getOrNull()
            cue?.startTone(ToneGenerator.TONE_PROP_BEEP, CUE_MS)
        }) { words ->
            scope.launch {
                val result = words?.let { understand(it) }
                talking = false
                answer(result)
            }
        }
    }

    /** "저장" first, then places and kinds of radio, then "다음" — so "일본으로 바꿔줘" is Japan, not "change". */
    private suspend fun understand(words: String): VoiceResult? {
        if (Spoken.save(words)) return act(VoiceAction.Save)
        repo.initialize()
        val found = search(words).firstOrNull()
        val course = found?.mediaId?.takeIf { it.startsWith(COURSE) }?.let { Courses.byId(it.removePrefix(COURSE)) }
        return when {
            course != null -> act(VoiceAction.Steer(course))
            found != null -> start(found)?.takeIf { it.isNotEmpty() }?.let { items ->
                player.setMediaItems(items); player.prepare(); player.play()
                VoiceResult(Outcome.PLAYING, items.first().mediaId)
            }
            Spoken.next(words) -> act(VoiceAction.Next)
            else -> null
        }
    }

    /** What came of a spoken request, in the car's voice — a new station is named by the voice guide itself. */
    private fun answer(result: VoiceResult?) {
        player.volume = 1f
        val line = when (result?.outcome) {
            Outcome.PLAYING -> return
            Outcome.SAVED, Outcome.ALREADY_SAVED -> getString(R.string.star_kept)
            Outcome.NO_STATIONS -> result.courseId?.let(Courses::byId)?.let { getString(R.string.talk_none_for, courseLabel(it)) }
                ?: getString(R.string.talk_none)
            Outcome.NOTHING_PLAYING -> getString(R.string.talk_none)
            Outcome.FAILED -> getString(R.string.network_error)
            null -> getString(R.string.talk_again)
        }
        val speaker = voice ?: return
        player.volume = DUCKED
        speaker.say(line, Locale.getDefault()) { player.volume = 1f }
    }

    /**
     * The road's radio (2026-10-21, design §4): the navigation's destination came, changed or went — the trip course
     * appears in the course lists, keeps up with it, or ends and the driver's own course returns.
     */
    private suspend fun destinationChanged(destination: Destination?) {
        // A new trip starts wherever the car is now — not where the last one left the border count.
        if (destination != null && app.trip.value == null) border = TripBorder()
        val to = destination?.let { d -> withContext(Dispatchers.Default) { runCatching { app.loadJourneys().second.locate(d.place) }.getOrNull() } }
        val trip = if (destination != null && to != null) Trip(destination, to, border.country ?: carCountry()) else null
        app.trip.value = trip
        followTrip(trip)
        if (trip != null) watchBorders()
    }

    /**
     * The trip course, if the driver chose it, keeps to the trip; with the trip gone it ends — after a moment, since a
     * new destination replaces the old one by cancelling it first (Pleos emulator: about 5 s between the two).
     */
    private fun followTrip(trip: Trip?) {
        val steering = app.drift.trip
        if (trip == null) {
            if (steering != null && tripEnding?.isActive != true) tripEnding = scope.launch {
                delay(TRIP_END_GRACE_MS)
                if (app.trip.value != null || app.drift.trip == null) return@launch
                tripEnded = SystemClock.elapsedRealtime()
                app.drift.trip = null
                redrift()
                refreshCourses()
            }
        } else {
            tripEnding?.cancel()
            if (steering != null && steering != trip.course) { app.drift.trip = trip.course; redrift() }
        }
        refreshCourses()
    }

    /**
     * While the navigation has a destination: the country under the car every [BORDER_CHECK_MS]. A crossing on the
     * trip course switches the radio to the new country at once; otherwise only the course's name follows.
     */
    private fun watchBorders() {
        if (borderWatch?.isActive == true) return
        borderWatch = scope.launch {
            while (isActive) {
                val trip = app.trip.value ?: break
                val crossed = border.saw(carCountry())
                val here = border.country
                if (here != null && here != trip.here) {
                    val moved = trip.copy(here = here)
                    app.trip.value = moved
                    if (crossed && app.drift.trip != null) cross(moved) else followTrip(moved)
                }
                delay(BORDER_CHECK_MS)
            }
        }
    }

    /** Over a border on the trip course: a station of the new country at once, introduced — "독일에 들어왔어요". */
    private fun cross(trip: Trip) {
        app.drift.trip = trip.course
        refreshCourses()
        val here = trip.here ?: return
        greet(Courses.country(here), getString(R.string.trip_entered, directory().label(here)))
    }

    /** At the destination on the trip course: one of its country's stations — "목적지에 도착했어요" — then the course again. */
    private suspend fun arrived(destination: Destination) {
        val onTrip = app.drift.trip != null || (tripEnded > 0L && SystemClock.elapsedRealtime() - tripEnded < ARRIVAL_GRACE_MS)
        if (!onTrip) return
        app.drift.trip = null
        refreshCourses()
        val to = app.trip.value?.to
            ?: withContext(Dispatchers.Default) { runCatching { app.loadJourneys().second.locate(destination.place) }.getOrNull() }
        if (to == null) redrift() else greet(Courses.country(to), getString(R.string.trip_arrived))
    }

    /**
     * One station along [way] now, the queue drifting on behind it along the driver's course, introduced by [line].
     * A paused radio stays paused, and a way with nothing on the air does not play just anything: the queue only
     * follows the new way.
     */
    private fun greet(way: Course, line: String) {
        val pool = repo.state.value.stations
        val first = app.drift.next(pool, null, 1, way).firstOrNull()
        if (first == null || !player.playWhenReady || !Drift.reaches(pool, way)) { redrift(); return }
        intro = first.id to line
        player.setMediaItems(drifting(first)); player.prepare(); player.play()
    }

    /** The way changed: the stations queued after this one go, and the queue refills along the new way (onTimelineChanged). */
    private fun redrift() {
        val next = player.currentMediaItemIndex + 1
        if (next < player.mediaItemCount) player.removeMediaItems(next, player.mediaItemCount)
    }

    /**
     * Leaving P starts a voyage; back in P it docks and, with two countries or more, tells. The last voyage's stars
     * stay until a new one has met a country — a quick R to park after arriving does not wipe them.
     */
    private fun moved() {
        if (destroyed) return
        when (app.pleos?.driving?.value ?: gateParked?.not()) {
            // The station already on when the car pulls away counts too, once it has played 10 s on the road.
            true -> if (!voyage.sailing) { voyage.depart(); hearOnRoad(player.currentMediaItem?.mediaId?.takeIf { player.isPlaying }) }
            false -> if (voyage.sailing) voyage.dock()?.let(::docked) ?: run { if (voyage.met > 0) app.voyage.value = null }
            null -> Unit
        }
    }

    /**
     * Today's voyage (2026-10-22): [id]'s country counts once it has played [Voyage.HEARD_MS] on the road — passing
     * stations by does not. A new station, a pause or leaving P to P again starts the count over; null just stops it.
     */
    private fun hearOnRoad(id: String?) {
        voyageEar?.cancel()
        if (id == null || !voyage.sailing) return
        voyageEar = scope.launch {
            delay(Voyage.HEARD_MS)
            val station = repo.find(id) ?: return@launch
            if (!voyage.sailing || !player.isPlaying || player.currentMediaItem?.mediaId != id) return@launch
            val km = withContext(Dispatchers.Default) { routeTo(station)?.km }
            voyage.heard(station.countryCode, km)
        }
    }

    /** Back in P after a drive: the countries met go to the globe, and the car says where the ears went. */
    private fun docked(log: Voyage.Log) {
        app.voyage.value = log
        val speaker = voice ?: return
        if (!VoiceGuide.isOn(this)) return
        val text = VoyageText.spoken(this, log) { directory().label(it) }
        speech?.cancel()
        player.volume = DUCKED
        speaker.say(text, Locale.getDefault()) { player.volume = 1f }
    }

    /** The car media screen's course folder and its 'drift now' line follow the trip. */
    private fun refreshCourses() {
        for (parent in listOf(RADIO, COURSES)) session.notifyChildrenChanged(parent, children(parent).size, null)
    }

    private fun carPlace(): LatLon? = app.pleos?.location ?: CarLocation.current(this)

    /** The country under the car (worked out off the main thread); null without a position or at sea. */
    private suspend fun carCountry(): String? = withContext(Dispatchers.Default) {
        runCatching { carPlace()?.let { app.loadJourneys().second.locate(it) } }.getOrNull()
    }

    private inner class LibraryCallbacks : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val standard = super.onConnect(session, controller)
            if (!standard.isAccepted) return standard
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commandsFor(controller, standard.availableSessionCommands))
                .setAvailablePlayerCommands(standard.availablePlayerCommands)
                .build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (customCommand.customAction == VoiceCommand.TALK.customAction) {
                talk()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            val action = VoiceCommand.action(args).takeIf { customCommand.customAction == VoiceCommand.SESSION.customAction }
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            val reply = SettableFuture.create<SessionResult>()
            scope.launch { reply.set(SessionResult(SessionResult.RESULT_SUCCESS, VoiceCommand.extras(act(action)))) }
            return reply
        }
        // SEARCH_SUPPORTED shows the car media screen's search button (typing while parked, voice while driving).
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, getString(R.string.app_name)),
                LibraryParams.Builder().setExtras(Bundle().apply { putBoolean(SEARCH_SUPPORTED, true) }).build()))
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (page < 0 || pageSize <= 0) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val all = children(parentId)
            val start = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
            return Futures.immediateFuture(LibraryResult.ofItemList(all.drop(start).take(pageSize), params))
        }
        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofVoid()).also { session.notifyChildrenChanged(browser, parentId, children(parentId).size, params) }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = repo.find(mediaId)?.let(::stationItem)
                ?: (children(ROOT) + children(RADIO) + children(COURSES)).find { it.mediaId == mediaId }
                ?: mediaId.takeIf { it.startsWith(COURSE) }?.let { Courses.byId(it.removePrefix(COURSE)) }?.let { playable(mediaId, courseLabel(it)) }
                ?: if (mediaId == ROOT) folder(ROOT, getString(R.string.app_name)) else null
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofVoid()).also { session.notifySearchResultChanged(browser, query, searchResults(query).size, params) }
        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = searchResults(query)
            val start = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(0)).coerceAtMost(all.size.toLong()).toInt()
            return Futures.immediateFuture(LibraryResult.ofItemList(all.drop(start).take(pageSize.coerceIn(0, 100)), params))
        }
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            // Resolve server-owned IDs only. A remote controller cannot inject arbitrary stream URIs.
            val resolved = if (mediaItems.size == 1) start(mediaItems.single()).orEmpty()
                else mediaItems.mapNotNull { item -> repo.find(item.mediaId)?.let(::stationItem) }
            return Futures.immediateFuture(resolved)
        }
        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // 하나를 고르면(방송·지금 표류·항로·분위기·음성) 그 뒤는 표류로 채운 대기열. 여럿이면(앱이 만든 대기열) 그대로.
            val resolved = if (mediaItems.size == 1) start(mediaItems.single()).orEmpty()
                else mediaItems.mapNotNull { repo.find(it.mediaId)?.let(::stationItem) }
            if (resolved.isEmpty()) return Futures.immediateFailedFuture(IllegalArgumentException("Unknown radio station"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, resolved.lastIndex), startPositionMs))
        }
        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Called only for an explicit controller play/resume request, never on app connection.
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    repo.initialize()
                    if (repo.state.value.stations.isEmpty()) repo.refresh()
                    val first = repo.state.value.lastPlayed ?: repo.state.value.stations.firstOrNull()
                        ?: throw IllegalStateException("No radio stations available")
                    result.set(MediaSession.MediaItemsWithStartPosition(drifting(first), 0, C.TIME_UNSET))
                } catch (error: Exception) { result.setException(error) }
            }
            return result
        }
    }
    /** A language change rebuilds what the car's media screen shows (folder names, journey lines) in the new language. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::session.isInitialized) return
        for (parent in listOf(ROOT, RADIO, COURSES, FAVORITES)) {
            session.notifyChildrenChanged(parent, children(parent).size, null)
        }
        offerTalk() // the button's name, and whether the car hears the new language at all
    }
    /**
     * The screen's task went. Pleos' home screen removes it when the driver switches to the car's media screen
     * (2026-10-22, emulator: the radio stopped right there) — a playing radio keeps playing then. Only a radio that
     * isn't playing goes away with its screen.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player.playWhenReady && player.mediaItemCount > 0) return
        retry?.cancel(); player.stop(); stopSelf()
    }
    /** Says where the new station [id] comes from, the radio turned down meanwhile (voice guide switch permitting). */
    private fun announce(id: String?) {
        // The intro belongs to this station only: with the guide off it goes unsaid rather than turn up later.
        val first = intro?.takeIf { it.first == id }?.second
        intro = null
        val station = id?.let(repo::find) ?: return
        val speaker = voice ?: return
        if (!VoiceGuide.isOn(this)) return
        speech?.cancel()
        speech = scope.launch {
            val text = withContext(Dispatchers.Default) { spokenJourney(station, first) }
            if (player.currentMediaItem?.mediaId != id || !player.isPlaying) return@launch
            player.volume = DUCKED
            speaker.say(text, Locale.getDefault()) { player.volume = 1f }
        }
    }

    /** Cuts the voice guide short (a newer station, or the radio stopping) and gives the radio its volume back. */
    private fun quiet() {
        speech?.cancel()
        voice?.stop()
        player.volume = 1f
    }

    /**
     * "Namibia. Broadcasting from Windhoek. 10,499 km away, about 10 days 22 hours by car." — the main screen's own
     * words (50 languages). The distance needs the car's position (Pleos FusedLocation, else Android's); without one
     * the guide says only where the station is. Pleos cars (2026-10-21, gear): moving, it stops at the distance;
     * parked, the place's hour follows ("그곳은 지금 오후 11:05"). On the road a crossing or the arrival comes first ([intro]).
     */
    private fun spokenJourney(station: Station, intro: String? = null): String {
        val driving = runCatching { app.pleos?.driving?.value }.getOrNull()
        val from = localCapital(station)?.let { getString(R.string.journey_from, it) }
        val detail = routeTo(station)?.let { route ->
            if (driving == true) JourneyText.distance(this, route) else JourneyText.detail(this, route, approximate = false)
        }
        val clock = if (driving == false) clockAt(station) else null
        return SpokenJourney.text(localCountry(station), from, detail, clock, intro)
    }

    /** The drive from the car to [station]'s country, by road and sea; null without the car's place. Off the main thread. */
    private fun routeTo(station: Station): Route? = runCatching {
        val here = carPlace() ?: return@runCatching null
        val target = app.loadAtlas().countries[station.countryCode] ?: return@runCatching null
        val (finder, locator) = app.loadJourneys()
        locator.locate(here)?.let { finder.find(here, it, target.code) }
    }.getOrNull()

    /** "그곳은 지금 오후 11:05" — the station's own hour; nothing where it keeps the car's hour or the zone is unclear. */
    private fun clockAt(station: Station): String? = runCatching {
        val code = station.countryCode.ifBlank { return@runCatching null }
        val capital = app.loadAtlas().countries[code]
        val zones = android.icu.util.TimeZone.getAvailableIDs(
            android.icu.util.TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, code, null).toList()
        val now = Instant.now()
        val zone = PlaceClock.zone(code, zones, capital?.capitalName, capital?.capital?.lon, now) ?: return@runCatching null
        PlaceClock.spoken(zone, ZoneId.systemDefault(), now, Locale.getDefault())?.let { getString(R.string.journey_clock, it) }
    }.getOrNull()

    override fun onDestroy() {
        destroyed = true
        quiet(); ownVoice?.release()
        ear?.stop(); cue?.release() // the ear belongs to the app (PleosConnect); it only stops listening
        parkedGate?.stop()
        heardProbe?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel(); player.release(); session.release(); super.onDestroy()
    }
}
