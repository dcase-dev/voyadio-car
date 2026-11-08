package com.voyadio.automotive.pleos

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only (2026-10-21): pokes the car's navigation (Pleos NaviHelper) and gear (Pleos Vehicle) on the
 * Pleos emulator and logs what comes back — the facts the trip course and the driving-aware voice guide rest on.
 *   adb shell am broadcast --user 10 -p com.voyadio.automotive.dev -a com.voyadio.automotive.debug.CAR_PROBE \
 *     --es do route --es lat 35.1796 --es lon 129.0756 --es name 부산역      (also: dest · where · state · cancel · gear)
 */
class CarProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (val what = intent.getStringExtra("do")) {
            "gear" -> vehicle(app).getGear().getCurrentGearSelectionState({ Log.i(TAG, "gear now: $it") }, { Log.w(TAG, "gear failed", it) })
            "route" -> navi(app) {
                val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: return@navi
                val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: return@navi
                it.requestRoute(RouteInfo(latitude = lat, longitude = lon, displayLatitude = null, displayLongitude = null,
                    poiName = intent.getStringExtra("name").orEmpty(), poiId = "", address = "", poiSubId = ""))
                Log.i(TAG, "route requested: $lat,$lon")
            }
            "dest" -> navi(app) { it.getDestinationInfo() }
            "where" -> navi(app) { it.getCurrentLocationInfo() }
            "state" -> navi(app) { it.getRouteStateInfo() }
            "cancel" -> navi(app) { it.cancelRoute() }
            else -> Log.w(TAG, "unknown: $what")
        }
    }

    private fun navi(context: Context, then: (NaviHelper) -> Unit) {
        helper?.let { return runCatching { then(it) }.onFailure { e -> Log.w(TAG, "navi call failed", e) }.let { } }
        val created = NaviHelper(context)
        created.initialize({
            Log.i(TAG, "navi ready")
            created.addListener(Events)
            helper = created
            runCatching { then(created) }.onFailure { Log.w(TAG, "navi call failed", it) }
        }, { Log.w(TAG, "navi initialize failed", it) })
    }

    private fun vehicle(context: Context): Vehicle = car ?: Vehicle(context).also { v ->
        car = v
        runCatching { v.initialize() }.onFailure { Log.w(TAG, "vehicle initialize failed", it) }
        runCatching {
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) { Log.i(TAG, "gear changed: $gear") }
                override fun onFailed(exception: Exception) { Log.w(TAG, "gear listener failed", exception) }
            })
        }.onFailure { Log.w(TAG, "gear register failed", it) }
    }

    private object Events : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { Log.i(TAG, "destination: $destinationInfo") }
        override fun onDestinationChanged() { Log.i(TAG, "destination changed") }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { Log.i(TAG, "arrived: $destinationArrivedInfo") }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) { Log.i(TAG, "here: $currentLocationInfo") }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { Log.i(TAG, "route started: $routeStartInfo") }
        override fun onRoutingCompleted() { Log.i(TAG, "routing completed") }
        override fun onRouteEnded() { Log.i(TAG, "route ended") }
        override fun onRouteCancelled() { Log.i(TAG, "route cancelled") }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { Log.i(TAG, "route state: $routeStateInfo") }
        override fun onNavigationStatus(navigationStatus: NavigationStatus) { Log.i(TAG, "navigation: $navigationStatus") }
        override fun onTripInfo(tripInfo: TripInfo) { Log.i(TAG, "trip: $tripInfo") }
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "navi error: $errorCode") }
    }

    companion object {
        private const val TAG = "CarProbe"
        private var helper: NaviHelper? = null
        private var car: Vehicle? = null
    }
}
