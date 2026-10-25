package com.buildx.ai.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import com.buildx.ai.LogStore
import com.buildx.ai.WakeService
import com.buildx.ai.wake.ModelManager

/**
 * What makes BuildX selectable as "Default digital assistant app". Once selected, the system keeps this service
 * bound (it starts with the phone), which lets us (1) show the popup over the lock screen on "Hey BuildX" and
 * (2) try to start the listening service by ourselves.
 * It does NOT give normal apps a built-in wake-word engine, and it cannot unlock the phone.
 */
class BuildXVoiceInteractionService : VoiceInteractionService() {

    companion object {
        @Volatile
        var instance: BuildXVoiceInteractionService? = null

        fun component(ctx: Context) = ComponentName(ctx, BuildXVoiceInteractionService::class.java)

        /** is BuildX the phone's current default assistant? */
        fun isActive(ctx: Context): Boolean = try {
            isActiveService(ctx, component(ctx))
        } catch (e: Exception) {
            false
        }
    }

    override fun onReady() {
        super.onReady()
        instance = this
        LogStore.add(this, "ASSISTANT ready (BuildX is now the default assistant)")
        autoStartListening()
    }

    override fun onShutdown() {
        instance = null
        LogStore.add(this, "ASSISTANT stopped")
        super.onShutdown()
    }

    /** Try to start the "Hey BuildX" listener without the person opening the app. The phone may refuse; that is logged. */
    private fun autoStartListening() {
        val prefs = getSharedPreferences("buildx", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("assistAutoStart", true) || WakeService.running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            LogStore.add(this, "ASSISTANT auto-start: no microphone permission")
            return
        }
        if (!ModelManager.isReady(this)) {
            LogStore.add(this, "ASSISTANT auto-start: wake model is not downloaded")
            return
        }
        try {
            startForegroundService(Intent(this, WakeService::class.java))
            LogStore.add(this, "ASSISTANT auto-start: asked the listening service to start")
        } catch (e: Exception) {
            LogStore.add(this, "ASSISTANT auto-start failed: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Throwable) {
            LogStore.add(this, "ASSISTANT auto-start failed: ${e.javaClass.simpleName}")
        }
    }

    /** show the popup (works from the background and over the lock screen when the system allows it) */
    fun show(fromWake: Boolean): Boolean = try {
        showSession(Bundle().apply { putBoolean("fromWake", fromWake) }, 0)
        true
    } catch (e: Exception) {
        LogStore.add(this, "ASSISTANT could not show the popup: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
