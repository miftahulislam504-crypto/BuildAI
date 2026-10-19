package com.buildx.ai.wake

import android.content.Context

/** Saved voice-print and the "only my voice" switch. All on this phone; nothing is sent anywhere. */
object VoiceLockStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("buildx", Context.MODE_PRIVATE)

    fun hasPrint(ctx: Context): Boolean = SpeakerMath.decode(prefs(ctx).getString("voicePrint", null)) != null

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("voiceLock", false)

    /** null = no lock (switched off, nothing enrolled yet, or no speaker model) */
    fun load(ctx: Context): VoiceLock? {
        val p = prefs(ctx)
        if (!p.getBoolean("voiceLock", false) || !ModelManager.isSpkReady(ctx)) return null
        val print = SpeakerMath.decode(p.getString("voicePrint", null)) ?: return null
        val base = p.getFloat("voiceBase", SpeakerMath.DEFAULT_BASE.toFloat()).toDouble()
        return VoiceLock(print, SpeakerMath.limit(base, VoiceStrictness.from(p.getInt("voiceStrict", 1))))
    }

    fun save(ctx: Context, print: DoubleArray, base: Double) {
        prefs(ctx).edit()
            .putString("voicePrint", SpeakerMath.encode(print))
            .putFloat("voiceBase", base.toFloat())
            .putBoolean("voiceLock", true)
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("voicePrint").remove("voiceBase").putBoolean("voiceLock", false).apply()
    }
}
