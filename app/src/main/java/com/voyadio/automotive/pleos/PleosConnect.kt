package com.voyadio.automotive.pleos

import com.voyadio.automotive.geo.LatLon
import com.voyadio.automotive.speech.Recognizer
import com.voyadio.automotive.speech.Speaker
import com.voyadio.automotive.trip.Destination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Pleos Connect SDK adds on Pleos cars (Hyundai·Kia·Genesis, 2026-10-21 — design
 * docs/superpowers/specs/2026-10-21-car-pleos-sdk-design.md). Only the 'pleos' build fills it ([PleosBridge]);
 * the 'generic' build and every non-Pleos device get null and the app works exactly as before.
 * Each value is optional on its own: a Pleos car may still refuse one SDK (permission, capability).
 */
interface PleosConnect {
    /** The car's position from the FusedLocation SDK; null until a fix arrives. */
    val location: LatLon?

    /** Moving (gear D, R, N) or parked (P), from the Vehicle SDK; null until known. Shapes the voice guide's length
     *  and today's voyage (from leaving P to P again, 2026-10-22). */
    val driving: StateFlow<Boolean?>

    /** The car's own voice (Pleos TextToSpeech SDK, on-device engine) for the voice guide; null if it can't start. */
    val speaker: Speaker?

    /** The car's ear (Pleos SpeechToText SDK, on-device engine) for the talk button; null if it can't start. */
    val recognizer: Recognizer?

    /** The navigation's destination (NaviHelper SDK) for the road's radio; null when there is none. */
    val destination: StateFlow<Destination?>

    /** Each arrival at the navigation's destination, as it happens. */
    val arrivals: Flow<Destination>

    /** The driver answered a permission request (location): start what was waiting for it. */
    fun onPermissionsChanged()

    fun release()
}
