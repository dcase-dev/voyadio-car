package com.voyadio.automotive

import androidx.media3.common.PlaybackException
import com.voyadio.automotive.PlaybackRecovery.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryTest {
    private val timeout = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    private val badStatus = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS

    @Test fun `a station that refuses or sends something unplayable is skipped at once`() {
        assertEquals(Action.Skip, PlaybackRecovery.decide(badStatus, retries = 0, skips = 0, hasNext = true, online = true))
        assertEquals(Action.Skip, PlaybackRecovery.decide(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, 0, 0, true, true))
        assertEquals(Action.Skip, PlaybackRecovery.decide(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, 0, 0, true, true))
    }

    @Test fun `a slow or dropped connection is tried again twice, then the next station plays`() {
        assertEquals(Action.Retry(2_000), PlaybackRecovery.decide(timeout, retries = 0, skips = 0, hasNext = true, online = true))
        assertEquals(Action.Retry(4_000), PlaybackRecovery.decide(timeout, retries = 1, skips = 0, hasNext = true, online = true))
        assertEquals(Action.Skip, PlaybackRecovery.decide(timeout, retries = 2, skips = 0, hasNext = true, online = true))
    }

    @Test fun `without internet it never runs down the queue`() {
        // 기기 인터넷이 끊겼으면 방송 탓이 아니다: 대기열을 헛되이 넘기지 않고 다시 시도 후 멈춘다.
        assertEquals(Action.Retry(2_000), PlaybackRecovery.decide(badStatus, 0, 0, hasNext = true, online = false))
        assertEquals(Action.GiveUp, PlaybackRecovery.decide(timeout, retries = 2, skips = 0, hasNext = true, online = false))
    }

    @Test fun `it stops after several dead stations in a row or at the end of the queue`() {
        assertEquals(Action.GiveUp, PlaybackRecovery.decide(badStatus, 0, skips = PlaybackRecovery.MAX_SKIPS, hasNext = true, online = true))
        assertEquals(Action.GiveUp, PlaybackRecovery.decide(badStatus, 0, skips = 0, hasNext = false, online = true))
    }
}
