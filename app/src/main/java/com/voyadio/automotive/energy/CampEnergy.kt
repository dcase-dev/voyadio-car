package com.voyadio.automotive.energy

/**
 * Whole battery percent, rounded down and clamped to 0..100 (0 without a capacity).
 * 캠핑 화면의 배터리 한 줄과 20% 자동 보호가 같은 규칙을 쓴다.
 */
fun batteryPercent(wh: Double, capacityWh: Double) = if (capacityWh > 0) (wh / capacityWh * 100).toInt().coerceIn(0, 100) else 0

data class EnergyReading(val levelWh: Double, val capacityWh: Double, val charging: Boolean, val atMs: Long)
