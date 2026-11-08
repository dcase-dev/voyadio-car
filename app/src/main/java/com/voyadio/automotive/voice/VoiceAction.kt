package com.voyadio.automotive.voice

import android.os.Bundle
import androidx.media3.session.SessionCommand
import com.voyadio.automotive.drift.Course
import com.voyadio.automotive.drift.Courses

/**
 * A spoken request to the radio (2026-10-21, design docs/superpowers/specs/2026-10-21-car-pleos-sdk-design.md §3):
 * from Gleo, the Pleos car assistant ("보야디오, 일본 라디오"), or from the talk button through the car's speech
 * recognizer. Only what the steering wheel and the car's media screen can already do — never a stream address.
 */
sealed interface VoiceAction {
    /** "다른 나라" — the next station along the course. */
    data object Next : VoiceAction
    /** "일본 라디오" — a new course, then the drift along it. */
    data class Steer(val course: Course) : VoiceAction
    /** "이 방송 저장" — the playing station into favourites. */
    data object Save : VoiceAction
}

/** What came of a [VoiceAction]. [announced]: the voice guide will name the new station itself. */
data class VoiceResult(
    val outcome: Outcome, val stationId: String? = null, val courseId: String? = null, val announced: Boolean = false,
) {
    enum class Outcome {
        /** A station is starting. */
        PLAYING,
        SAVED,
        ALREADY_SAVED,
        /** No station on that course is on the air — or none at all yet (no signal). */
        NO_STATIONS,
        /** Nothing is playing to save. */
        NOTHING_PLAYING,
        /** The radio could not be reached, or the save failed. */
        FAILED,
    }
}

/** Carries a [VoiceAction] to RadioService as a session command, and its [VoiceResult] back. */
object VoiceCommand {
    /** Offered to this app's own controllers only (RadioService's onConnect). */
    val SESSION = SessionCommand("com.voyadio.automotive.VOICE", Bundle.EMPTY)
    /** The talk button — the car's media screen and the camp screen: the radio listens for one request. */
    val TALK = SessionCommand("com.voyadio.automotive.TALK", Bundle.EMPTY)
    private const val KIND = "kind"
    private const val COURSE = "course"
    private const val OUTCOME = "outcome"
    private const val STATION = "station"
    private const val ANNOUNCED = "announced"

    fun args(action: VoiceAction) = Bundle().apply {
        when (action) {
            VoiceAction.Next -> putString(KIND, "next")
            VoiceAction.Save -> putString(KIND, "save")
            is VoiceAction.Steer -> { putString(KIND, "steer"); putString(COURSE, action.course.id) }
        }
    }

    fun action(args: Bundle): VoiceAction? = when (args.getString(KIND)) {
        "next" -> VoiceAction.Next
        "save" -> VoiceAction.Save
        "steer" -> Courses.byId(args.getString(COURSE))?.let(VoiceAction::Steer)
        else -> null
    }

    fun extras(result: VoiceResult) = Bundle().apply {
        putString(OUTCOME, result.outcome.name)
        putString(STATION, result.stationId)
        putString(COURSE, result.courseId)
        putBoolean(ANNOUNCED, result.announced)
    }

    fun result(extras: Bundle): VoiceResult? {
        val outcome = VoiceResult.Outcome.entries.firstOrNull { it.name == extras.getString(OUTCOME) } ?: return null
        return VoiceResult(outcome, extras.getString(STATION), extras.getString(COURSE), extras.getBoolean(ANNOUNCED))
    }
}
