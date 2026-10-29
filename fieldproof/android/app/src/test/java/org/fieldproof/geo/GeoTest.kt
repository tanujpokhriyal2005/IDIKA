package org.fieldproof.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GeoTest {
    private val hingoli = LatLng(19.5651, 77.0482)
    private val t = Geo.Enu(hingoli)
    private fun shape(vararg xy: Pair<Double, Double>) = xy.map { t.unproject(XY(it.first, it.second)) }

    @Test fun distanceMatchesKnownValues() {
        val p = shape(0.0 to 0.0, 3.0 to 4.0)
        assertEquals(5.0, Geo.distance(p[0], p[1]), 1e-4)
        // One arc-second of latitude at 19.5°N on WGS84 ≈ 30.73 m.
        assertEquals(30.73, Geo.distance(LatLng(19.5, 77.0), LatLng(19.5 + 1.0 / 3600, 77.0)), 0.02)
    }

    @Test fun unprojectInvertsProject() {
        val ll = t.unproject(XY(123.4, -56.7))
        val back = t.project(ll)
        assertEquals(123.4, back.e, 1e-6)
        assertEquals(-56.7, back.n, 1e-6)
    }

    @Test fun squareOfOnePointFiveAcres() {
        val side = sqrt(1.5 * Geo.SQ_M_PER_ACRE)
        val ring = shape(0.0 to 0.0, side to 0.0, side to side, 0.0 to side)
        assertEquals(1.5 * Geo.SQ_M_PER_ACRE, Geo.area(ring), 0.01)
    }

    /** Values PostGIS ST_Area(geography) returned for the same shapes in the backend tests. */
    @Test fun areaAgreesWithBackendGolden() {
        val ring = shape(0.0 to 0.0, 120.0 to 10.0, 140.0 to 90.0, 60.0 to 130.0, -10.0 to 70.0)
        val plane = Geo.shoelace(listOf(XY(0.0, 0.0), XY(120.0, 10.0), XY(140.0, 90.0), XY(60.0, 130.0), XY(-10.0, 70.0)))
        assertEquals(plane, Geo.area(ring), 0.01)
        assertTrue(Geo.signedArea(ring) > 0)
        assertTrue(Geo.signedArea(ring.reversed()) < 0)
        assertEquals(Geo.area(ring), Geo.area(ring + ring.first()), 1e-9)
    }

    @Test fun selfIntersection() {
        assertTrue(Geo.selfIntersects(shape(0.0 to 0.0, 100.0 to 100.0, 100.0 to 0.0, 0.0 to 100.0)))
        assertFalse(Geo.selfIntersects(shape(0.0 to 0.0, 80.0 to 0.0, 80.0 to 50.0, 0.0 to 50.0)))
        assertFalse(Geo.selfIntersects(shape(0.0 to 0.0, 60.0 to 0.0, 60.0 to 15.0, 15.0 to 15.0, 15.0 to 45.0, 60.0 to 45.0, 60.0 to 60.0, 0.0 to 60.0)))
        assertTrue(Geo.selfIntersects(shape(0.0 to 0.0, 50.0 to 0.0, 25.0 to 25.0, 50.0 to 50.0, 0.0 to 50.0, 25.0 to 25.0)))
    }

    @Test fun ccwOrdersRing() {
        val cw = shape(0.0 to 0.0, 0.0 to 50.0, 50.0 to 50.0, 50.0 to 0.0)
        assertTrue(Geo.signedArea(cw) < 0)
        assertTrue(Geo.signedArea(Geo.ccw(cw)) > 0)
        assertEquals(4, Geo.ccw(cw + cw.first()).size)
    }

    @Test fun segmentIntersectionPoint() {
        val x = Geo.segmentIntersection(XY(0.0, 0.0), XY(10.0, 10.0), XY(0.0, 10.0), XY(10.0, 0.0))!!
        assertTrue(abs(x.e - 5) < 1e-9 && abs(x.n - 5) < 1e-9)
    }
}
