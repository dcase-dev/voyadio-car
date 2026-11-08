package com.voyadio.automotive

/** Ignore delayed callbacks from an earlier activity or vehicle-service connection. Main thread only. */
internal class ParkedAccess(private val changed: (Boolean) -> Unit) {
    private var session = 0L
    private var source = 0L

    fun begin(): Long {
        end()
        return session
    }

    fun observe(expectedSession: Long): Long? {
        if (expectedSession != session) return null
        source++
        changed(false)
        return source
    }

    fun report(expectedSession: Long, expectedSource: Long, requiresRestriction: Boolean?) {
        if (expectedSession == session && expectedSource == source) {
            changed(requiresRestriction == false)
        }
    }

    fun end() {
        session++
        source++
        changed(false)
    }
}
