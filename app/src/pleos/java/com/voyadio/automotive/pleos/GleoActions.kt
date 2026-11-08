package com.voyadio.automotive.pleos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.voyadio.automotive.VoyadioApplication
import com.voyadio.automotive.voice.VoiceRemote
import org.json.JSONObject

/**
 * Gleo AI app actions (2026-10-21): Gleo, the Pleos car assistant, reads our schema.json when the app is installed and
 * calls its three functions as broadcasts to this app — "보야디오, 다른 나라" · "보야디오, 일본 라디오" ·
 * "보야디오, 이 방송 저장". RadioService does it (the same drift as the buttons) and Gleo hears back what happened.
 * Exported without a permission — Gleo holds none we could ask for — so a sender gets no more than the steering wheel
 * gives: next, a course, save. Stream addresses never come from outside.
 */
class GleoActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        val call = GleoCall.from(intent.action, extras?.keySet()?.associateWith { extras.getString(it) }.orEmpty()) ?: return
        val app = context.applicationContext as VoyadioApplication
        val action = call.action() ?: return answer(app, GleoReply.asking(call.function))
        val pending = goAsync()
        VoiceRemote.send(app, action) { result ->
            Log.i(TAG, "${call.function} → ${result.outcome}")
            answer(app, GleoReply.json(call.function, result, facts(app, result.stationId)))
            pending.finish()
        }
    }

    /** The station in English — Gleo writes its own reply from these. */
    private fun facts(app: VoyadioApplication, id: String?): GleoReply.Facts {
        val station = id?.let(app.repository::find) ?: return GleoReply.Facts()
        val country = app.atlas?.countries?.get(station.countryCode)
        return GleoReply.Facts(station.name, country?.name ?: station.country.ifBlank { null },
            country?.capitalName?.takeIf { it != country.name })
    }

    private fun answer(context: Context, reply: JSONObject) = context.sendBroadcast(Intent(GleoReply.ACTION)
        .putExtra("result", reply.getString("status"))
        .putExtra("packageName", context.packageName)
        .putExtra("response", reply.toString()))

    private companion object {
        const val TAG = "GleoActions"
    }
}
