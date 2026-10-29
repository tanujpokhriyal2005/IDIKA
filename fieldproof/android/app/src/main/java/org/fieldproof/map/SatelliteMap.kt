package org.fieldproof.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.fieldproof.BuildConfig
import org.fieldproof.geo.Geo
import org.fieldproof.geo.LatLng
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import kotlin.math.max

/** Esri World Imagery satellite tiles (z/y/x order). */
object EsriWorldImagery : OnlineTileSourceBase(
    "EsriWorldImagery", 1, 19, 256, ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Imagery © Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(index: Long): String =
        baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index)
}

fun initMaps(ctx: Context) {
    Configuration.getInstance().apply {
        userAgentValue = BuildConfig.APPLICATION_ID
        osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
    }
}

data class MapContent(
    val trail: List<LatLng> = emptyList(),
    val polygon: List<LatLng>? = null,
    val start: LatLng? = null,
    val current: LatLng? = null,
    val accuracyM: Double? = null,
    val startLabel: String = "Start",
    val showSeam: Boolean = false,
    /** The land checked at onboarding, drawn as an amber dotted outline. */
    val refRings: List<List<LatLng>> = emptyList(),
    /** Where the surveyor is going (field entrance or photo spot). */
    val target: LatLng? = null,
    /** Dashed straight line from the current position to this point. */
    val guideTo: LatLng? = null,
    /** Compass heading of the phone in degrees from north, drawn as a cone. */
    val heading: Float? = null,
    /** FIT_ALL: extra room below the content, as a share of its height (for a bottom sheet). */
    val fitBottomPad: Double = 0.0,
)

enum class MapFit { FOLLOW_CURRENT, FIT_TRAIL, FIT_POLYGON, FIT_ALL }

/** Draws the walk on top of the satellite map. Points are display-only: nothing on the map can add or move them. */
private class WalkOverlay(private val density: Float) : Overlay() {
    var content = MapContent()
    private fun dp(v: Float) = v * density
    private val p = Point()
    private val trailOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val trailInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFD9381E.toInt(); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val polyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x7A2E7D32 }
    private val polyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F7A3A.toInt(); typeface = Typeface.DEFAULT_BOLD }
    private val refStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFCD34D.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val refFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x2EFCD34D }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val cone = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x3D1546E0 }

    private fun path(mv: MapView, pts: List<LatLng>, close: Boolean): Path {
        val path = Path()
        pts.forEachIndexed { i, ll ->
            mv.projection.toPixels(GeoPoint(ll.lat, ll.lng), p)
            if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        if (close) path.close()
        return path
    }

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        val k = content
        k.refRings.filter { it.size >= 3 }.forEach {
            val path = path(mv, it, true)
            c.drawPath(path, refFill)
            refStroke.strokeWidth = dp(3f)
            refStroke.pathEffect = DashPathEffect(floatArrayOf(dp(1.5f), dp(5f)), 0f)
            c.drawPath(path, refStroke)
        }
        val guideTo = k.guideTo
        if (guideTo != null && k.current != null) {
            guide.strokeWidth = dp(2.6f)
            guide.pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(6f)), 0f)
            c.drawPath(path(mv, listOf(k.current, guideTo), false), guide)
        }
        k.polygon?.takeIf { it.size >= 3 }?.let {
            val path = path(mv, it, true)
            c.drawPath(path, polyFill)
            polyStroke.strokeWidth = dp(3f)
            c.drawPath(path, polyStroke)
        }
        val trail = k.trail + listOfNotNull(k.current).takeIf { k.trail.isNotEmpty() }.orEmpty()
        if (trail.size >= 2) {
            val path = path(mv, trail, false)
            trailOuter.strokeWidth = dp(6.5f)
            trailInner.strokeWidth = dp(3.2f)
            c.drawPath(path, trailOuter)
            c.drawPath(path, trailInner)
        }
        val start = k.start
        val cur = k.current
        if (k.showSeam && start != null && cur != null) {
            seam.strokeWidth = dp(2f)
            seam.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
            c.drawPath(path(mv, listOf(cur, start), false), seam)
        }
        k.target?.let { t ->
            mv.projection.toPixels(GeoPoint(t.lat, t.lng), p)
            fill.color = 0xFFFFFFFF.toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), dp(9f), fill)
            fill.color = 0xFFC9411B.toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), dp(5.5f), fill)
        }
        val heading = k.heading
        if (cur != null && heading != null) {
            mv.projection.toPixels(GeoPoint(cur.lat, cur.lng), p)
            val r = dp(40f)
            // Canvas angles start at east and turn clockwise; headings start at north.
            c.drawArc(RectF(p.x - r, p.y - r, p.x + r, p.y + r), heading - 90f - 30f, 60f, true, cone)
        }
        if (cur != null) {
            mv.projection.toPixels(GeoPoint(cur.lat, cur.lng), p)
            val acc = k.accuracyM
            if (acc != null) {
                val r = max(mv.projection.metersToPixels(acc.toFloat()), dp(10f))
                fill.color = 0x5C3A5068
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, fill)
            }
            fill.color = 0xFFFFFFFF.toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), dp(8.5f), fill)
            fill.color = 0xFF1546E0.toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), dp(6f), fill)
        }
        if (start != null) {
            mv.projection.toPixels(GeoPoint(start.lat, start.lng), p)
            fill.color = 0xFFFFFFFF.toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), dp(10.5f), fill)
            fill.color = 0xFF1F7A3A.toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), dp(7.5f), fill)
            if (k.startLabel.isNotEmpty()) {
                label.textSize = dp(11.5f)
                val tw = label.measureText(k.startLabel)
                val x = p.x + dp(16f)
                val r = RectF(x, p.y - dp(10f), x + tw + dp(14f), p.y + dp(10f))
                fill.color = 0xFFFFFFFF.toInt()
                c.drawRoundRect(r, dp(5f), dp(5f), fill)
                c.drawText(k.startLabel, x + dp(7f), p.y + dp(4f), label)
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun SatelliteMap(
    content: MapContent,
    fit: MapFit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    fallbackCenter: LatLng? = null,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val density = ctx.resources.displayMetrics.density
    val overlay = remember { WalkOverlay(density) }
    val holder = remember { arrayOfNulls<MapView>(1) }
    val state = remember { object { var userMovedAt = 0L; var fitted = false } }

    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            holder[0]?.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            MapView(c).apply {
                setTileSource(EsriWorldImagery)
                setMultiTouchControls(interactive)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                minZoomLevel = 4.0
                maxZoomLevel = 21.0
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                setBackgroundColor(0xFF6E8A4E.toInt())
                overlays.add(overlay)
                controller.setZoom(18.5)
                (content.current ?: fallbackCenter)?.let { controller.setCenter(GeoPoint(it.lat, it.lng)) }
                setOnTouchListener { _, e ->
                    if (!interactive) return@setOnTouchListener true
                    if (e.action == MotionEvent.ACTION_MOVE) state.userMovedAt = System.currentTimeMillis()
                    false
                }
                holder[0] = this
                onResume()
            }
        },
        update = { mv ->
            overlay.content = content
            val userRecently = System.currentTimeMillis() - state.userMovedAt < 8000
            when (fit) {
                MapFit.FOLLOW_CURRENT -> if (!userRecently) (content.current ?: fallbackCenter)?.let { mv.controller.setCenter(GeoPoint(it.lat, it.lng)) }
                MapFit.FIT_TRAIL -> if (!userRecently) {
                    val pts = content.trail + listOfNotNull(content.current, content.start)
                    fitTo(mv, pts, 17.0, 19.5, if (pts.size < 3) 18.8 else null)
                }
                MapFit.FIT_POLYGON -> if (!state.fitted) {
                    val pts = content.polygon.orEmpty() + content.refRings.flatten()
                    if (pts.isNotEmpty() && fitTo(mv, pts, 13.0, 20.0, null)) state.fitted = true
                }
                MapFit.FIT_ALL -> if (!userRecently) {
                    var pts = content.polygon.orEmpty() + content.refRings.flatten() + listOfNotNull(content.current, content.target)
                    if (content.fitBottomPad > 0 && pts.size >= 2) {
                        val n = pts.maxOf { it.lat }; val s = pts.minOf { it.lat }
                        pts = pts + LatLng(s - (n - s).coerceAtLeast(0.0003) * content.fitBottomPad, pts.first().lng)
                    }
                    fitTo(mv, pts, 12.0, 19.5, null)
                }
            }
            mv.invalidate()
        },
    )
}

/** Fit the view to points. Returns false if the view isn't laid out yet. */
private fun fitTo(mv: MapView, pts: List<LatLng>, minZ: Double, maxZ: Double, fixedZoom: Double?): Boolean {
    if (pts.isEmpty()) return true
    if (fixedZoom != null || pts.size == 1) {
        val c = Geo.centroid(pts)
        mv.controller.setZoom(fixedZoom ?: 18.8)
        mv.controller.setCenter(GeoPoint(c.lat, c.lng))
        return true
    }
    if (mv.width == 0 || mv.height == 0) {
        mv.post { fitTo(mv, pts, minZ, maxZ, fixedZoom) }
        return false
    }
    val n = pts.maxOf { it.lat }; val s = pts.minOf { it.lat }
    val e = pts.maxOf { it.lng }; val w = pts.minOf { it.lng }
    val padLat = (n - s) * 0.18 + 0.00005
    val padLng = (e - w) * 0.18 + 0.00005
    val box = BoundingBox(n + padLat, e + padLng, s - padLat, w - padLng)
    val z = mv.getZoomLevel(false)
    val target = (mv.tileProvider.tileSource.let { _ -> zoomFor(mv, box) }).coerceIn(minZ, maxZ)
    if (kotlin.math.abs(target - z) > 0.25) mv.controller.setZoom(target)
    mv.controller.setCenter(box.centerWithDateLine)
    return true
}

private fun zoomFor(mv: MapView, box: BoundingBox): Double {
    // Zoom at which the box fits the view (Web Mercator, 256 px tiles scaled to dpi).
    val tile = 256.0 * mv.context.resources.displayMetrics.density
    val latRad = { lat: Double -> Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) }
    val fracLat = (latRad(box.latNorth) - latRad(box.latSouth)) / (2 * Math.PI)
    val fracLng = (box.lonEast - box.lonWest) / 360.0
    val zLat = Math.log(mv.height / tile / fracLat) / Math.log(2.0)
    val zLng = Math.log(mv.width / tile / fracLng) / Math.log(2.0)
    return minOf(zLat, zLng)
}
