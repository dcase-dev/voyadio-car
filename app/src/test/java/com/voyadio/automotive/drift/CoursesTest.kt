package com.voyadio.automotive.drift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoursesTest {
    @Test fun `every course id round-trips through storage`() {
        for (course in Courses.CONTENT + Courses.LANGUAGE + Course.Any) assertEquals(course, Courses.byId(course.id))
        assertEquals(Courses.country("JP"), Courses.byId("country_jp"))
        assertEquals("JP", Courses.countryOf(Courses.country("jp")))
        assertNull(Courses.byId("not_a_course"))
        assertNull(Courses.byId(null))
    }

    @Test fun `the trip course holds its countries in its id, and only the car has it`() {
        val trip = Courses.trip("FR", "de")
        assertEquals(listOf("FR", "DE"), trip.codes)
        assertEquals(trip, Courses.byId(trip.id))
        assertTrue(Courses.isTrip(trip))
        assertFalse(Courses.isTrip(Courses.country("DE")))
        assertFalse(Courses.shared(trip))
        assertEquals("one country when the way stays in it", listOf("KR"), Courses.trip("KR", "KR").codes)
        assertNull("no countries, no course", Courses.byId("trip_"))
    }

    @Test fun `phone and car share course ids but not single-country courses`() {
        assertTrue(Courses.shared(Courses.CONTENT.first()))
        assertTrue(Courses.shared(Course.Any))
        assertFalse(Courses.shared(Courses.country("KR")))
    }

    @Test fun `the same course ids as the phone app`() {
        // 폰 정의(lib/models/explore_direction.dart)의 id와 한 글자도 다르면 계정 항로가 폰↔차로 안 이어진다.
        val phone = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "lib/models/explore_direction.dart") }.firstOrNull { it.exists() } ?: return
        val ids = Regex("id: '([a-z0-9_]+)'").findAll(phone.readText()).map { it.groupValues[1] }.toSet()
        assertEquals(ids, (Courses.CONTENT + Courses.LANGUAGE + Course.Any).map { it.id }.toSet())
    }
}
