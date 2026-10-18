package com.buildx.ai.system

import android.os.Handler
import android.os.Looper

/** [Scheduler] on the main thread. */
object MainScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun after(ms: Long, task: () -> Unit) {
        handler.postDelayed(task, ms)
    }
}
