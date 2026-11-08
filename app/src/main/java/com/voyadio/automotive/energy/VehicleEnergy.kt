package com.voyadio.automotive.energy

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * The car's battery level, capacity and charge-port state (Android Automotive standard properties).
 * Reports null when the car can't share them. Values stay on the device.
 */
class VehicleEnergy(context: Context, private val changed: (EnergyReading?) -> Unit) {
    // applicationContext: an Activity must not leak through the car service connection
    private val context: Context = context.applicationContext ?: context
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var connection = 0 // bumped on every start()/stop() so late callbacks of an old connection are dropped
    private var capacityWh: Double? = null
    private var level: Double? = null
    private var charging = false
    private var lastReported: Double? = null
    private var lastReportedCharging = false
    private var lastReportedAtMs = 0L

    // EV_BATTERY_LEVEL is a continuous property: events may arrive about once a second even when nothing changed.
    // Lazy: a tablet or phone has no android.car classes — building this at construction crashed the app there (2026-10-15).
    private val callback by lazy { object : CarPropertyManager.CarPropertyEventCallback {
        @Suppress("DEPRECATION") // CarPropertyValue.status: still the way to tell AVAILABLE from UNAVAILABLE/ERROR on API 29+
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.EV_BATTERY_LEVEL -> {
                    if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                    val wh = (value.value as? Float)?.toDouble() ?: return
                    if (!wh.isFinite() || wh < 0.0) return
                    level = wh
                }
                VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED -> {
                    if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                    charging = value.value as? Boolean ?: return
                }
                else -> return
            }
            reportIfChanged()
        }

        // A transient error must not look like "unsupported": ignore it.
        override fun onErrorEvent(propertyId: Int, zone: Int) {}
    } }

    private fun reportIfChanged() {
        val wh = level ?: return
        val capacity = capacityWh ?: return
        val now = System.currentTimeMillis()
        val previous = lastReported
        // Also report every few minutes without a change, so the camping screen's battery line never looks frozen.
        val stale = now - lastReportedAtMs >= MIN_REPORT_INTERVAL_MS
        if (previous != null && abs(wh - previous) < MIN_CHANGE_WH && charging == lastReportedCharging && !stale) return
        lastReported = wh
        lastReportedCharging = charging
        lastReportedAtMs = now
        changed(EnergyReading(wh, capacity, charging, now))
    }

    @Suppress("DEPRECATION") // registerCallback: still supported; subscribePropertyEvents needs API 35
    fun start() {
        stop()
        if (!supported(context) || !granted(context)) { changed(null); return }
        val token = ++connection
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { vehicle, ready ->
                if (token != connection) return@createCar
                runCatching { properties?.unregisterCallback(callback) }
                properties = null
                if (!ready) { changed(null); return@createCar }
                try {
                    val manager = vehicle.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    val capacity = manager.getFloatProperty(VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, 0).toDouble()
                    if (!capacity.isFinite() || capacity <= 0.0) { changed(null); return@createCar }
                    capacityWh = capacity
                    properties = manager
                    manager.registerCallback(callback, VehiclePropertyIds.EV_BATTERY_LEVEL, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                    runCatching { manager.registerCallback(callback, VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                    val first = read()
                    if (first == null) { changed(null) } else {
                        level = first.levelWh; charging = first.charging
                        lastReported = null
                        reportIfChanged()
                    }
                } catch (_: Exception) { changed(null) }
            }
        } catch (_: Exception) { changed(null) }
    }

    /** A fresh reading straight from the car (uses the capacity read at connect time); null if unavailable. */
    fun read(): EnergyReading? {
        val manager = properties ?: return null
        val capacity = capacityWh ?: return null
        return try {
            val wh = manager.getFloatProperty(VehiclePropertyIds.EV_BATTERY_LEVEL, 0).toDouble()
            val plugged = runCatching { manager.getBooleanProperty(VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED, 0) }.getOrDefault(false)
            if (!wh.isFinite() || wh < 0.0) null else EnergyReading(wh, capacity, plugged, System.currentTimeMillis())
        } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION") // unregisterCallback: paired with registerCallback above
    fun stop() {
        connection++
        runCatching { properties?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        properties = null; car = null
        capacityWh = null; level = null; charging = false; lastReported = null; lastReportedCharging = false
        lastReportedAtMs = 0L
    }

    companion object {
        const val MIN_CHANGE_WH = 10.0
        const val MIN_REPORT_INTERVAL_MS = 5 * 60_000L
        const val PERMISSION = "android.car.permission.CAR_ENERGY"
        fun supported(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        fun granted(context: Context) = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}
