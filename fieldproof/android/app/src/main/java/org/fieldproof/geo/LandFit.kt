package org.fieldproof.geo

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Where the surveyor is, or what they walked, compared with the land checked at
 * onboarding. Same thresholds as the server (backend landswap.go), so the phone
 * warns before anything is saved.
 */
object LandFit {
    const val OK = 0.70          // share of a walk on the onboarding land that needs no explanation
    const val MIN = 0.30         // below this it is a different land: report a land change
    const val ALLOWANCE_M = 20.0 // GPS drift at the edges
    const val NEAR_M = 50.0      // before / during a walk: this close counts as at the field
    const val FAR_M = 300.0      // before a walk: farther than this blocks the walk

    private fun inside(p: XY, ring: List<XY>): Boolean {
        var c = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[j]
            if ((a.n > p.n) != (b.n > p.n) && p.e < (b.e - a.e) * (p.n - a.n) / (b.n - a.n) + a.e) c = !c
            j = i
        }
        return c
    }

    private fun nearestOnSegment(p: XY, a: XY, b: XY): XY {
        val dx = b.e - a.e; val dy = b.n - a.n
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else (((p.e - a.e) * dx + (p.n - a.n) * dy) / len).coerceIn(0.0, 1.0)
        return XY(a.e + t * dx, a.n + t * dy)
    }

    private fun edgeDistance(p: XY, ring: List<XY>): Pair<Double, XY> {
        var best = Double.MAX_VALUE
        var at = ring.first()
        for (i in ring.indices) {
            val q = nearestOnSegment(p, ring[i], ring[(i + 1) % ring.size])
            val d = hypot(q.e - p.e, q.n - p.n)
            if (d < best) { best = d; at = q }
        }
        return best to at
    }

    /**
     * Metres from [p] to the nearest of [rings]: negative inside (how deep), positive outside.
     * Null when there is nothing to compare with.
     */
    fun signedDistance(p: LatLng, rings: List<List<LatLng>>): Double? {
        val enu = Geo.Enu(p)
        val origin = XY(0.0, 0.0)
        var best: Double? = null
        for (r in rings.map { Geo.openRing(it) }.filter { it.size >= 3 }) {
            val xy = r.map { enu.project(it) }
            val (d, _) = edgeDistance(origin, xy)
            val s = if (inside(origin, xy)) -d else d
            best = if (best == null) s else min(best, s)
        }
        return best
    }

    /** The point on the edge of [rings] closest to [p] ([p] itself when inside). */
    fun nearestPoint(p: LatLng, rings: List<List<LatLng>>): LatLng? {
        val enu = Geo.Enu(p)
        val origin = XY(0.0, 0.0)
        var best = Double.MAX_VALUE
        var at: XY? = null
        for (r in rings.map { Geo.openRing(it) }.filter { it.size >= 3 }) {
            val xy = r.map { enu.project(it) }
            if (inside(origin, xy)) return p
            val (d, q) = edgeDistance(origin, xy)
            if (d < best) { best = d; at = q }
        }
        return at?.let { enu.unproject(it) }
    }

    /** Compass bearing from [a] to [b], degrees from north. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val v = Geo.Enu(a).project(b)
        return (Math.toDegrees(atan2(v.e, v.n)) + 360) % 360
    }

    /**
     * Share of the area of [walk] that lies on [rings] (within [ALLOWANCE_M] of them),
     * by sampling a grid over the walk. Null when there is nothing to compare with.
     */
    fun share(walk: List<LatLng>, rings: List<List<LatLng>>, grid: Int = 48): Double? {
        val w = Geo.openRing(walk)
        val refs = rings.map { Geo.openRing(it) }.filter { it.size >= 3 }
        if (w.size < 3 || refs.isEmpty()) return null
        val enu = Geo.Enu(Geo.centroid(w))
        val wx = w.map { enu.project(it) }
        val rx = refs.map { r -> r.map { enu.project(it) } }
        val minE = wx.minOf { it.e }; val maxE = wx.maxOf { it.e }
        val minN = wx.minOf { it.n }; val maxN = wx.maxOf { it.n }
        var total = 0
        var on = 0
        for (i in 0 until grid) for (j in 0 until grid) {
            val p = XY(minE + (i + 0.5) * (maxE - minE) / grid, minN + (j + 0.5) * (maxN - minN) / grid)
            if (!inside(p, wx)) continue
            total++
            if (rx.any { r -> inside(p, r) || edgeDistance(p, r).first <= ALLOWANCE_M }) on++
        }
        return if (total == 0) null else on.toDouble() / total
    }

    /** Distance between the middle of [walk] and the middle of [rings]. */
    fun centreDistance(walk: List<LatLng>, rings: List<List<LatLng>>): Double {
        val all = rings.flatMap { Geo.openRing(it) }
        if (walk.isEmpty() || all.isEmpty()) return 0.0
        return Geo.distance(Geo.centroid(Geo.openRing(walk)), Geo.centroid(all))
    }

    /** "N", "NE", … for a bearing. */
    fun compassPoint(deg: Double): Int = (((deg % 360) + 360 + 22.5) / 45).toInt() % 8
}
