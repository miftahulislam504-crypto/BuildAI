package com.buildx.ai

import android.app.Application
import com.buildx.ai.device.CrashLog

/** Runs in every process of the app (activity and service), so crashes in either are recorded. */
class BuildXApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
