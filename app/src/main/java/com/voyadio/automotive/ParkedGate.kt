package com.voyadio.automotive

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager

/** Unknown/disconnected vehicle state is restricted; never infer parking from speed alone. */
class ParkedGate(private val context: Context, private val changed: (Boolean) -> Unit) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private val access = ParkedAccess(changed)
    fun start() {
        stop()
        val session = access.begin()
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            val source = access.observe(session) ?: return
            access.report(session, source, !BuildConfig.DEBUG) // development emulator only
            return
        }
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { vehicle, ready ->
                val source = access.observe(session) ?: return@createCar
                runCatching { manager?.unregisterListener() }
                manager = null
                if (!ready) return@createCar
                try {
                    manager = vehicle.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    manager?.registerListener { access.report(session, source, it.isRequiresDistractionOptimization) }
                    access.report(session, source, manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization)
                } catch (_: Exception) {
                    access.observe(session) // invalidate any listener registered before the failure
                }
            }
        } catch (_: Exception) { access.observe(session) }
    }
    fun stop() {
        access.end()
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null; car = null
    }
}
