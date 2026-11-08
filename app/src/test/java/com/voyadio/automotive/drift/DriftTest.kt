package com.voyadio.automotive.drift

import com.voyadio.automotive.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DriftTest {
    private fun st(n: Int, cc: String, tags: String = "", name: String = "Radio $n") =
        Station("https://radio.example.test/$cc/$n", name, cc, cc, tags = tags)
    private val world = listOf("KR", "JP", "BR", "FR", "KE", "US").flatMap { cc -> (1..10).map { st(it, cc) } }

    @Test fun `with no course the next station comes from another country`() {
        val random = Random(1)
        var current = world.first()
        repeat(200) {
            val next = Drift.pick(world, Course.Any, emptyList(), current, random)!!
            assertNotEquals("같은 나라가 이어지면 안 된다", current.countryCode, next.countryCode)
            current = next
        }
    }

    @Test fun `no course still visits every country, not just a few`() {
        val seen = Drift.queue(world, Course.Any, emptyList(), world.first(), 60, Random(2)).map { it.countryCode }.toSet()
        assertEquals(6, seen.size)
    }

    @Test fun `recently played stations are not repeated`() {
        val q = Drift.queue(world, Course.Any, emptyList(), null, 30, Random(3))
        assertEquals("30개 안에 같은 방송이 다시 나오면 안 된다", 30, q.map { it.id }.toSet().size)
    }

    @Test fun `a country course stays inside its countries`() {
        val course = Course.Countries("country", listOf("KR"))
        val q = Drift.queue(world, course, emptyList(), null, 9, Random(4))
        assertTrue(q.all { it.countryCode == "KR" })
        assertEquals(9, q.map { it.id }.toSet().size)
    }

    @Test fun `a language course moves between its countries`() {
        val course = Course.Countries("lang_x", listOf("KR", "JP"))
        val q = Drift.queue(world, course, emptyList(), null, 20, Random(5))
        assertTrue(q.all { it.countryCode in setOf("KR", "JP") })
        assertEquals(setOf("KR", "JP"), q.map { it.countryCode }.toSet())
    }

    @Test fun `a content course follows its tags with about one surprise in eight`() {
        val pool = world + (1..40).map { st(100 + it, listOf("DE", "IT", "AT", "CH")[it % 4], tags = "classical,orchestra") }
        val course = Course.Content("classical", listOf("classical"))
        val q = Drift.queue(pool, course, emptyList(), null, 400, Random(6).let { it })
        val outside = q.count { !it.tags.contains("classical") }.toDouble() / q.size
        assertTrue("뜻밖의 신호 비율 $outside 이 1/8 근처여야 한다", outside in 0.06..0.20)
    }

    @Test fun `a course with nothing matching widens instead of going silent`() {
        val course = Course.Countries("country", listOf("ZZ"))
        assertTrue(Drift.pick(world, course, emptyList(), null, Random(7)) != null)
        val content = Course.Content("asmr", listOf("asmr"))
        assertTrue(Drift.pick(world, content, emptyList(), null, Random(8)) != null)
    }

    @Test fun `a station course stays in its list and sometimes visits the wider list`() {
        val mood = (1..10).map { st(200 + it, "ES", tags = "bossa nova") }
        val wider = (1..10).map { st(300 + it, "BR", tags = "jazz") }
        val q = Drift.queue(world, Course.Stations("midday_bossa", mood, wider), emptyList(), null, 300, Random(9))
        val inMood = q.count { it in mood }.toDouble() / q.size
        assertTrue("분위기 안 비율 $inMood", inMood in 0.80..0.94)
        assertTrue(q.all { it in mood || it in wider })
    }

    @Test fun `an empty pool gives nothing`() {
        assertNull(Drift.pick(emptyList(), Course.Any, emptyList(), null, Random(10)))
        assertTrue(Drift.queue(emptyList(), Course.Any, emptyList(), null, 5, Random(11)).isEmpty())
    }

    @Test fun `a spoken course is checked against the pool before the radio steers`() {
        assertTrue(Drift.reaches(world, Courses.country("JP")))
        assertFalse("방송 없는 나라는 '없다'고 답해야 한다 — 세계 아무 데로 새면 안 된다", Drift.reaches(world, Courses.country("IS")))
        assertTrue(Drift.reaches(world, Courses.LANGUAGE.first { it.id == "lang_ja" }))
        val classical = Course.Content("classical", listOf("classical"))
        assertFalse(Drift.reaches(world, classical))
        assertTrue(Drift.reaches(world + st(99, "AT", tags = "classical,opera"), classical))
        assertTrue(Drift.reaches(world, Course.Any))
        assertFalse(Drift.reaches(emptyList(), Course.Any))
    }
}
