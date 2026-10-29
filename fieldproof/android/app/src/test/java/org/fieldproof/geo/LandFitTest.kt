package org.fieldproof.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandFitTest {
    private val origin = LatLng(19.58, 77.21)
    private val enu = Geo.Enu(origin)

    /** A square of side [m] metres, its south-west corner [east]/[north] metres from the origin. */
    private fun square(m: Double, east: Double = 0.0, north: Double = 0.0) = listOf(
        XY(east, north), XY(east + m, north), XY(east + m, north + m), XY(east, north + m),
    ).map { enu.unproject(it) }

    private fun at(east: Double, north: Double) = enu.unproject(XY(east, north))

    @Test fun signedDistanceInsideAndOutside() {
        val field = listOf(square(100.0))
        assertEquals(-50.0, LandFit.signedDistance(at(50.0, 50.0), field)!!, 0.5)
        assertEquals(30.0, LandFit.signedDistance(at(130.0, 50.0), field)!!, 0.5)
        assertEquals(500.0, LandFit.signedDistance(at(50.0, 600.0), field)!!, 1.0)
        assertNull(LandFit.signedDistance(at(0.0, 0.0), emptyList()))
    }

    @Test fun nearestPointAndBearing() {
        val field = listOf(square(100.0))
        val q = LandFit.nearestPoint(at(50.0, 400.0), field)
        assertNotNull(q)
        assertEquals(300.0, Geo.distance(at(50.0, 400.0), q!!), 1.0)
        assertEquals(180.0, LandFit.bearing(at(50.0, 400.0), q), 1.0)
        assertEquals(90.0, LandFit.bearing(at(0.0, 0.0), at(100.0, 0.0)), 1.0)
        assertEquals(1, LandFit.compassPoint(45.0)) // NE
        assertEquals(0, LandFit.compassPoint(350.0)) // N
    }

    @Test fun shareOfAWalk() {
        val field = listOf(square(100.0))
        assertEquals(1.0, LandFit.share(square(96.0, 2.0, 2.0), field)!!, 0.02)
        // Half shifted: half inside, plus the 20 m allowance.
        val half = LandFit.share(square(100.0, 50.0, 0.0), field)!!
        assertTrue("half=$half", half in 0.65..0.75)
        // Another field 600 m away.
        assertEquals(0.0, LandFit.share(square(100.0, 0.0, 600.0), field)!!, 0.001)
        assertEquals(600.0, LandFit.centreDistance(square(100.0, 0.0, 600.0), field), 2.0)
        assertNull(LandFit.share(square(100.0), emptyList()))
    }
}
