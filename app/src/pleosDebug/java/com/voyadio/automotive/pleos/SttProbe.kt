package com.voyadio.automotive.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.voyadio.automotive.voice.Heard
import java.io.File
import java.util.Locale

/**
 * Debug builds only (2026-10-21): feeds a recorded 16 kHz 16-bit mono PCM file from the app's files folder to the
 * car's on-device speech recognizer — checks recognition on the Pleos emulator without the Mac's microphone. The final
 * words then go to the radio service like the talk button's ([Heard], demo video 2026-10-22).
 *   adb shell am broadcast --user 10 -p com.voyadio.automotive.dev -a com.voyadio.automotive.debug.STT_PROBE \
 *     --es file ko-japan.pcm --es locale ko-KR
 */
class SttProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val name = intent.getStringExtra("file") ?: return
        val pcm = runCatching { File(app.filesDir, name).readBytes() }.getOrElse { Log.w(TAG, "no $name", it); return }
        val locale = Locale.forLanguageTag(intent.getStringExtra("locale") ?: "ko-KR")
        engine?.release()
        val stt = SpeechToText(app, Mode.ON_DEVICE).also { engine = it }
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                Log.i(TAG, "initialized — $locale, $name ${pcm.size} bytes")
                stt.setLocale(locale)
                stt.addListener(Results(app))
                Thread {
                    for (at in pcm.indices step CHUNK) {
                        stt.sendAudio(pcm.copyOfRange(at, minOf(at + CHUNK, pcm.size)))
                        Thread.sleep(100)
                    }
                    stt.completeAudioSend()
                    Log.i(TAG, "audio sent")
                }.start()
            }
            override fun onFailure(errorType: ErrorType) { Log.w(TAG, "initialize failed: $errorType") }
        })
    }

    private class Results(private val app: Context) : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            Log.i(TAG, "text(final=$isFinal): $text")
            // The final words go to the radio service the way the talk button's do (demo video, 2026-10-22).
            if (isFinal && text.isNotBlank()) {
                app.sendBroadcast(Intent(Heard.ACTION).setPackage(app.packageName).putExtra(Heard.WORDS, text))
            }
        }
        override fun onUpdatedEpdData(start: Long, end: Long) { Log.i(TAG, "speech $start..$end") }
        override fun onStartedRecognition() { Log.i(TAG, "started") }
        override fun onEndedRecognition() { Log.i(TAG, "ended") }
        override fun onError(errorType: ErrorType) { Log.w(TAG, "error: $errorType") }
        override fun onReady() { Log.i(TAG, "ready") }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    companion object {
        private const val TAG = "SttProbe"
        /** 100 ms of 16 kHz 16-bit mono audio. */
        private const val CHUNK = 3_200
        private var engine: SpeechToText? = null
    }
}
