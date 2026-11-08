package com.voyadio.automotive.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Moving or parked, from the gear (Pleos Vehicle SDK, 2026-10-21; permission pleos.car.permission.CAR_DRIVE):
 * P is parked; D, R and N are moving. Checked on the Pleos emulator — Virtual Console's gear switch arrives as
 * GEAR_DRIVE / GEAR_PARK at once.
 */
internal class PleosGear(context: Context) {
    private val state = MutableStateFlow<Boolean?>(null)
    /** Followed as it changes (today's voyage starts when the car leaves P, 2026-10-22). */
    val driving: StateFlow<Boolean?> = state
    private val vehicle = Vehicle(context)
    private val changes = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { state.value = moving(gear) }
        override fun onFailed(exception: Exception) { Log.i(TAG, "gear: ${exception.message}") }
    }

    init {
        vehicle.initialize()
        val gear = vehicle.getGear()
        gear.getCurrentGearSelectionState({ state.value = moving(it) }, { Log.i(TAG, "gear unknown: ${it.message}") })
        gear.registerGearSelection(changes)
    }

    fun release() { runCatching { vehicle.getGear().unregisterGearSelection(changes); vehicle.release() } }

    private fun moving(gear: VehicleGear?): Boolean? = when (gear) {
        null, VehicleGear.GEAR_UNKNOWN -> null
        VehicleGear.GEAR_PARK -> false
        else -> true
    }

    private companion object { const val TAG = "PleosGear" }
}
