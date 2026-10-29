package org.fieldproof.geo

import kotlin.math.max

/**
 * Constant-velocity Kalman filter on the local tangent plane, one independent
 * [position, velocity] filter per axis. It takes GPS positions (weighted by the
 * fix's reported accuracy) and, when available, the Doppler velocity the GNSS
 * chip reports. Using velocity means the estimate does not lag behind a walker
 * (no corner cutting), while jitter from a standing phone is held still.
 */
class PositionFilter(
    /** Walking acceleration noise, m/s². Turning a corner at 1.2 m/s is ~1 m/s². */
    private val accelNoise: Double = 1.0,
) {
    private class Axis {
        var p = 0.0
        var v = 0.0
        var pp = 0.0 // var(p)
        var pv = 0.0 // cov(p, v)
        var vv = 0.0 // var(v)

        fun reset(pos: Double, posVar: Double, vel: Double, velVar: Double) {
            p = pos; v = vel; pp = posVar; pv = 0.0; vv = velVar
        }

        fun predict(dt: Double, q: Double) {
            p += v * dt
            val dt2 = dt * dt
            val npp = pp + 2 * dt * pv + dt2 * vv + q * dt2 * dt2 / 4
            val npv = pv + dt * vv + q * dt2 * dt / 2
            val nvv = vv + q * dt2
            pp = npp; pv = npv; vv = nvv
        }

        fun updatePosition(z: Double, r: Double) {
            val s = pp + r
            val kp = pp / s
            val kv = pv / s
            val y = z - p
            p += kp * y
            v += kv * y
            val npp = (1 - kp) * pp
            val npv = (1 - kp) * pv
            val nvv = vv - kv * pv
            pp = npp; pv = npv; vv = nvv
        }

        fun updateVelocity(z: Double, r: Double) {
            val s = vv + r
            val kp = pv / s
            val kv = vv / s
            val y = z - v
            p += kp * y
            v += kv * y
            val npp = pp - kp * pv
            val npv = (1 - kv) * pv
            val nvv = (1 - kv) * vv
            pp = npp; pv = npv; vv = nvv
        }
    }

    private val e = Axis()
    private val n = Axis()
    private var lastMs = -1L

    val position: XY get() = XY(e.p, n.p)
    val speed: Double get() = kotlin.math.hypot(e.v, n.v)
    val initialized: Boolean get() = lastMs >= 0

    fun reset(pos: XY, accuracyM: Double, timeMs: Long) {
        val r = sigma(accuracyM).let { it * it }
        e.reset(pos.e, r, 0.0, 1.0)
        n.reset(pos.n, r, 0.0, 1.0)
        lastMs = timeMs
    }

    /**
     * @param vel Doppler velocity (east, north) in m/s, or null if the fix has none.
     * @param velSigma its 1-sigma accuracy in m/s.
     */
    fun update(pos: XY, accuracyM: Double, timeMs: Long, vel: XY?, velSigma: Double): XY {
        if (lastMs < 0) {
            reset(pos, accuracyM, timeMs)
            return position
        }
        val dt = ((timeMs - lastMs) / 1000.0).coerceIn(0.0, 10.0)
        lastMs = timeMs
        val q = accelNoise * accelNoise
        if (dt > 0) {
            e.predict(dt, q)
            n.predict(dt, q)
        }
        val r = sigma(accuracyM).let { it * it }
        e.updatePosition(pos.e, r)
        n.updatePosition(pos.n, r)
        if (vel != null) {
            val rv = max(velSigma, 0.1).let { it * it }
            e.updateVelocity(vel.e, rv)
            n.updateVelocity(vel.n, rv)
        }
        return position
    }

    /** Android reports the 68% horizontal radius; for a 2-D normal error that is ~1.515 sigma per axis. */
    private fun sigma(accuracyM: Double) = max(accuracyM, 0.5) / 1.515
}
