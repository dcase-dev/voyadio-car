package com.voyadio.automotive.voice

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.voyadio.automotive.RadioService

/**
 * Hands a [VoiceAction] to RadioService — starting it when needed — and [done] hears what came of it, on the main
 * thread. Call on the main thread. The service keeps the playback; this only carries the request.
 */
object VoiceRemote {
    @OptIn(UnstableApi::class)
    fun send(context: Context, action: VoiceAction, done: (VoiceResult) -> Unit) {
        val app = context.applicationContext
        val main = ContextCompat.getMainExecutor(app)
        val connecting = MediaController.Builder(app, SessionToken(app, ComponentName(app, RadioService::class.java))).buildAsync()
        connecting.addListener({
            val controller = runCatching { connecting.get() }.getOrNull()
                ?: return@addListener done(VoiceResult(VoiceResult.Outcome.FAILED))
            val reply = controller.sendCustomCommand(VoiceCommand.SESSION, VoiceCommand.args(action))
            reply.addListener({
                val answer = runCatching { reply.get() }.getOrNull()?.takeIf { it.resultCode == SessionResult.RESULT_SUCCESS }
                controller.release()
                done(answer?.let { VoiceCommand.result(it.extras) } ?: VoiceResult(VoiceResult.Outcome.FAILED))
            }, main)
        }, main)
    }
}
