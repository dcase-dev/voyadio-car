package com.voyadio.automotive.speech

import android.content.Context

/** Whether the voice guide speaks (a switch on the main screen, remembered on the device; on by default). */
object VoiceGuide {
    private const val PREFS = "voice"
    private const val ON = "guide"

    fun isOn(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON, true)

    fun set(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ON, on).apply()
}
