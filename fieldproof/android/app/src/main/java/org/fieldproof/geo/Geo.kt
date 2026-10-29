package org.fieldproof.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Boundary geometry: WGS84 -> local East/North tangent plane, area, distance,
 * orientation and self-intersection. Line-for-line port of the backend's
 * internal/geo/geo.go, so the phone and server always agree on area.
 */
data class LatLng(val lat: Double, val lng: Double)

data class XY(val e: Double, val n: Double)

object Geo {
    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val E2 = F * (2 - F)

    const val SQ_M_PER_ACRE = 4046.8564224
    const val SQ_M_PER_HECTARE = 10000.0
    const val ACRES_PER_HECTARE = SQ_M_PER_HECTARE / SQ_M_PER_ACRE

    private fun rad(d: Double) = d * PI / 180

    private fun ecef(p: LatLng): DoubleArray {
        val phi = rad(p.lat)
        val lam = rad(p.lng)
        val s = sin(phi)
        val n = A / sqrt(1 - E2 * s * s)
        return doubleArrayOf(n * cos(phi) * cos(lam), n * cos(phi) * sin(lam), n * (1 - E2) * s)
    }

    /** Straight-line metres between two surface points; equals geodesic distance to < 1 mm under a few km. */
    fun distance(a: LatLng, b: LatLng): Double {
        val p = ecef(a)
        val q = ecef(b)
        val dx = p[0] - q[0]
        val dy = p[1] - q[1]
        val dz = p[2] - q[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Local tangent plane anchored at [origin]. */
    class Enu(val origin: LatLng) {
        private val o = ecef(origin)
        private val sinP = sin(rad(origin.lat))
        private val cosP = cos(rad(origin.lat))
        private val sinL = sin(rad(origin.lng))
        private val cosL = cos(rad(origin.lng))

        fun project(p: LatLng): XY {
            val x = ecef(p)
            val dx = x[0] - o[0]
            val dy = x[1] - o[1]
            val dz = x[2] - o[2]
            return XY(
                e = -sinL * dx + cosL * dy,
                n = -sinP * cosL * dx - sinP * sinL * dy + cosP * dz,
            )
        }

        /** Inverse of [project] for a point on the plane, dropped onto the ellipsoid. */
        fun unproject(p: XY): LatLng {
            val x = o[0] - sinL * p.e - sinP * cosL * p.n
            val y = o[1] + cosL * p.e - sinP * sinL * p.n
            val z = o[2] + cosP * p.n
            val lng = atan2(y, x)
            val r = hypot(x, y)
            var lat = atan2(z, r * (1 - E2))
            repeat(10) {
                val s = sin(lat)
                val n = A / sqrt(1 - E2 * s * s)
                val h = r / cos(lat) - n
                lat = atan2(z, r * (1 - E2 * n / (n + h)))
            }
            return LatLng(lat * 180 / PI, lng * 180 / PI)
        }
    }

    fun centroid(pts: List<LatLng>): LatLng =
        LatLng(pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lng } / pts.size)

    fun openRing(pts: List<LatLng>): List<LatLng> =
        if (pts.size > 1 && pts.first() == pts.last()) pts.dropLast(1) else pts

    fun projectRing(pts: List<LatLng>): List<XY> {
        val open = openRing(pts)
        val t = Enu(centroid(open))
        return open.map { t.project(it) }
    }

    fun shoelace(xy: List<XY>): Double {
        var s = 0.0
        for (i in xy.indices) {
            val j = (i + 1) % xy.size
            s += xy[i].e * xy[j].n - xy[j].e * xy[i].n
        }
        return s / 2
    }

    /** m², positive when counter-clockwise. */
    fun signedArea(pts: List<LatLng>): Double = if (openRing(pts).size < 3) 0.0 else shoelace(projectRing(pts))

    /** m²; within 1e-6 of the ellipsoidal area for fields up to several km across. */
    fun area(pts: List<LatLng>): Double = abs(signedArea(pts))

    fun perimeter(pts: List<LatLng>): Double {
        val open = openRing(pts)
        if (open.size < 2) return 0.0
        var s = 0.0
        for (i in open.indices) s += distance(open[i], open[(i + 1) % open.size])
        return s
    }

    /** Open ring in counter-clockwise order (KML outer-ring convention). */
    fun ccw(pts: List<LatLng>): List<LatLng> {
        val open = openRing(pts)
        return if (signedArea(open) < 0) open.reversed() else open
    }

    private fun orient(a: XY, b: XY, c: XY) = (b.e - a.e) * (c.n - a.n) - (b.n - a.n) * (c.e - a.e)

    private fun onSegment(a: XY, b: XY, p: XY) =
        min(a.e, b.e) - 1e-9 <= p.e && p.e <= max(a.e, b.e) + 1e-9 &&
            min(a.n, b.n) - 1e-9 <= p.n && p.n <= max(a.n, b.n) + 1e-9

    fun segmentsIntersect(a: XY, b: XY, c: XY, d: XY): Boolean {
        val eps = 1e-9
        val d1 = orient(c, d, a)
        val d2 = orient(c, d, b)
        val d3 = orient(a, b, c)
        val d4 = orient(a, b, d)
        if (((d1 > eps && d2 < -eps) || (d1 < -eps && d2 > eps)) &&
            ((d3 > eps && d4 < -eps) || (d3 < -eps && d4 > eps))
        ) return true
        if (abs(d1) <= eps && onSegment(c, d, a)) return true
        if (abs(d2) <= eps && onSegment(c, d, b)) return true
        if (abs(d3) <= eps && onSegment(a, b, c)) return true
        if (abs(d4) <= eps && onSegment(a, b, d)) return true
        return false
    }

    /** Intersection point of segments ab and cd, or null if they don't properly cross. */
    fun segmentIntersection(a: XY, b: XY, c: XY, d: XY): XY? {
        val r = XY(b.e - a.e, b.n - a.n)
        val s = XY(d.e - c.e, d.n - c.n)
        val denom = r.e * s.n - r.n * s.e
        if (abs(denom) < 1e-12) return null
        val qp = XY(c.e - a.e, c.n - a.n)
        val t = (qp.e * s.n - qp.n * s.e) / denom
        val u = (qp.e * r.n - qp.n * r.e) / denom
        if (t < 0 || t > 1 || u < 0 || u > 1) return null
        return XY(a.e + t * r.e, a.n + t * r.n)
    }

    /** True if any two non-adjacent edges of the closed ring touch. */
    fun selfIntersects(pts: List<LatLng>): Boolean = firstCrossing(pts) != null

    /** Indices (i, j) of the first pair of crossing edges, or null if the ring is simple. */
    fun firstCrossing(pts: List<LatLng>): Pair<Int, Int>? {
        val xy = projectRing(pts)
        val n = xy.size
        if (n < 4) return null
        for (i in 0 until n) {
            val a = xy[i]
            val b = xy[(i + 1) % n]
            for (j in i + 2 until n) {
                if (i == 0 && j == n - 1) continue
                if (segmentsIntersect(a, b, xy[j], xy[(j + 1) % n])) return i to j
            }
        }
        return null
    }

    const val MIN_POINTS = 12
}
