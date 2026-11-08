package com.voyadio.automotive.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTest {
    @Test fun `save in the four languages the car hears`() {
        for (words in listOf("이 방송 저장해줘", "이 채널을 저장해줘", "즐겨찾기에 넣어 줘", "save this station", "Enregistre cette station", "guarda esta emisora")) {
            assertTrue(words, Spoken.save(words))
        }
        assertFalse(Spoken.save("일본 라디오 틀어줘"))
        assertFalse(Spoken.save("다른 나라 방송 들려줘"))
    }

    @Test fun `a star is a save`() {
        for (words in listOf("별로 담아줘", "이 방송 별 담기", "add a star", "star this station", "une étoile", "una estrella"))
            assertTrue(words, Spoken.save(words))
        for (words in listOf("별로야 다음", "start the radio", "다음 방송"))
            assertFalse("'별로'(not much) and 'start' are not stars: $words", Spoken.save(words))
    }

    @Test fun `next in the four languages the car hears`() {
        for (words in listOf("다른 나라 방송 들려줘", "다음", "Next", "play another station", "station suivante", "pon otra emisora")) {
            assertTrue(words, Spoken.next(words))
        }
        assertFalse(Spoken.next("일본 라디오 틀어줘"))
        assertFalse(Spoken.next("이 방송 저장해줘"))
    }
}
