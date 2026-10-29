package org.fieldproof.location

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fieldproof.data.Repository
import org.fieldproof.geo.BoundaryRecorder
import org.fieldproof.geo.CapturedBoundary
import org.fieldproof.geo.WalkPhase
import org.fieldproof.geo.WalkSnapshot
import org.fieldproof.geo.WalkState

/**
 * Owns the boundary recorder for the walk in progress: feeds it GPS fixes and
 * a 1 s clock, keeps a foreground service alive while recording, and saves a
 * snapshot after every new point so a killed app can continue the walk.
 */
class WalkController(private val ctx: Context, private val gps: GpsTracker, private val repo: Repository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder = BoundaryRecorder()
    private var jobs = listOf<Job>()
    private var lastSavedPoints = -1

    var farmerId: String? = null; private set
    var landId: String? = null; private set
    private val _state = MutableStateFlow(WalkState())
    val state: StateFlow<WalkState> = _state
    var captured: CapturedBoundary? = null; private set

    /** Prepare a walk for this farmer/parcel and start listening to GPS. */
    fun open(farmerId: String, landId: String?, snapshot: WalkSnapshot?) {
        if (this.farmerId == farmerId && this.landId == landId && jobs.isNotEmpty()) return
        close()
        this.farmerId = farmerId
        this.landId = landId
        recorder = BoundaryRecorder()
        captured = null
        lastSavedPoints = -1
        if (snapshot != null && snapshot.farmerId == farmerId && snapshot.landId == landId) recorder.restore(snapshot)
        gps.acquire()
        gps.lastFix.value?.let { if (SystemClock.elapsedRealtime() - it.elapsedMs < 3000) recorder.onFix(it) }
        _state.value = recorder.state
        jobs = listOf(
            scope.launch { gps.fixes.collect { fix -> onState(recorder.onFix(fix)) } },
            scope.launch {
                while (isActive) {
                    delay(1000)
                    onState(recorder.onTick(SystemClock.elapsedRealtime()))
                }
            },
        )
    }

    private fun onState(s: WalkState) {
        _state.value = s
        val recording = s.phase == WalkPhase.RECORDING || s.phase == WalkPhase.PAUSED || s.phase == WalkPhase.ANCHORING
        if (recording) WalkService.update(ctx, s)
        if ((s.phase == WalkPhase.RECORDING || s.phase == WalkPhase.PAUSED) && s.points.size != lastSavedPoints) {
            lastSavedPoints = s.points.size
            val snap = recorder.snapshot(farmerId ?: return, landId, System.currentTimeMillis())
            repo.scope.launch { repo.saveWalkSnapshot(snap) }
        }
    }

    fun start(): Boolean {
        val ok = recorder.start()
        if (ok) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WalkService::class.java))
            onState(recorder.state)
        }
        return ok
    }

    fun pause() { recorder.pause(); onState(recorder.state) }
    fun resume(): Boolean = recorder.resume().also { onState(recorder.state) }

    fun finish(): CapturedBoundary? {
        val c = recorder.finish(System.currentTimeMillis()) ?: return null
        captured = c
        _state.value = recorder.state
        ctx.stopService(Intent(ctx, WalkService::class.java))
        return c
    }

    /** Throw the walk away (Back / Walk again). */
    fun discard() {
        captured = null
        repo.scope.launch { repo.saveWalkSnapshot(null) }
        val f = farmerId; val l = landId
        close()
        if (f != null) open(f, l, null)
    }

    fun close() {
        jobs.forEach { it.cancel() }
        if (jobs.isNotEmpty()) gps.release()
        jobs = emptyList()
        ctx.stopService(Intent(ctx, WalkService::class.java))
        farmerId = null
    }
}
