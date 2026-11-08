package com.voyadio.automotive.pleos

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import com.voyadio.automotive.speech.Recognizer
import java.util.Locale

/**
 * The talk button's ear: Pleos SpeechToText SDK, on-device engine (no app registration or network) and the car's own
 * microphone. Hears Korean, English, French and Spanish. Checked on the Pleos emulator with recorded speech
 * (pleosDebug/SttProbe, 2026-10-21): "일본 라디오 틀어줘" came back word for word.
 */
internal class PleosRecognizer(context: Context) : Recognizer {
    private val main = Handler(Looper.getMainLooper())
    private val stt = SpeechToText(context, Mode.ON_DEVICE)
    @Volatile private var ready = false
    private var started: (() -> Unit)? = null
    private var heard: ((String?) -> Unit)? = null
    private var listening: (() -> Unit)? = null
    /** The latest words so far: the engine reports the end of speech just before the final words (13 ms, emulator). */
    @Volatile private var partial: String? = null
    private val late = Runnable { finish(partial) }
    private val limit = Runnable { Log.i(TAG, "no words in time"); stop() }
    /** The engine said nothing about being ready: cue the driver anyway. */
    private val unready = Runnable { hearing("assumed") }

    private val results = object : ResultListener {
        override fun onUpdated(stt: String, completed: Boolean) { if (completed) finish(stt) else partial = stt }
        override fun onEndedRecognition() { main.postDelayed(late, FINAL_WAIT_MS) }
        override fun onError(errorType: ErrorType) { Log.i(TAG, "recognition ended: $errorType"); finish(null) }
        override fun onReady() { main.post { hearing("engine ready") } }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    /** The microphone is open: the caller cues the driver, who then has [SILENCE_MS] to speak. */
    private fun hearing(why: String) {
        val cue = listening ?: return
        listening = null
        main.removeCallbacks(unready); main.removeCallbacks(limit)
        main.postDelayed(limit, SILENCE_MS)
        Log.i(TAG, "listening ($why)")
        cue()
    }

    init {
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                runCatching {
                    stt.addListener(results)
                    ready = true
                    Log.i(TAG, "speech recognizer ready")
                    main.post { started?.invoke(); started = null }
                }.onFailure { Log.w(TAG, "speech recognizer setup failed", it) }
            }
            override fun onFailure(errorType: ErrorType) { Log.w(TAG, "speech recognizer unavailable: $errorType") }
        })
    }

    override fun hears(locale: Locale) = ready && locale.language in LANGUAGES

    override fun whenReady(action: () -> Unit) { if (ready) main.post(action) else started = action }

    override fun listen(locale: Locale, listening: () -> Unit, heard: (String?) -> Unit) {
        stop()
        if (!hears(locale)) { heard(null); return }
        this.heard = heard
        this.listening = listening
        partial = null
        main.postDelayed(unready, READY_WAIT_MS)
        runCatching { stt.setLocale(engineLocale(locale)); stt.request() }
            .onFailure { Log.w(TAG, "listening failed", it); finish(null) }
    }

    override fun stop() {
        val waiting = heard ?: return
        heard = null; listening = null
        main.removeCallbacks(late); main.removeCallbacks(limit); main.removeCallbacks(unready)
        runCatching { stt.stop() }
        waiting(null)
    }

    override fun release() { stop(); runCatching { stt.removeListener(results); stt.release() } }

    /** SDK callbacks may come off the main thread; the waiting caller hears back on the main thread, once. */
    private fun finish(words: String?) {
        main.post {
            val waiting = heard ?: return@post
            heard = null; listening = null
            main.removeCallbacks(late); main.removeCallbacks(limit); main.removeCallbacks(unready)
            waiting(words?.trim()?.ifEmpty { null })
        }
    }

    private companion object {
        const val TAG = "PleosRecognizer"
        /** The engine's languages (Pleos STT documents): ko-KR, en-US, fr-CA, es-US, es-MX. */
        val LANGUAGES = setOf("ko", "en", "fr", "es")
        const val FINAL_WAIT_MS = 1_500L
        /** The engine took 2.3 s to open the microphone on the emulator; past this the cue sounds regardless. */
        const val READY_WAIT_MS = 4_000L
        /** A request is a few words; this long after the cue with nothing said, the button lets go. */
        const val SILENCE_MS = 8_000L

        fun engineLocale(locale: Locale): Locale = when (locale.language) {
            "ko" -> Locale.KOREA
            "fr" -> Locale.CANADA_FRENCH
            "es" -> Locale("es", if (locale.country == "MX") "MX" else "US")
            else -> Locale.US
        }
    }
}
