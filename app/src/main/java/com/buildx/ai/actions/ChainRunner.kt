package com.buildx.ai.actions

import com.buildx.ai.nlu.Describe
import com.buildx.ai.nlu.Parsed
import com.buildx.ai.system.Scheduler

/**
 * Runs the steps of "ইউটিউব খুলে ভলিউম কমাও" one after another. Each step's own sentence is spoken, then the next
 * step starts after a short gap. A step may ask a question or finish later; the chain waits for it. The first step
 * that fails stops the chain, and the spoken result says which step failed and how many were skipped.
 * No Android classes: [executor] runs one step, [scheduler] makes the gaps.
 */
class ChainRunner(
    private val bn: Boolean,
    private val executor: (Parsed) -> ActionResult,
    private val scheduler: Scheduler,
    private val gapAfter: (Parsed) -> Long = { defaultGap(it) }
) {
    companion object {
        fun defaultGap(p: Parsed): Long = when (p.intent) {
            "open_app", "wifi", "bluetooth", "screen_wake" -> 1500L // let the screen / app come up first
            else -> 400L
        }
    }

    private fun t(bnText: String, enText: String) = if (bn) bnText else enText
    private fun num(n: Int) = if (bn) n.toString().map { '০' + (it - '0') }.joinToString("") else n.toString()

    fun start(steps: List<Parsed>): ActionResult = step(0, steps)

    private fun step(i: Int, steps: List<Parsed>): ActionResult {
        val r = try {
            executor(steps[i])
        } catch (e: Exception) {
            ActionResult(false, t("কাজটি করতে পারিনি", "That failed"))
        }
        return track(i, steps, r)
    }

    private fun track(i: Int, steps: List<Parsed>, r: ActionResult): ActionResult {
        val ask = r.followUp
        if (ask != null) {
            return r.copy(followUp = FollowUp { reply ->
                val next = try {
                    ask.onReply(reply)
                } catch (e: Exception) {
                    ActionResult(false, t("কাজটি করতে পারিনি", "That failed"))
                }
                track(i, steps, next)
            })
        }
        val later = r.deferred
        if (later != null) {
            return r.copy(deferred = Deferred { report -> later.run { final -> report(track(i, steps, final)) } })
        }
        if (!r.success) return failed(i, steps, r)
        if (i == steps.size - 1) return r
        return ActionResult(true, r.message, deferred = Deferred { report ->
            scheduler.after(gapAfter(steps[i])) { report(step(i + 1, steps)) }
        })
    }

    private fun failed(i: Int, steps: List<Parsed>, r: ActionResult): ActionResult {
        val rest = steps.size - i - 1
        val where = t("ধাপ ${num(i + 1)}/${num(steps.size)}-এ আটকে গেছি", "Stopped at step ${num(i + 1)} of ${num(steps.size)}")
        val skipped = if (rest > 0) t(", বাকি ${num(rest)}টি ধাপ করিনি", ", skipped the remaining ${num(rest)}") else ""
        val what = Describe.say(steps[i], bn)
        return ActionResult(false, "${r.message}${if (bn) "। " else ". "}$where ($what)$skipped")
    }
}
