package com.voyadio.automotive.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.voyadio.automotive.geo.LatLon
import com.voyadio.automotive.trip.Destination
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The navigation's destination and arrival for the road's radio (Pleos NaviHelper 3.0.5, 2026-10-21; permission
 * pleos.car.permission.NAVI_ROUTE). Checked on the Pleos emulator: no destination comes as (0, 0) with no name;
 * setting one starts a route (ROUTE_DRIVE) whose destination info carries the place; cancelling goes back to
 * FREE_DRIVE and the empty destination.
 */
internal class PleosNavi(context: Context) {
    val destination = MutableStateFlow<Destination?>(null)
    val arrivals = MutableSharedFlow<Destination>(extraBufferCapacity = 1)
    /** The last destination: the arrival may come after the navigation has already let it go. */
    @Volatile private var last: Destination? = null
    private val navi = NaviHelper(context)

    private val events = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { set(destinationInfo.place()) }
        override fun onDestinationChanged() = ask()
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = ask()
        override fun onRouteCancelled() = set(null)
        override fun onNavigationStatus(navigationStatus: NavigationStatus) {
            when (navigationStatus) {
                NavigationStatus.ROUTE_DRIVE -> ask()
                NavigationStatus.FREE_DRIVE, NavigationStatus.INACTIVE -> set(null)
                else -> Unit // INITIALIZING (a route being found) keeps what there was
            }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            (destination.value ?: last)?.let { arrivals.tryEmit(it) }
        }
        override fun onError(errorCode: NaviErrorCode) { Log.i(TAG, "navigation: $errorCode") }
    }

    init {
        navi.initialize({ navi.addListener(events); ask() }, { Log.i(TAG, "NaviHelper unavailable: ${it.message}") })
    }

    fun release() { runCatching { navi.removeListener(events); navi.release() } }

    private fun ask() { runCatching { navi.getDestinationInfo() }.onFailure { Log.i(TAG, "destination: ${it.message}") } }

    private fun set(place: Destination?) {
        if (place != null) last = place
        destination.value = place
    }

    /** (0, 0) with no name is "no destination". */
    private fun DestinationInfo.place(): Destination? =
        if (latitude == 0.0 && longitude == 0.0) null else Destination(name.ifBlank { address }, LatLon(latitude, longitude))

    private companion object { const val TAG = "PleosNavi" }
}
