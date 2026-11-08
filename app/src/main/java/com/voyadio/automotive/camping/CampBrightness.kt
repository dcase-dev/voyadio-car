package com.voyadio.automotive.camping

import kotlin.math.abs

/**
 * 캠핑 화면 밝기(2026-10-15 PO 결정): 기본은 밤에 눈부시지 않은 15%, 해 버튼을 누를 때마다 15 → 40 → 70 → 100%.
 * 고른 값은 기억한다. 전에는 15%로 고정이라 기기 밝기 막대가 먹지 않았다.
 */
object CampBrightness {
    val LEVELS = floatArrayOf(0.15f, 0.4f, 0.7f, 1f)
    const val DEFAULT = 0.15f

    /** 다음 단계(마지막 다음은 처음). 목록에 없는 값이면 첫 단계. */
    fun next(current: Float): Float {
        val i = LEVELS.indexOfFirst { abs(it - current) < 0.01f }
        return LEVELS[(i + 1) % LEVELS.size]
    }

    /** 저장된 값이 목록에 없으면(옛 값·손상) 기본값. */
    fun valid(saved: Float): Float = LEVELS.firstOrNull { abs(it - saved) < 0.01f } ?: DEFAULT
}
