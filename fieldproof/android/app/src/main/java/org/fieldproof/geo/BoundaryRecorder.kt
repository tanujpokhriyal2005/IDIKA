package org.fieldproof.geo

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Developer rules for boundary capture (spec page "For the developer"). */
object BoundaryRules {
    const val START_ACCURACY_M = 5.0      // walking can start only at ±5 m or better
    const val WALK_MAX_ACCURACY_M = 10.0  // fixes worse than this are ignored while walking
    const val POINT_SPACING_M = 2.0       // a point every 2 m walked
    const val MAX_INTERPOLATE_M = 6.0     // fill 2 m points only between fixes this close
    const val FINISH_RADIUS_M = 10.0      // finish unlocks within 10 m of start
    const val MAX_SPEED_KMH = 8.0         // above this it's a vehicle: pause
    const val MIN_POINTS = 12             // minimum vertices
    const val MIN_EXCURSION_M = 15.0      // must have walked at least this far from the start
    const val RESUME_RADIUS_M = 10.0      // resume only near where recording stopped
    const val ANCHOR_FIXES = 3            // start corner = weighted mean of the first fixes
    const val STALE_FIX_MS = 5_000L
    const val SPEED_CONFIRM_FIXES = 2     // consecutive fast fixes before pausing
    const val SEAM_SEARCH_M = 30.0        // overshoot/undershoot trimmed within this distance of start
}

/** One GPS fix, platform-independent. */
data class GpsFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val timeMs: Long,           // wall clock (UTC ms) for the record
    val elapsedMs: Long,        // monotonic clock for time deltas
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val speedAccuracyMps: Double? = null,
    val isMock: Boolean = false,
    val bearingAccuracyDeg: Double? = null,
)

@Serializable
data class BoundaryPoint(val lat: Double, val lng: Double, val acc: Double, val t: Long)

enum class WalkPhase { WAITING_GPS, READY, ANCHORING, RECORDING, PAUSED, FINISHED }

enum class PauseReason { NONE, USER, TOO_FAST, RESTORED }

enum class WalkWarning { NONE, GPS_WEAK, GPS_LOST, TOO_FAST, MOCK_LOCATION, WALK_BACK }

data class WalkState(
    val phase: WalkPhase = WalkPhase.WAITING_GPS,
    val accuracyM: Double? = null,
    val current: LatLng? = null,
    val start: LatLng? = null,
    val points: List<LatLng> = emptyList(),
    val walkedM: Double = 0.0,
    val areaM2: Double = 0.0,
    val distToStartM: Double? = null,
    val distToResumeM: Double? = null,
    val canFinish: Boolean = false,
    val speedKmh: Double = 0.0,
    val warning: WalkWarning = WalkWarning.NONE,
    val pauseReason: PauseReason = PauseReason.NONE,
    val activeMs: Long = 0,
    val anchorFixes: Int = 0,
) {
    val gpsGoodForStart: Boolean get() = accuracyM != null && accuracyM <= BoundaryRules.START_ACCURACY_M && warning != WalkWarning.GPS_LOST && warning != WalkWarning.MOCK_LOCATION
}

data class CapturedBoundary(
    val points: List<BoundaryPoint>,   // open ring, simple, as saved
    val areaM2: Double,
    val perimeterM: Double,
    val walkedM: Double,
    val accuracyM: Double,             // mean reported accuracy of the vertices
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val closingGapM: Double,
    val selfIntersects: Boolean,
) {
    val ring: List<LatLng> get() = points.map { LatLng(it.lat, it.lng) }
    val closesCleanly: Boolean get() = !selfIntersects && closingGapM <= BoundaryRules.FINISH_RADIUS_M && points.size >= BoundaryRules.MIN_POINTS
}

/** What survives process death mid-walk. */
@Serializable
data class WalkSnapshot(
    val farmerId: String,
    val landId: String?,
    val anchorLat: Double,
    val anchorLng: Double,
    val points: List<BoundaryPoint>,
    val walkedM: Double,
    val maxExcursionM: Double,
    val startedAtMs: Long,
    val activeMs: Long,
    val savedAtMs: Long,
)

/**
 * Turns a stream of GPS fixes into a walked boundary, enforcing the capture
 * rules. Points can only come from fixes: there is no API to add or move one.
 */
class BoundaryRecorder {
    private var phase = WalkPhase.WAITING_GPS
    private var pauseReason = PauseReason.NONE
    private var lastFix: GpsFix? = null
    private var lastFixElapsed = 0L
    private var warning = WalkWarning.NONE

    private var enu: Geo.Enu? = null
    private val filter = PositionFilter()
    private var filtered: LatLng? = null

    private val anchorFixes = mutableListOf<GpsFix>()
    private var anchor: LatLng? = null
    private val points = mutableListOf<BoundaryPoint>()
    private var walkedM = 0.0
    private var maxExcursionM = 0.0
    private var startedAtMs = 0L
    private var activeMs = 0L
    private var lastActiveElapsed = -1L
    private var fastFixes = 0
    private var slowFixes = 0

    var state: WalkState = WalkState()
        private set

    // ---- inputs ----

    fun onFix(fix: GpsFix): WalkState {
        lastFix = fix
        lastFixElapsed = fix.elapsedMs
        if (fix.isMock) {
            warning = WalkWarning.MOCK_LOCATION
            return publish()
        }
        if (phase == WalkPhase.FINISHED) return publish()

        val speed = fix.speedMps ?: filterSpeedOrNull()
        val kmh = (speed ?: 0.0) * 3.6
        when (phase) {
            WalkPhase.WAITING_GPS, WalkPhase.READY -> {
                phase = if (fix.accuracyM <= BoundaryRules.START_ACCURACY_M) WalkPhase.READY else WalkPhase.WAITING_GPS
                warning = if (fix.accuracyM <= BoundaryRules.START_ACCURACY_M) WalkWarning.NONE else WalkWarning.GPS_WEAK
                filtered = LatLng(fix.lat, fix.lng)
            }
            WalkPhase.ANCHORING -> {
                if (fix.accuracyM <= BoundaryRules.START_ACCURACY_M) {
                    anchorFixes += fix
                    warning = WalkWarning.NONE
                } else {
                    warning = WalkWarning.GPS_WEAK
                }
                filtered = LatLng(fix.lat, fix.lng)
                if (anchorFixes.size >= BoundaryRules.ANCHOR_FIXES) beginRecording(fix)
            }
            WalkPhase.RECORDING -> {
                tickActive(fix.elapsedMs)
                if (kmh > BoundaryRules.MAX_SPEED_KMH) fastFixes++ else fastFixes = 0
                if (fastFixes >= BoundaryRules.SPEED_CONFIRM_FIXES) {
                    phase = WalkPhase.PAUSED
                    pauseReason = PauseReason.TOO_FAST
                    warning = WalkWarning.TOO_FAST
                    lastActiveElapsed = -1
                    slowFixes = 0
                    return publish(kmh)
                }
                // A fix above walking speed never becomes a point, even before the pause is confirmed.
                if (fastFixes > 0) return publish(kmh)
                if (fix.accuracyM > BoundaryRules.WALK_MAX_ACCURACY_M) {
                    warning = WalkWarning.GPS_WEAK
                    return publish(kmh)
                }
                warning = if (fix.accuracyM > BoundaryRules.START_ACCURACY_M) WalkWarning.GPS_WEAK else WalkWarning.NONE
                val p = filterFix(fix)
                filtered = p
                maybeAddPoint(p, fix)
            }
            WalkPhase.PAUSED -> {
                val here = LatLng(fix.lat, fix.lng)
                filtered = here
                if (pauseReason == PauseReason.TOO_FAST) {
                    if (kmh <= BoundaryRules.MAX_SPEED_KMH) slowFixes++ else slowFixes = 0
                    val near = distToResume(here) <= BoundaryRules.RESUME_RADIUS_M
                    warning = if (kmh > BoundaryRules.MAX_SPEED_KMH) WalkWarning.TOO_FAST else if (!near) WalkWarning.WALK_BACK else WalkWarning.NONE
                    if (slowFixes >= 3 && near && fix.accuracyM <= BoundaryRules.WALK_MAX_ACCURACY_M) resumeInternal(fix)
                } else {
                    warning = if (distToResume(here) > BoundaryRules.RESUME_RADIUS_M) WalkWarning.WALK_BACK else WalkWarning.NONE
                }
            }
            WalkPhase.FINISHED -> Unit
        }
        return publish(kmh)
    }

    /** Called about once a second by the UI clock so "GPS lost" shows even with no fixes. */
    fun onTick(nowElapsedMs: Long): WalkState {
        if (lastFix != null && nowElapsedMs - lastFixElapsed > BoundaryRules.STALE_FIX_MS && phase != WalkPhase.FINISHED) {
            warning = WalkWarning.GPS_LOST
            if (phase == WalkPhase.READY) phase = WalkPhase.WAITING_GPS
        }
        if (phase == WalkPhase.RECORDING) tickActive(nowElapsedMs)
        return publish()
    }

    /** "Start walking". Allowed only with a fresh fix at ±5 m or better. */
    fun start(): Boolean {
        if (phase != WalkPhase.READY || !state.gpsGoodForStart) return false
        phase = WalkPhase.ANCHORING
        anchorFixes.clear()
        lastFix?.let { if (it.accuracyM <= BoundaryRules.START_ACCURACY_M) anchorFixes += it }
        publish()
        return true
    }

    fun pause() {
        if (phase != WalkPhase.RECORDING) return
        phase = WalkPhase.PAUSED
        pauseReason = PauseReason.USER
        lastActiveElapsed = -1
        publish()
    }

    /** Resume after a pause; only allowed back near the last recorded point. */
    fun resume(): Boolean {
        val fix = lastFix ?: return false
        if (phase != WalkPhase.PAUSED) return false
        if (fix.accuracyM > BoundaryRules.WALK_MAX_ACCURACY_M) return false
        if (pauseReason == PauseReason.TOO_FAST && (fix.speedMps ?: 0.0) * 3.6 > BoundaryRules.MAX_SPEED_KMH) return false
        if (distToResume(LatLng(fix.lat, fix.lng)) > BoundaryRules.RESUME_RADIUS_M) return false
        resumeInternal(fix)
        publish()
        return true
    }

    fun snapshot(farmerId: String, landId: String?, nowMs: Long): WalkSnapshot? {
        val a = anchor ?: return null
        return WalkSnapshot(farmerId, landId, a.lat, a.lng, points.toList(), walkedM, maxExcursionM, startedAtMs, activeMs, nowMs)
    }

    /** Continue a walk saved before the app was closed. Starts paused; the walker resumes at the last point. */
    fun restore(s: WalkSnapshot) {
        anchor = LatLng(s.anchorLat, s.anchorLng)
        enu = Geo.Enu(anchor!!)
        points.clear(); points += s.points
        walkedM = s.walkedM
        maxExcursionM = s.maxExcursionM
        startedAtMs = s.startedAtMs
        activeMs = s.activeMs
        phase = WalkPhase.PAUSED
        pauseReason = PauseReason.RESTORED
        publish()
    }

    /**
     * "Finish boundary". Closes the ring at the start corner, trims any
     * overshoot past the start, removes GPS back-steps, and measures it.
     */
    fun finish(nowMs: Long): CapturedBoundary? {
        if (!state.canFinish) return null
        val t = enu ?: return null
        val fix = lastFix
        val end = filtered
        if (end != null && fix != null) {
            val tail = Geo.distance(LatLng(points.last().lat, points.last().lng), end)
            if (tail >= 1.0) points += BoundaryPoint(end.lat, end.lng, fix.accuracyM, fix.timeMs)
            walkedM += tail
        }
        val closed = closeRing(points.toList(), t)
        val cleaned = removeBackSteps(closed, t)
        phase = WalkPhase.FINISHED
        publish()
        val ring = cleaned.map { LatLng(it.lat, it.lng) }
        val gap = Geo.distance(ring.first(), ring.last())
        return CapturedBoundary(
            points = cleaned,
            areaM2 = Geo.area(ring),
            perimeterM = Geo.perimeter(ring),
            walkedM = walkedM,
            accuracyM = cleaned.map { it.acc }.average(),
            startedAtMs = startedAtMs,
            finishedAtMs = nowMs,
            closingGapM = gap,
            selfIntersects = cleaned.size < 3 || Geo.selfIntersects(ring),
        )
    }

    // ---- internals ----

    private fun filterSpeedOrNull(): Double? = if (filter.initialized) filter.speed else null

    private fun tickActive(nowElapsed: Long) {
        if (lastActiveElapsed >= 0 && nowElapsed > lastActiveElapsed) activeMs += nowElapsed - lastActiveElapsed
        lastActiveElapsed = nowElapsed
    }

    private fun beginRecording(fix: GpsFix) {
        // Start corner: inverse-variance weighted mean of the anchoring fixes.
        var wSum = 0.0; var lat = 0.0; var lng = 0.0; var acc = 0.0
        for (f in anchorFixes) {
            val w = 1.0 / (f.accuracyM * f.accuracyM)
            wSum += w; lat += w * f.lat; lng += w * f.lng
        }
        lat /= wSum; lng /= wSum
        acc = 1.0 / kotlin.math.sqrt(wSum)
        val a = LatLng(lat, lng)
        anchor = a
        enu = Geo.Enu(a)
        filter.reset(XY(0.0, 0.0), acc, fix.elapsedMs)
        filtered = a
        points.clear()
        points += BoundaryPoint(a.lat, a.lng, anchorFixes.map { it.accuracyM }.average(), fix.timeMs)
        walkedM = 0.0
        maxExcursionM = 0.0
        startedAtMs = fix.timeMs
        activeMs = 0
        lastActiveElapsed = fix.elapsedMs
        fastFixes = 0
        phase = WalkPhase.RECORDING
        pauseReason = PauseReason.NONE
    }

    private fun resumeInternal(fix: GpsFix) {
        val t = enu ?: return
        filter.reset(t.project(LatLng(fix.lat, fix.lng)), fix.accuracyM, fix.elapsedMs)
        phase = WalkPhase.RECORDING
        pauseReason = PauseReason.NONE
        warning = WalkWarning.NONE
        fastFixes = 0
        lastActiveElapsed = fix.elapsedMs
    }

    private fun velocityOf(fix: GpsFix): Pair<XY?, Double> {
        val s = fix.speedMps ?: return null to 0.0
        val sigma = fix.speedAccuracyMps?.takeIf { it > 0 } ?: 0.5
        if (s < 0.3) return XY(0.0, 0.0) to max(sigma, 0.3) // standing: hold position
        val b = fix.bearingDeg ?: return null to 0.0
        // A bearing the chip itself isn't sure of would steer the filter wrong.
        if ((fix.bearingAccuracyDeg ?: 0.0) > 20.0) return null to 0.0
        val r = b * PI / 180
        return XY(s * sin(r), s * cos(r)) to sigma
    }

    private fun filterFix(fix: GpsFix): LatLng {
        val t = enu!!
        val (vel, vs) = velocityOf(fix)
        val p = filter.update(t.project(LatLng(fix.lat, fix.lng)), fix.accuracyM, fix.elapsedMs, vel, vs)
        return t.unproject(p)
    }

    private fun maybeAddPoint(p: LatLng, fix: GpsFix) {
        val last = points.last()
        val d = Geo.distance(LatLng(last.lat, last.lng), p)
        if (d < BoundaryRules.POINT_SPACING_M) return
        val step = BoundaryRules.POINT_SPACING_M
        if (d <= BoundaryRules.MAX_INTERPOLATE_M) {
            // Walking pace gives fixes 2-3 m apart: lay points every 2 m along that short
            // straight stretch; the remainder carries over to the next fix.
            val t = enu!!
            val a = t.project(LatLng(last.lat, last.lng))
            val b = t.project(p)
            var k = 1
            while (k * step <= d + 1e-9) {
                val f = k * step / d
                val q = t.unproject(XY(a.e + f * (b.e - a.e), a.n + f * (b.n - a.n)))
                points += BoundaryPoint(q.lat, q.lng, fix.accuracyM, fix.timeMs)
                k++
            }
            walkedM += (k - 1) * step
        } else {
            // After a gap, use the real fix only; never invent points across it.
            points += BoundaryPoint(p.lat, p.lng, fix.accuracyM, fix.timeMs)
            walkedM += d
        }
        anchor?.let { maxExcursionM = max(maxExcursionM, Geo.distance(it, p)) }
    }

    private fun distToResume(here: LatLng): Double {
        val last = points.lastOrNull() ?: return Double.MAX_VALUE
        return Geo.distance(LatLng(last.lat, last.lng), here)
    }

    private fun publish(kmh: Double = state.speedKmh): WalkState {
        val ring = points.map { LatLng(it.lat, it.lng) }
        val cur = filtered
        val a = anchor
        val dStart = if (a != null && cur != null) Geo.distance(a, cur) else null
        val areaRing = if (cur != null && phase == WalkPhase.RECORDING) ring + cur else ring
        val canFinish = phase == WalkPhase.RECORDING &&
            points.size >= BoundaryRules.MIN_POINTS &&
            maxExcursionM >= BoundaryRules.MIN_EXCURSION_M &&
            dStart != null && dStart <= BoundaryRules.FINISH_RADIUS_M &&
            warning != WalkWarning.GPS_LOST
        state = WalkState(
            phase = phase,
            accuracyM = lastFix?.accuracyM,
            current = cur,
            start = a,
            points = ring,
            walkedM = walkedM,
            areaM2 = if (areaRing.size >= 3) Geo.area(areaRing) else 0.0,
            distToStartM = dStart,
            distToResumeM = if (phase == WalkPhase.PAUSED && cur != null) distToResume(cur) else null,
            canFinish = canFinish,
            speedKmh = kmh,
            warning = warning,
            pauseReason = pauseReason,
            activeMs = activeMs,
            anchorFixes = anchorFixes.size,
        )
        return state
    }

    companion object {
        /**
         * Joins the end of the walk to its start. If the walker overshot the
         * start corner (or cut in just before it), the tail crosses the first
         * edges: cut both at the crossing so the ring closes exactly there.
         */
        fun closeRing(input: List<BoundaryPoint>, t: Geo.Enu): List<BoundaryPoint> {
            if (input.size < 4) return input
            // Overshoot along the first edge: cut the tail at its closest approach to the start.
            val start = LatLng(input[0].lat, input[0].lng)
            fun ll(i: Int) = LatLng(input[i].lat, input[i].lng)
            var k = input.size - 1
            var best = k
            var bestD = Geo.distance(ll(k), start)
            var walked = 0.0
            while (k > 2 && walked < BoundaryRules.SEAM_SEARCH_M) {
                walked += Geo.distance(ll(k), ll(k - 1))
                k--
                val d = Geo.distance(ll(k), start)
                if (d < bestD) { bestD = d; best = k }
            }
            val pts = input.subList(0, best + 1)
            if (pts.size < 4) return pts
            val xy = pts.map { t.project(LatLng(it.lat, it.lng)) }
            fun seg(i: Int) = hypot(xy[i + 1].e - xy[i].e, xy[i + 1].n - xy[i].n)
            var headEnd = 0
            var acc = 0.0
            while (headEnd < xy.size - 2 && acc < BoundaryRules.SEAM_SEARCH_M) { acc += seg(headEnd); headEnd++ }
            var tailStart = xy.size - 2
            acc = 0.0
            while (tailStart > 0 && acc < BoundaryRules.SEAM_SEARCH_M) { acc += seg(tailStart); tailStart-- }
            // Widest ring: earliest head edge, latest tail edge that cross.
            for (i in 0 until headEnd) {
                for (j in (xy.size - 2) downTo max(tailStart, i + 2)) {
                    val x = Geo.segmentIntersection(xy[i], xy[i + 1], xy[j], xy[j + 1]) ?: continue
                    val ll = t.unproject(x)
                    val crossing = BoundaryPoint(ll.lat, ll.lng, (pts[i].acc + pts[j].acc) / 2, pts[i].t)
                    return listOf(crossing) + pts.subList(i + 1, j + 1)
                }
            }
            return pts
        }

        /**
         * Removes GPS back-steps: a vertex where the path reverses on itself
         * (interior angle under 15°) between two short edges. A real farm
         * corner is never that sharp at 2 m spacing.
         */
        fun removeBackSteps(input: List<BoundaryPoint>, t: Geo.Enu): List<BoundaryPoint> {
            val pts = input.toMutableList()
            var changed = true
            while (changed && pts.size > BoundaryRules.MIN_POINTS) {
                changed = false
                var i = 0
                while (i < pts.size && pts.size > BoundaryRules.MIN_POINTS) {
                    val a = t.project(LatLng(pts[(i - 1 + pts.size) % pts.size].lat, pts[(i - 1 + pts.size) % pts.size].lng))
                    val b = t.project(LatLng(pts[i].lat, pts[i].lng))
                    val c = t.project(LatLng(pts[(i + 1) % pts.size].lat, pts[(i + 1) % pts.size].lng))
                    val ab = hypot(a.e - b.e, a.n - b.n)
                    val cb = hypot(c.e - b.e, c.n - b.n)
                    val tooClose = ab < 0.3
                    val angle = if (ab < 1e-6 || cb < 1e-6) 0.0 else
                        acos((((a.e - b.e) * (c.e - b.e) + (a.n - b.n) * (c.n - b.n)) / (ab * cb)).coerceIn(-1.0, 1.0)) * 180 / PI
                    if (tooClose || (angle < 15.0 && min(ab, cb) < 6.0)) {
                        pts.removeAt(i)
                        changed = true
                    } else {
                        i++
                    }
                }
            }
            return pts
        }
    }
}
