package com.voyadio.automotive.pleos

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import com.voyadio.automotive.speech.Speaker
import java.util.Locale

/**
 * The voice guide in the car's own voice: Pleos TextToSpeech SDK, on-device engine (needs no app registration or
 * network — a campsite may have neither). A language the engine lacks ends in onError and the guide stays quiet.
 */
internal class PleosSpeaker(context: Context) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    @Volatile private var ready = false
    private var done: (() -> Unit)? = null

    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = finish()
        override fun onError(errorType: ErrorType) { Log.w(TAG, "speech failed: $errorType"); finish() }
        override fun onStop() = finish()
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init {
        // The SDK's order: initialize, then listeners and settings — calling setAudioAttributes first throws inside the
        // SDK ("lateinit property tts has not been initialized", Pleos Connect emulator 2.1.0, 2026-10-21).
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                runCatching {
                    tts.addEventListener(events)
                    tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    ready = true
                    Log.i(TAG, "speech engine ready")
                }.onFailure { Log.w(TAG, "speech engine setup failed", it) }
            }
            override fun onFailure(errorType: ErrorType) { Log.w(TAG, "speech engine unavailable: $errorType") }
        })
    }

    override fun say(text: String, locale: Locale, onDone: () -> Unit) {
        stop()
        if (!ready) { onDone(); return }
        done = onDone
        runCatching { tts.setLocale(locale); tts.speak(text, true) }
            .onFailure { Log.w(TAG, "speak failed", it); finish() }
    }

    override fun stop() {
        val waiting = done ?: return
        done = null
        runCatching { tts.stop() }
        waiting()
    }

    override fun release() { stop(); runCatching { tts.release() } }

    /** SDK callbacks may come off the main thread; the waiting caller hears back on the main thread, once. */
    private fun finish() {
        main.post {
            val waiting = done
            done = null
            waiting?.invoke()
        }
    }

    private companion object { const val TAG = "PleosSpeaker" }
}
