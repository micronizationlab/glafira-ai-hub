package com.micronizationlab.glafira

import android.app.Application

/**
 * Owns one process-wide Core runtime instance. UI, workers and Bridge adapters must reuse it.
 */
class GlafiraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlafiraCoreRuntimeOwner.initialize(this)
    }
}
