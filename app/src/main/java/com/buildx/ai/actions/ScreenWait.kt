package com.buildx.ai.actions

import android.content.Context
import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.a11y.ElKind
import com.buildx.ai.a11y.ScreenOps
import com.buildx.ai.llm.AndroidLlm
import com.buildx.ai.nlu.TextNormalizer
import com.buildx.ai.system.MainScheduler

/** Waits for a screen to show a button and presses it (Accessibility). Main thread only. */
internal object ScreenWait {

    private fun matches(label: String, q: String): Boolean {
        val l = TextNormalizer.normalize(label).trim()
        return l == q || " $l ".contains(" $q ")
    }

    /** true if a button with one of [queries] is on screen now (and, if [expectPkg] is set, that app is in front) */
    fun hasButton(svc: BuildXAccessibilityService, ctx: Context, queries: List<String>, expectPkg: String?): Boolean {
        val built = ScreenOps.capture(svc, ctx, allowSensitive = true) ?: return false
        if (expectPkg != null && built.snapshot.pkg != expectPkg) return false
        val qs = queries.map { TextNormalizer.normalize(it).trim() }
        return built.snapshot.elements.any { e -> e.kind == ElKind.BUTTON && qs.any { matches(e.label, it) } }
    }

    /** looks every [gapMs] up to [tries] times; [done] gets true once a matching button was pressed */
    fun tapButton(
        ctx: Context,
        svc: BuildXAccessibilityService,
        queries: List<String>,
        tries: Int,
        gapMs: Long,
        expectPkg: String?,
        done: (Boolean) -> Unit
    ) {
        val qs = queries.map { TextNormalizer.normalize(it).trim() }
        var left = tries
        lateinit var attempt: () -> Unit
        attempt = {
            val built = ScreenOps.capture(svc, ctx, allowSensitive = true)
            val inFront = built != null && (expectPkg == null || built.snapshot.pkg == expectPkg)
            val hit = if (inFront) built!!.snapshot.elements.firstOrNull { e -> e.kind == ElKind.BUTTON && qs.any { matches(e.label, it) } } else null
            val node = hit?.let { built!!.nodes[it.id] }
            if (node != null) {
                ScreenOps.tap(svc, node) { ok -> AndroidLlm.runUi { done(ok) } }
            } else if (--left <= 0) {
                done(false)
            } else {
                MainScheduler.after(gapMs) { attempt() }
            }
        }
        MainScheduler.after(gapMs) { attempt() }
    }
}
