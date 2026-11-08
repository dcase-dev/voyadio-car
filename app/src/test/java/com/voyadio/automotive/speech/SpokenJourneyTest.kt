package com.voyadio.automotive.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenJourneyTest {
    @Test fun `country, origin and distance become three spoken sentences`() {
        assertEquals("나미비아. 빈트후크에서 오는 방송. 10,499km 떨어진 곳, 차로 약 10일 22시간",
            SpokenJourney.text("나미비아", "빈트후크에서 오는 방송", "10,499km 떨어진 곳 · 차로 약 10일 22시간"))
    }

    @Test fun `parked, the place's hour comes as one more sentence`() {
        assertEquals("나미비아. 빈트후크에서 오는 방송. 10,499km 떨어진 곳, 차로 약 10일 22시간. 그곳은 지금 오후 4:05",
            SpokenJourney.text("나미비아", "빈트후크에서 오는 방송", "10,499km 떨어진 곳 · 차로 약 10일 22시간", "그곳은 지금 오후 4:05"))
    }

    @Test fun `on the road, a crossing or an arrival is said first`() {
        assertEquals("독일에 들어왔어요. 독일. 베를린에서 오는 방송. 612km 떨어진 곳",
            SpokenJourney.text("독일", "베를린에서 오는 방송", "612km 떨어진 곳", intro = "독일에 들어왔어요"))
    }

    @Test fun `missing parts are left out, not spoken as blanks`() {
        assertEquals("Namibia", SpokenJourney.text("Namibia", null, ""))
        assertEquals("Japan. Broadcasting from Tokyo", SpokenJourney.text("Japan", "Broadcasting from Tokyo", null))
    }

    @Test fun `phrases that already end with a full stop are not doubled`() {
        assertEquals("Korea. A station right around here", SpokenJourney.text("Korea.", null, "A station right around here."))
    }
}
