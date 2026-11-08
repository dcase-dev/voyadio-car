package com.voyadio.automotive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.voyadio.automotive.geo.LatLon

/** Last known coarse location. Stays on the device; never sent to the server. */
object CarLocation {
    fun granted(context: Context) = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    fun current(context: Context): LatLon? {
        if (!granted(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val latest = manager.getProviders(true)
            .mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }
            .maxByOrNull { it.time } ?: return null
        return LatLon(latest.latitude, latest.longitude)
    }

    /**
     * Asks for one fresh coarse fix when no last-known location exists. Returns false if the
     * request could not be made (no permission, no enabled provider). [onDone] runs on the main
     * thread with the fix, or null if none arrived (timeout, ~30 s on every API level).
     * Cancelling [signal] drops the request without calling [onDone].
     */
    fun requestOnce(context: Context, signal: CancellationSignal, onDone: (LatLon?) -> Unit): Boolean {
        if (!granted(context)) return false
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        val provider = pickProvider(manager) ?: return false
        try {
            LocationManagerCompat.getCurrentLocation(
                manager, provider, signal, ContextCompat.getMainExecutor(context),
            ) { location -> onDone(location?.let { LatLon(it.latitude, it.longitude) }) }
        } catch (_: SecurityException) {
            return false
        }
        return true
    }

    private fun pickProvider(manager: LocationManager): String? {
        val enabled = manager.getProviders(true)
        val order = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return order.firstOrNull { it in enabled }
    }
}
