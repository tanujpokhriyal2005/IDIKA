package org.fieldproof.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Drives the real recorder with simulated walks around fields of known area.
 * GPS error model: slowly drifting bias (Gauss-Markov, like multipath and
 * atmosphere) plus white noise, Doppler speed/bearing with noise.
 */
class BoundaryRecorderTest {
    private val origin = LatLng(19.4512, 77.1874) // Kurunda
    private val enu = Geo.Enu(origin)

    private val fieldCorners = listOf(XY(0.0, 0.0), XY(82.0, -6.0), XY(96.0, 40.0), XY(78.0, 84.0), XY(12.0, 80.0), XY(-8.0, 38.0))
    private val trueArea = Geo.shoelace(fieldCorners)

    class Sim(
        val rec: BoundaryRecorder,
        private val enu: Geo.Enu,
        seed: Long,
        private val biasSigma: Double,
        private val whiteSigma: Double,
        private val reportedAcc: Double = 3.0,
    ) {
        private val rnd = Random(seed)
        var tMs = 1_760_000_000_000L
        var el = 0L
        private var bx = rnd.nextGaussian() * biasSigma
        private var by = rnd.nextGaussian() * biasSigma
        /** Raw measured positions while moving — what the GPS alone would give. */
        val raw = mutableListOf<LatLng>()

        fun fix(p: XY, speed: Double, heading: Double, acc: Double = reportedAcc, mock: Boolean = false): WalkState {
            tMs += 1000; el += 1000
            val a = exp(-1.0 / 60.0) // 60 s correlation time
            val k = biasSigma * sqrt(1 - a * a)
            bx = a * bx + k * rnd.nextGaussian()
            by = a * by + k * rnd.nextGaussian()
            val m = XY(p.e + bx + whiteSigma * rnd.nextGaussian(), p.n + by + whiteSigma * rnd.nextGaussian())
            val ll = enu.unproject(m)
            if (speed > 0) raw += ll
            val sp = (speed + 0.1 * rnd.nextGaussian()).coerceAtLeast(0.0)
            val brg = (Math.toDegrees(heading) + 3 * rnd.nextGaussian() + 360) % 360
            return rec.onFix(GpsFix(ll.lat, ll.lng, acc, tMs, el, sp, brg, 0.3, mock))
        }

        fun stand(p: XY, seconds: Int) = repeat(seconds) { fix(p, 0.0, 0.0) }

        /** Walks the polygon edges from [from] at [speed] m/s, one fix per second, ending [overshoot] m past the start. */
        fun walk(corners: List<XY>, speed: Double = 1.2, overshoot: Double = 0.0, stopShortM: Double = 0.0) {
            val path = corners + corners.first()
            val legs = path.zipWithNext()
            val total = legs.sumOf { (a, b) -> hypot(b.e - a.e, b.n - a.n) }
            var s = 0.0
            val end = total + overshoot - stopShortM
            while (s < end) {
                s = minOf(s + speed, end)
                var rem = s % total
                if (s >= total && overshoot > 0) rem = s - total
                for ((a, b) in legs) {
                    val l = hypot(b.e - a.e, b.n - a.n)
                    if (rem <= l) {
                        val f = rem / l
                        fix(XY(a.e + f * (b.e - a.e), a.n + f * (b.n - a.n)), speed, atan2(b.e - a.e, b.n - a.n))
                        break
                    }
                    rem -= l
                }
            }
        }
    }

    private fun startAt(sim: Sim, p: XY) {
        sim.stand(p, 3)
        assertTrue("start must be allowed at ±3 m", sim.rec.start())
        sim.stand(p, 3) // anchoring
        assertEquals(WalkPhase.RECORDING, sim.rec.state.phase)
    }

    @Test fun perfectGpsReproducesFieldArea() {
        val rec = BoundaryRecorder()
        val sim = Sim(rec, enu, 1, 0.0, 0.0)
        startAt(sim, fieldCorners[0])
        sim.walk(fieldCorners)
        assertTrue(rec.state.canFinish)
        val b = rec.finish(sim.tMs)
        assertNotNull(b)
        b!!
        val err = abs(b.areaM2 - trueArea) / trueArea
        val dev = b.points.map { distToPolygon(enu.project(LatLng(it.lat, it.lng)), fieldCorners) }
        println("perfect GPS: true %.1f m², walked %.1f m², error %.3f%%, %d points, max off-line %.2f m, mean %.2f m".format(
            trueArea, b.areaM2, err * 100, b.points.size, dev.max(), dev.average()))
        assertTrue("area error ${err * 100}%", err < 0.005)
        assertFalse(b.selfIntersects)
        assertTrue(b.closesCleanly)
        assertTrue(b.points.size >= BoundaryRules.MIN_POINTS)
        // Every point lies on the true boundary — nothing invented or drawn.
        assertTrue("max deviation ${dev.max()} m", dev.max() < 1.0)
        // A point every 2 m walked: count ≈ perimeter / 2.
        val perimeter = fieldCorners.indices.sumOf { i -> val a = fieldCorners[i]; val c = fieldCorners[(i + 1) % fieldCorners.size]; hypot(c.e - a.e, c.n - a.n) }
        assertTrue("points ${b.points.size} for ${perimeter.toInt()} m", abs(b.points.size - perimeter / 2) <= 6)
        assertEquals(perimeter, b.walkedM, 3.0)
        // Spacing rule: consecutive points ≥ 2 m apart (except closing seam).
        b.points.zipWithNext().dropLast(1).forEach { (a, c) -> val d = Geo.distance(LatLng(a.lat, a.lng), LatLng(c.lat, c.lng)); assertTrue("spacing $d", d >= 1.99 && d <= 2.6) }
    }

    /**
     * With realistic GPS error the walked area can only be as good as the GPS.
     * The recorder must not add error on top: compare with the polygon of the
     * raw fixes themselves for the same walks.
     */
    @Test fun noisyGpsAddsNoErrorBeyondTheGpsItself() {
        val ours = mutableListOf<Double>()
        val gps = mutableListOf<Double>()
        for (seed in 1..60L) {
            val rec = BoundaryRecorder()
            val sim = Sim(rec, enu, seed, biasSigma = 1.5, whiteSigma = 1.0)
            startAt(sim, fieldCorners[0])
            sim.walk(fieldCorners)
            if (!rec.state.canFinish) sim.stand(fieldCorners[0], 5)
            val b = rec.finish(sim.tMs)
            assertNotNull("seed $seed could not finish (dist ${rec.state.distToStartM})", b)
            assertFalse("seed $seed self-intersects", b!!.selfIntersects)
            ours += abs(b.areaM2 - trueArea) / trueArea
            gps += abs(Geo.area(sim.raw) - trueArea) / trueArea
        }
        val o = ours.sorted(); val g = gps.sorted()
        fun pct(v: Double) = "%.2f%%".format(v * 100)
        println("noisy GPS (drifting bias σ1.5 m + white σ1 m), 60 walks of a %.0f m² field:".format(trueArea))
        println("  recorder  median ${pct(o[30])}  p90 ${pct(o[54])}  worst ${pct(o.last())}")
        println("  raw fixes median ${pct(g[30])}  p90 ${pct(g[54])}  worst ${pct(g.last())}")
        assertTrue("median ${pct(o[30])}", o[30] < 0.02)
        assertTrue("recorder median worse than raw GPS", o[30] <= g[30] + 0.003)
        assertTrue("recorder p90 worse than raw GPS", o[54] <= g[54] + 0.005)
    }

    @Test fun typicalPhoneGpsWithinOnePercent() {
        // Open farmland, good sky view: ~0.7 m drift, 0.5 m jitter.
        val errs = (1..40L).map { seed ->
            val rec = BoundaryRecorder()
            val sim = Sim(rec, enu, 1000 + seed, biasSigma = 0.7, whiteSigma = 0.5)
            startAt(sim, fieldCorners[0])
            sim.walk(fieldCorners)
            if (!rec.state.canFinish) sim.stand(fieldCorners[0], 5)
            val b = rec.finish(sim.tMs)!!
            assertFalse(b.selfIntersects)
            abs(b.areaM2 - trueArea) / trueArea
        }.sorted()
        println("good-sky GPS, 40 walks: median %.2f%%, worst %.2f%%".format(errs[20] * 100, errs.last() * 100))
        assertTrue(errs[20] < 0.01)
        assertTrue(errs.last() < 0.035)
    }

    @Test fun startNeedsFiveMetreAccuracy() {
        val rec = BoundaryRecorder()
        val sim = Sim(rec, enu, 2, 0.0, 0.0, reportedAcc = 8.0)
        sim.stand(XY(0.0, 0.0), 3)
        assertEquals(WalkPhase.WAITING_GPS, rec.state.phase)
        assertFalse(rec.start())
        repeat(2) { sim.fix(XY(0.0, 0.0), 0.0, 0.0, acc = 4.0) }
        assertEquals(WalkPhase.READY, rec.state.phase)
        assertTrue(rec.start())
    }

    @Test fun finishLockedUntilWalkedAwayAndBackWithinTenMetres() {
        val rec = BoundaryRecorder()
        val sim = Sim(rec, enu, 3, 0.0, 0.0)
        startAt(sim, fieldCorners[0])
        assertFalse("can't finish at the start before walking", rec.state.canFinish)
        sim.walk(fieldCorners, stopShortM = 25.0)
        assertFalse("25 m from start must stay locked", rec.state.canFinish)
        assertTrue(rec.state.distToStartM!! > 10)
        assertEquals(null, rec.finish(sim.tMs))
        sim.walk(fieldCorners, stopShortM = 6.0) // continue: second lap ends 6 m short
        assertTrue(rec.state.distToStartM!! <= 10)
        assertTrue(rec.state.canFinish)
    }

    @Test fun vehicleSpeedPausesAndResumesOnlyNearLastPoint() {
        val rec = BoundaryRecorder()
        val sim = Sim(rec, enu, 4, 0.0, 0.0)
        startAt(sim, XY(0.0, 0.0))
        for (i in 1..20) sim.fix(XY(i * 1.2, 0.0), 1.2, Math.PI / 2)
        val before = rec.state.points.size
        // Jump on a motorbike: 15 km/h east.
        var x = 24.0
        repeat(10) { x += 4.2; sim.fix(XY(x, 0.0), 4.2, Math.PI / 2) }
        assertEquals(WalkPhase.PAUSED, rec.state.phase)
        assertEquals(PauseReason.TOO_FAST, rec.state.pauseReason)
        assertTrue(rec.state.points.size <= before + 1)
        // Stopped far away: must not auto-resume.
        repeat(5) { sim.fix(XY(x, 0.0), 0.0, 0.0) }
        assertEquals(WalkPhase.PAUSED, rec.state.phase)
        assertEquals(WalkWarning.WALK_BACK, rec.state.warning)
        assertFalse(rec.resume())
        // Walk back to where it paused: resumes automatically.
        while (x > 26) { x -= 1.2; sim.fix(XY(x, 0.0), 1.2, -Math.PI / 2) }
        repeat(3) { sim.fix(XY(x, 0.0), 0.5, -Math.PI / 2) }
        assertEquals(WalkPhase.RECORDING, rec.state.phase)
    }

    @Test fun standingStillAddsNoPoints() {
        val rec = BoundaryRecorder()
        val sim = Sim(rec, enu, 5, biasSigma = 0.0, whiteSigma = 1.5)
        startAt(sim, XY(0.0, 0.0))
        for (i in 1..15) sim.fix(XY(i * 1.2, 0.0), 1.2, Math.PI / 2)
        val n = rec.state.points.size
        sim.stand(XY(18.0, 0.0), 120) // two minutes talking to the farmer
        assertTrue("jitter added ${rec.state.points.size - n} points", rec.state.points.size - n <= 1)
    }

    @Test fun overshootPastStartIsTrimmedCleanly() {
        val rec = BoundaryRecorder()
        val sim = Sim(rec, enu, 6, 0.0, 0.0)
        // Start a little along the first edge so the overshoot crosses the start.
        startAt(sim, XY(4.0, -0.3))
        val shifted = listOf(XY(4.0, -0.3)) + fieldCorners.drop(1) + fieldCorners[0]
        sim.walk(shifted, overshoot = 8.0)
        val b = rec.finish(sim.tMs)!!
        assertFalse(b.selfIntersects)
        val err = abs(b.areaM2 - trueArea) / trueArea
        assertTrue("area error after overshoot ${err * 100}%", err < 0.005)
    }

    @Test fun mockLocationsAreRejected() {
        val rec = BoundaryRecorder()
        val sim = Sim(rec, enu, 7, 0.0, 0.0)
        sim.fix(XY(0.0, 0.0), 0.0, 0.0, mock = true)
        assertEquals(WalkWarning.MOCK_LOCATION, rec.state.warning)
        assertFalse(rec.start())
    }

    @Test fun walkThatCrossesItselfIsFlagged() {
        val rec = BoundaryRecorder()
        val sim = Sim(rec, enu, 8, 0.0, 0.0)
        val bow = listOf(XY(0.0, 0.0), XY(60.0, 60.0), XY(60.0, 0.0), XY(0.0, 60.0))
        startAt(sim, bow[0])
        sim.walk(bow)
        val b = rec.finish(sim.tMs)!!
        assertTrue(b.selfIntersects)
        assertFalse(b.closesCleanly)
    }

    @Test fun snapshotRestoreContinuesWalk() {
        val rec = BoundaryRecorder()
        val sim = Sim(rec, enu, 9, 0.0, 0.0)
        startAt(sim, XY(0.0, 0.0))
        for (i in 1..30) sim.fix(XY(i * 1.2, 0.0), 1.2, Math.PI / 2)
        val snap = rec.snapshot("f", null, sim.tMs)!!
        val rec2 = BoundaryRecorder()
        rec2.restore(snap)
        val sim2 = Sim(rec2, enu, 10, 0.0, 0.0)
        sim2.fix(XY(80.0, 0.0), 0.0, 0.0)
        assertFalse("can't resume 44 m away", rec2.resume())
        sim2.fix(XY(37.0, 0.0), 0.0, 0.0)
        assertTrue(rec2.resume())
        assertEquals(snap.points.size, rec2.state.points.size)
    }

    private fun distToPolygon(p: XY, poly: List<XY>): Double = poly.indices.minOf { i ->
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        val dx = b.e - a.e; val dy = b.n - a.n
        val t = (((p.e - a.e) * dx + (p.n - a.n) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        hypot(p.e - (a.e + t * dx), p.n - (a.n + t * dy))
    }
}
