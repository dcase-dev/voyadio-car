package com.voyadio.automotive.camping

/**
 * 캠핑 화면의 배터리 자동 보호(설계 2026-10-13 §2). 잔량이 [floor]% 이하인데 라디오가 나오면 **한 번** 끈다.
 * 한 번 끈 뒤 사용자가 다시 틀면 그 캠핑 화면에서는 다시 끄지 않는다(사용자 선택 존중).
 * 충전 중이면 배터리가 줄지 않으니 끄지 않는다. 바닥값은 고정이고 설정은 없다.
 */
class BatteryGuard(val floor: Int = FLOOR) {
    private var tripped = false

    /** true = 지금 라디오를 끄고 안내를 띄운다. */
    fun shouldStop(percent: Int, playing: Boolean, charging: Boolean): Boolean {
        if (tripped || !playing || charging || percent > floor) return false
        tripped = true
        return true
    }

    /** 캠핑 화면에 새로 들어올 때 다시 무장한다. */
    fun reset() { tripped = false }

    companion object {
        const val FLOOR = 20
    }
}
