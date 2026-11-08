package com.voyadio.automotive.speech

/**
 * When the radio says where the new station comes from (voice guide, 2026-10-21 — design
 * docs/superpowers/specs/2026-10-21-car-pleos-sdk-design.md §2): once per new station, at the moment its sound starts.
 * Not for the first station after the radio starts (resuming, or the app opening straight into the radio), not again
 * when the same station resumes, and not for a station that never played (a dead one skipped on the way).
 */
class AnnounceGate {
    private var heard: String? = null
    private var pending: String? = null
    private var started = false

    /** True once a station has played: from then on each new station is named (a voice command relies on this). */
    val announcesNext: Boolean get() = started

    /** The queue moved to [id] (next, steering wheel, automatic skip, a new course). */
    fun onTransition(id: String?) {
        pending = if (id != null && started && id != heard) id else null
    }

    /** Sound started on [id]. True when the voice guide should speak now. */
    fun onPlaying(id: String?): Boolean {
        if (id == null) return false
        val speak = id == pending
        pending = null
        heard = id
        started = true
        return speak
    }
}
