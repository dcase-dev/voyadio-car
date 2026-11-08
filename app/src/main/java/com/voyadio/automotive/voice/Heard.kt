package com.voyadio.automotive.voice

/**
 * Debug builds only (demo video, 2026-10-22): words a recorded voice gave the car's recognizer, from the pleosDebug
 * SttProbe to the radio service — handled like the talk button's. The Pleos emulator has no microphone.
 */
object Heard {
    const val ACTION = "com.voyadio.automotive.debug.HEARD"
    const val WORDS = "words"
}
