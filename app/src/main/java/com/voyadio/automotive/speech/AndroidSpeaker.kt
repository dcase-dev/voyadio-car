package com.voyadio.automotive.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Android's own text-to-speech, for cars and tablets without Pleos. No engine, or no voice for the car's language:
 * it stays quiet and reports done at once.
 */
class AndroidSpeaker(context: Context) : Speaker {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var current: String? = null
    private var done: (() -> Unit)? = null
    private var count = 0

    private val engine = TextToSpeech(context.applicationContext) { status ->
        main.post { ready = status == TextToSpeech.SUCCESS }
    }.apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = finish(id)
            @Deprecated("Deprecated in Java") override fun onError(id: String?) = finish(id)
            override fun onError(id: String?, errorCode: Int) = finish(id)
            override fun onStop(id: String?, interrupted: Boolean) = finish(id)
        })
    }

    override fun say(text: String, locale: Locale, onDone: () -> Unit) {
        stop()
        val supported = ready && engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
        if (!supported) { onDone(); return }
        val id = "voyadio-${++count}"
        current = id; done = onDone
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
    }

    override fun stop() {
        val waiting = done
        current = null; done = null
        if (ready) engine.stop()
        waiting?.invoke()
    }

    override fun release() { stop(); engine.shutdown() }

    private fun finish(id: String?) {
        main.post {
            if (id == null || id != current) return@post
            val waiting = done
            current = null; done = null
            waiting?.invoke()
        }
    }
}
