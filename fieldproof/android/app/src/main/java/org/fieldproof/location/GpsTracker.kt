package org.fieldproof.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.fieldproof.geo.GpsFix

/**
 * Location fixes at 1 Hz for the boundary walk and photo stamps.
 *
 * Uses Google's fused location at high accuracy, the same source Google Maps
 * uses: outdoors it is driven by the GNSS chip (with assistance data, so the
 * first fix comes in seconds), indoors it falls back to Wi-Fi/cell positions,
 * which carry a large reported accuracy and are therefore refused by the
 * recorder's ±5 m start gate and ±10 m walking gate. On phones without Google
 * Play services it listens to the raw GPS chip instead.
 */
class GpsTracker(private val ctx: Context) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _fixes = MutableSharedFlow<GpsFix>(replay = 1, extraBufferCapacity = 64)
    val fixes: SharedFlow<GpsFix> = _fixes
    val lastFix = MutableStateFlow<GpsFix?>(null)
    val lastAnyLocation = MutableStateFlow<Location?>(null)
    val satellitesUsed = MutableStateFlow(0)
    val gpsEnabled = MutableStateFlow(true)
    private val _users = MutableStateFlow(0)
    val active: StateFlow<Int> = _users

    private val fused: FusedLocationProviderClient? =
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS)
            LocationServices.getFusedLocationProviderClient(ctx) else null

    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun deliver(l: Location) {
        gpsEnabled.value = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val fix = l.toFix()
        lastFix.value = fix
        lastAnyLocation.value = l
        _fixes.tryEmit(fix)
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { deliver(it) }
        }
        override fun onLocationAvailability(a: LocationAvailability) {
            gpsEnabled.value = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) = deliver(l)
        override fun onProviderEnabled(provider: String) { gpsEnabled.value = true }
        override fun onProviderDisabled(provider: String) { gpsEnabled.value = false }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var n = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) n++
            satellitesUsed.value = n
        }
    }

    @SuppressLint("MissingPermission")
    fun acquire() {
        if (!hasPermission()) return
        if (_users.value++ > 0) return
        gpsEnabled.value = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val f = fused
        if (f != null) {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .setMaxUpdateDelayMillis(0L)
                .setWaitForAccurateLocation(false)
                .build()
            f.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
            // Seed the map centre (display only; a stale fix is never recorded).
            f.lastLocation.addOnSuccessListener { l -> if (l != null && lastAnyLocation.value == null) lastAnyLocation.value = l }
        } else {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }
                .getOrNull()?.let { if (lastAnyLocation.value == null) lastAnyLocation.value = it }
        }
        runCatching { lm.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper())) }
    }

    fun release() {
        if (_users.value == 0) return
        if (--_users.value > 0) return
        fused?.removeLocationUpdates(fusedCallback)
        lm.removeUpdates(gpsListener)
        runCatching { lm.unregisterGnssStatusCallback(gnssCallback) }
        satellitesUsed.value = 0
    }

    /** Best recent location for stamping a photo, or null. */
    fun photoLocation(): Location? {
        val l = lastAnyLocation.value ?: return null
        val age = SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos
        return if (age < 5 * 60_000_000_000L) l else null
    }
}

fun Location.toFix() = GpsFix(
    lat = latitude,
    lng = longitude,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else 99.0,
    timeMs = time,
    elapsedMs = elapsedRealtimeNanos / 1_000_000,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
    isMock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider,
    bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null,
)
