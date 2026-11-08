package com.voyadio.automotive.speech

import java.util.Locale

/**
 * A voice that reads one short line over the radio (voice guide, 2026-10-21). Pleos cars use the Pleos TextToSpeech
 * SDK ([com.voyadio.automotive.pleos.PleosConnect.speaker]); everything else Android's own engine ([AndroidSpeaker]).
 * Call on the main thread. [say] reports back through `onDone` exactly once, on the main thread — finished, failed,
 * language not supported, or cut off by [stop] or a newer [say].
 */
interface Speaker {
    fun say(text: String, locale: Locale, onDone: () -> Unit)
    fun stop()
    fun release()
}
