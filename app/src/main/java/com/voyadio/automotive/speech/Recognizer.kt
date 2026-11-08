package com.voyadio.automotive.speech

import java.util.Locale

/**
 * The car's ear for the talk button (2026-10-21): hears one spoken request through the car's microphone. Pleos cars
 * only — Pleos SpeechToText SDK ([com.voyadio.automotive.pleos.PleosConnect.recognizer]); elsewhere there is no talk
 * button. Call on the main thread. [listen] reports back through `heard` exactly once, on the main thread: the words,
 * or null — nothing understood, an error, the time limit, or cut off by [stop] or a newer [listen].
 */
interface Recognizer {
    /** Whether it is ready to hear [locale]'s language. */
    fun hears(locale: Locale): Boolean

    /** [action] runs once, on the main thread, when the recognizer has started up (it does so in the background). */
    fun whenReady(action: () -> Unit)

    /**
     * Listens once. [listening] runs when the engine really hears — it takes a moment to start (2.3 s on the Pleos
     * emulator), and the driver's cue belongs there or the first words are lost; [heard] then gets the words.
     */
    fun listen(locale: Locale, listening: () -> Unit, heard: (String?) -> Unit)
    fun stop()
    fun release()
}
