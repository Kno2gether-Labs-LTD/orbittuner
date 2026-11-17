package dev.avijit.orbittuner

import android.app.Application
import android.util.Log
import dev.avijit.orbittuner.data.SyncScheduler

class OrbitTunerApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Sync on start and every 6 h. WorkManager may be absent in some test environments.
        try { SyncScheduler.onAppStart(this) } catch (e: IllegalStateException) { Log.w("OrbitTunerApp", "sync not scheduled", e) }
    }
}
