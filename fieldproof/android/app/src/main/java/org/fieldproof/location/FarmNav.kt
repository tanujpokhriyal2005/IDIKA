package org.fieldproof.location

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.fieldproof.data.Farmer
import org.fieldproof.geo.Geo
import org.fieldproof.geo.LatLng
import java.util.Locale

/**
 * Where to send the surveyor for a farmer. With a walked boundary: where the
 * walk started (usually the field entrance) and the outline. Without one: where
 * the land record or water photo was taken.
 */
data class FarmTarget(
    val landId: String?,
    val label: String,          // "Gat 45/2", or "" for a single unnamed parcel
    val point: LatLng,
    val ring: List<LatLng>?,    // null when only a photo location is known
    val areaM2: Double = 0.0,
)

fun farmTargets(f: Farmer): List<FarmTarget> {
    val walked = f.boundaries.filter { it.points.size >= 3 }.mapIndexed { i, b ->
        val land = f.lands.find { it.id == b.landId }
        val ring = b.points.map { LatLng(it.lat, it.lng) }
        FarmTarget(b.landId, land?.let { "Gat ${it.surveyNo}" } ?: if (f.boundaries.size > 1) "#${i + 1}" else "", ring.first(), ring, b.areaM2)
    }
    if (walked.isNotEmpty()) return walked
    val photo = listOf("land_record", "water_source").firstNotNullOfOrNull { k -> f.photos.lastOrNull { it.kind == k && it.lat != null && it.lng != null } }
    return listOfNotNull(photo?.let { FarmTarget(it.landId, "", LatLng(it.lat!!, it.lng!!), null) })
}

/** Google Maps turn-by-turn to [p]; the browser version when the app isn't installed. */
fun openGoogleMaps(ctx: Context, p: LatLng) {
    val q = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lng)
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q&mode=d")).setPackage("com.google.android.apps.maps")
    try {
        ctx.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$q&travelmode=driving"))) }
    }
}

fun distanceText(m: Double): String = if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000)

fun straightDistance(a: LatLng, b: LatLng) = Geo.distance(a, b)

/** Compass heading of the phone (degrees from north), or null without a compass. */
@Composable
fun rememberHeading(): Float? {
    val ctx = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val orient = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, orient)
                val deg = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
                val prev = heading
                // Smooth across the 0/360 seam so the arrow doesn't jitter.
                heading = if (prev == null) deg else {
                    var d = deg - prev
                    if (d > 180) d -= 360
                    if (d < -180) d += 360
                    ((prev + d * 0.2f) + 360) % 360
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return heading
}
