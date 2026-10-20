package com.buildx.ai.llm

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.buildx.ai.LogStore
import com.buildx.ai.agent.AgentBackend
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

/** The Android side: settings in SharedPreferences, a background thread for the call, the main thread for the answer. */
object AndroidLlm {
    const val KEY_ENABLED = "llmEnabled"
    const val KEY_URL = "llmUrl"
    const val KEY_TOKEN = "llmToken"
    const val KEY_AGENT = "agentEnabled"

    private val ioExec = Executors.newSingleThreadExecutor { r -> Thread(r, "buildx-llm").apply { isDaemon = true } }
    private val uiHandler = Handler(Looper.getMainLooper())

    fun runIo(block: () -> Unit) { ioExec.execute(block) }
    fun runUi(block: () -> Unit) { uiHandler.post(block) }
    fun runUiLater(ms: Long, block: () -> Unit) { uiHandler.postDelayed(block, ms) }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("buildx", Context.MODE_PRIVATE)

    /** null = the feature is off (the default) or not set up properly: callers behave as if it did not exist */
    fun fallback(ctx: Context): LlmFallback? {
        val p = prefs(ctx)
        val cfg = LlmConfig.of(
            p.getBoolean(KEY_ENABLED, false), p.getString(KEY_URL, "") ?: "", p.getString(KEY_TOKEN, "") ?: ""
        ) ?: return null
        return LlmFallback(
            backend = HttpLlmBackend(cfg.url, cfg.token),
            io = { runIo(it) },
            main = { runUi(it) },
            log = { LogStore.add(ctx, it) },
            now = { nowIso() }
        )
    }

    /** "Test connection" button: one real round trip, result reported on the main thread. Works even while the feature is off. */
    fun test(ctx: Context, done: (String) -> Unit) {
        val p = prefs(ctx)
        val cfg = LlmConfig.of(true, p.getString(KEY_URL, "") ?: "", p.getString(KEY_TOKEN, "") ?: "")
            ?: return done("✗ ঠিকানা ঠিক নেই: https:// দিয়ে শুরু হতে হবে")
        runIo {
            val said = "ফ্ল্যাশলাইট জ্বালাও"
            val out = HttpLlmBackend(cfg.url, cfg.token).plan(LlmRequest(said, "bn-BD", nowIso()))
            val text = when (out) {
                is LlmOutcome.Planned ->
                    "✓ সংযোগ ঠিক আছে। \"$said\" -> " +
                        (out.plan.steps.joinToString(" | ") { it.intent }.ifEmpty { "say: ${out.plan.say}" })
                is LlmOutcome.Failed -> "✗ ${out.why} ${out.detail}"
            }
            runUi { done(text) }
        }
    }

    /** the screen agent is a separate switch: it sends what is on the screen, not just what was said */
    fun agentOn(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean(KEY_AGENT, false) &&
            LlmConfig.of(true, p.getString(KEY_URL, "") ?: "", p.getString(KEY_TOKEN, "") ?: "") != null
    }

    /** null = the agent is off or the server is not set up */
    fun agentBackend(ctx: Context): AgentBackend? {
        if (!agentOn(ctx)) return null
        val p = prefs(ctx)
        val cfg = LlmConfig.of(true, p.getString(KEY_URL, "") ?: "", p.getString(KEY_TOKEN, "") ?: "") ?: return null
        return HttpLlmBackend(cfg.url, cfg.token, readMs = 25_000)
    }

    fun nowIso(): String =
        OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
