package org.fieldproof

import android.app.Application
import org.fieldproof.data.Repository
import org.fieldproof.location.GpsTracker
import org.fieldproof.location.WalkController
import org.fieldproof.map.initMaps

class FieldproofApp : Application() {
    lateinit var repo: Repository; private set
    lateinit var gps: GpsTracker; private set
    lateinit var walk: WalkController; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = Repository(this)
        gps = GpsTracker(this)
        walk = WalkController(this, gps, repo)
        initMaps(this)
        if (repo.state.value.outbox.isNotEmpty()) repo.scheduleSync()
    }

    companion object {
        lateinit var instance: FieldproofApp; private set
    }
}
