package com.voyadio.automotive.camping

/** 시각 → 시간대. 서버(cloudflare/moods.js `slotOf`)와 같은 경계: 아침 5–9 · 낮 10–17 · 저녁 18–21 · 밤 22–4. */
enum class TimeSlot {
    MORNING, DAY, EVENING, NIGHT;

    companion object {
        fun of(hour: Int): TimeSlot {
            require(hour in 0..23) { "hour must be 0-23" }
            return when (hour) {
                in 5..9 -> MORNING
                in 10..17 -> DAY
                in 18..21 -> EVENING
                else -> NIGHT
            }
        }
    }
}
