package com.voyadio.automotive

import androidx.media3.common.PlaybackException

/**
 * What to do when a station won't play (2026-10-15): try the same station again, move to the next station in the
 * queue, or stop. In the car a dead station must not leave silence — the driver can't fix it while moving.
 * - The station's own fault (refused, missing, unplayable format) → next station at once.
 * - A slow or dropped connection → the same station again ([MAX_RETRIES] times, 2 s then 4 s), then the next.
 * - The car is offline → never run down the queue; retry, then stop with the error showing.
 * - [MAX_SKIPS] dead stations in a row, or the end of the queue → stop.
 */
internal object PlaybackRecovery {
    sealed interface Action {
        data class Retry(val delayMs: Long) : Action
        data object Skip : Action
        data object GiveUp : Action
    }

    const val MAX_RETRIES = 2
    const val MAX_SKIPS = 4

    fun decide(errorCode: Int, retries: Int, skips: Int, hasNext: Boolean, online: Boolean): Action {
        val stationFault = online && stationFault(errorCode)
        if (!stationFault && retries < MAX_RETRIES) return Action.Retry((retries + 1) * 2_000L)
        if (!online || !hasNext || skips >= MAX_SKIPS) return Action.GiveUp
        return Action.Skip
    }

    private fun stationFault(code: Int) = code in STATION_IO ||
        code in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        code in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

    private val STATION_IO = setOf(
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    )
}
