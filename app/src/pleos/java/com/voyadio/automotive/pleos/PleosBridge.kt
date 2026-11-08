package com.voyadio.automotive.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import com.voyadio.automotive.geo.LatLon
import com.voyadio.automotive.speech.Recognizer
import com.voyadio.automotive.speech.Speaker
import com.voyadio.automotive.trip.Destination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The 'pleos' build: connects the Pleos Connect SDKs this car offers. On a car (or emulator) without Pleos
 * services every call fails inside here and [connect] returns null — the app then runs as the generic build.
 */
object PleosBridge {
    /** Runtime permissions the Pleos SDKs need from the driver — asked together with Android's location permission. */
    val permissions = listOf("pleos.car.permission.FUSED_LOCATION")

    fun connect(context: Context): PleosConnect? {
        val app = context.applicationContext
        val location = runCatching { FusedLocation(app).takeIf { it.isSupported() } }
            .onFailure { Log.i(TAG, "FusedLocation unavailable: $it") }.getOrNull() ?: return null
        return Connected(app, location)
    }

    private class Connected(private val app: Context, private val fused: FusedLocation) : PleosConnect {
        @Volatile override var location: LatLon? = null
            private set

        /** Started with the app: the voice guide asks it each time a station starts; the voyage follows its changes. */
        private val gear = runCatching { PleosGear(app) }.onFailure { Log.i(TAG, "Vehicle gear unavailable: $it") }.getOrNull()
        override val driving: StateFlow<Boolean?> = gear?.driving ?: MutableStateFlow(null)

        /** The navigation, followed from the start: a destination set before the app opened still counts. */
        private val navi = runCatching { PleosNavi(app) }.onFailure { Log.i(TAG, "NaviHelper unavailable: $it") }.getOrNull()
        override val destination: StateFlow<Destination?> = navi?.destination ?: MutableStateFlow(null)
        override val arrivals: Flow<Destination> = navi?.arrivals ?: emptyFlow()

        /** Started on first use (the radio service's first announcement), not with the app. */
        override val speaker: Speaker? by lazy {
            runCatching { PleosSpeaker(app) }.onFailure { Log.w(TAG, "TextToSpeech unavailable", it) }.getOrNull()
        }

        /** Started with the radio service, so the talk button is ready by the time it shows. */
        override val recognizer: Recognizer? by lazy {
            runCatching { PleosRecognizer(app) }.onFailure { Log.w(TAG, "SpeechToText unavailable", it) }.getOrNull()
        }

        private val fixes = object : FusedLocationListener {
            override fun onChanged(data: LocationData?) {
                if (data == null) return
                if (location == null) Log.i(TAG, "first Pleos location fix")
                location = LatLon(data.latitude, data.longitude)
            }
            override fun onError(error: Exception) { Log.w(TAG, "FusedLocation error", error) }
        }

        @Volatile private var listening = false

        init {
            runCatching { fused.initialize() }.onFailure { Log.w(TAG, "FusedLocation initialize failed", it) }
            startLocation()
        }

        /** Without pleos.car.permission.FUSED_LOCATION the SDK throws; [onPermissionsChanged] tries again. */
        private fun startLocation() {
            if (listening) return
            runCatching {
                fused.getLastKnownLocation(fixes)
                fused.registerFusedLocationCallback(fixes)
                listening = true
            }.onFailure { Log.i(TAG, "FusedLocation not started: ${it.message}") }
        }

        override fun onPermissionsChanged() = startLocation()

        override fun release() {
            runCatching { fused.unregisterFusedLocationCallback(fixes); fused.release() }
            gear?.release()
            navi?.release()
        }
    }

    private const val TAG = "PleosBridge"
}
