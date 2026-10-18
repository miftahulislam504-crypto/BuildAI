package com.buildx.ai.system

/** Runs [task] after a delay. The Android one is a main-thread Handler; tests use a fake clock. */
interface Scheduler {
    fun after(ms: Long, task: () -> Unit)
}

enum class Verdict { YES, NO, UNKNOWN }

/**
 * "Did it really happen?": ask the phone (via [probe]) every [intervalMs] until it says YES, up to [maxTries] times.
 * Ends with YES as soon as seen; otherwise with the last answer: NO (it clearly did not happen) or UNKNOWN
 * (no way to tell, e.g. the Accessibility service is off).
 */
object Verify {
    fun poll(sched: Scheduler, intervalMs: Long, maxTries: Int, probe: () -> Verdict, done: (Verdict) -> Unit) {
        fun attempt(left: Int) {
            val v = probe()
            if (v == Verdict.YES || left <= 1) {
                done(v)
            } else if (v == Verdict.UNKNOWN && left == maxTries) {
                // cannot be checked at all: do not make the person wait
                done(Verdict.UNKNOWN)
            } else {
                sched.after(intervalMs) { attempt(left - 1) }
            }
        }
        sched.after(intervalMs) { attempt(maxTries) }
    }
}
