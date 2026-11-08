package com.voyadio.automotive.drift

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 메모리 SharedPreferences(문자열만) — JVM 시험용. */
private class MemoryPrefs : SharedPreferences {
    val map = HashMap<String, String?>()
    override fun getString(key: String, defValue: String?) = map[key] ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { map[key] = value }
        override fun apply() {}
        override fun commit() = true
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
    }
    override fun getAll(): MutableMap<String, *> = map
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = defValue
    override fun getLong(key: String?, defValue: Long) = defValue
    override fun getFloat(key: String?, defValue: Float) = defValue
    override fun getBoolean(key: String?, defValue: Boolean) = defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class DriftStateTest {
    @Test fun `the trip course is followed but never remembered, and another course ends it`() {
        val prefs = MemoryPrefs()
        val state = DriftState(prefs)
        state.choose(Courses.country("JP"))
        val trip = Courses.trip("FR", "DE")
        state.choose(trip)
        assertEquals(trip, state.steering)
        assertEquals(Courses.country("JP"), state.course)
        assertEquals("country_jp", prefs.map["course"])
        assertEquals("a restarted app is back on its own course", Courses.country("JP"), DriftState(prefs).steering)
        state.choose(Courses.country("BR"))
        assertEquals(Courses.country("BR"), state.steering)
        assertEquals(null, state.trip)
    }

    @Test fun `the camp screen's mood beats the trip course, the trip course beats the remembered one`() {
        val pool = listOf("FR", "DE", "JP").flatMap { cc -> (1..5).map { com.voyadio.automotive.data.Station("https://r.test/$cc/$it", "R$it", cc, cc) } }
        val state = DriftState(MemoryPrefs())
        state.choose(Courses.country("JP"))
        state.choose(Courses.trip("DE", "DE"))
        assertTrue(state.next(pool, null, 10).all { it.countryCode == "DE" })
        state.along = Courses.country("FR")
        assertTrue(state.next(pool, null, 10).all { it.countryCode == "FR" })
    }

    @Test fun `heard lists what actually played, newest first and once each`() {
        val state = DriftState(MemoryPrefs())
        state.remember("a"); state.remember("b"); state.remember("a")
        assertEquals(listOf("a", "b"), state.heard())
    }

    @Test fun `the chosen course is remembered across restarts`() {
        val prefs = MemoryPrefs()
        DriftState(prefs).choose(Courses.country("JP"))
        assertEquals(Courses.country("JP"), DriftState(prefs).course)
    }

    @Test fun `a course changed on the phone is followed`() {
        val prefs = MemoryPrefs()
        val state = DriftState(prefs)
        assertTrue(state.adoptFromServer("classical")) // 처음 보는 서버 값 + 차는 아직 '전체' → 따른다
        assertEquals("classical", state.course.id)
        state.choose(Courses.country("KR"))           // 차에서 나라 하나로 바꿈
        assertFalse(state.adoptFromServer("classical")) // 서버 값은 그대로 → 차 항로 유지
        assertEquals("country_kr", state.course.id)
        assertTrue(state.adoptFromServer("lang_ja"))   // 폰에서 바꿈 → 따른다
        assertEquals("lang_ja", state.course.id)
    }

    @Test fun `the first server value does not override a course already chosen in the car`() {
        val state = DriftState(MemoryPrefs())
        state.choose(Courses.country("BR"))
        assertFalse(state.adoptFromServer("any"))
        assertEquals("country_br", state.course.id)
    }

    @Test fun `what the car pushed is not mistaken for a phone change`() {
        val state = DriftState(MemoryPrefs())
        state.choose(Courses.CONTENT[3]); state.pushed("classical")
        state.choose(Courses.country("KR"))
        assertFalse(state.adoptFromServer("classical"))
    }
}
