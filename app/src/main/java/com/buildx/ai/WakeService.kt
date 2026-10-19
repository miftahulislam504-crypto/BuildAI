package com.buildx.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.quicksettings.TileService
import com.buildx.ai.assistant.AssistantBridge
import com.buildx.ai.assistant.BuildXVoiceInteractionService
import com.buildx.ai.call.CallTracker
import com.buildx.ai.call.CallWatcher
import com.buildx.ai.voice.Conversation
import com.buildx.ai.voice.Speaker
import com.buildx.ai.wake.ModelManager
import com.buildx.ai.wake.VoskWakeEngine
import com.buildx.ai.wake.WakeEngine
import com.buildx.ai.wake.WakeSensitivity

/**
 * Flow: wake word -> vibrate -> Conversation (prompt, STT, command, spoken result) -> resume wake word.
 * The wake engine is paused for the whole conversation so the mic is free for STT and the engine
 * never hears our own TTS.
 */
class WakeService : Service() {

    companion object {
        const val ACTION_STOP = "com.buildx.ai.STOP"
        private const val CHANNEL_ID = "buildx_listen"
        private const val NOTIF_ID = 1
        private const val BATTERY_LOG_MS = 10 * 60 * 1000L

        @Volatile
        var running = false

        @Volatile
        var instance: WakeService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var engine: WakeEngine? = null
    private var conversation: Conversation? = null
    private var speaker: Speaker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var detections = 0
    private var commands = 0
    private var startedAt = 0L
    private var lang = "bn-BD"
    private var callWatcher: CallWatcher? = null
    private var callActive = false

    private val batteryTick = object : Runnable {
        override fun run() {
            logBattery()
            main.postDelayed(this, BATTERY_LOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be called every time startForegroundService() is used.
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (running) return START_NOT_STICKY
        running = true
        instance = this
        startedAt = SystemClock.elapsedRealtime()

        val prefs = getSharedPreferences("buildx", MODE_PRIVATE)
        lang = prefs.getString("lang", "bn-BD") ?: "bn-BD"
        val useWakeLock = prefs.getBoolean("wakeLock", true)

        // If the last run never reached onDestroy, the phone (or a crash) killed the service.
        if (prefs.getBoolean("svcActive", false)) {
            val kills = prefs.getInt("svcKills", 0) + 1
            prefs.edit().putInt("svcKills", kills).putBoolean("svcDied", true).apply()
            LogStore.add(this, "সার্ভিস গতবার নিজে বন্ধ হয়নি, অস্বাভাবিকভাবে বন্ধ হয়েছিল (এ পর্যন্ত $kills বার). ফোনের ব্যাকগ্রাউন্ড গাইড দেখুন")
        } else {
            prefs.edit().putBoolean("svcDied", false).apply()
        }
        prefs.edit().putBoolean("svcActive", true).apply()

        LogStore.add(
            this,
            "SERVICE START lang=$lang wakeLock=$useWakeLock " +
                "Android=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}"
        )

        if (!ModelManager.isReady(this)) {
            LogStore.add(this, "ERROR: wake word মডেল নামানো হয়নি")
            stopSelf()
            return START_NOT_STICKY
        }

        if (useWakeLock) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buildx:wake").apply { acquire() }
        }

        speaker = Speaker(this).also { it.init(lang) }
        startCallWatch()
        logBattery()
        main.postDelayed(batteryTick, BATTERY_LOG_MS)
        refreshTile()
        loadEngine()
        return START_NOT_STICKY
    }

    /** Loading the Vosk model is slow, so it happens off the main thread. */
    private fun loadEngine() {
        val appCtx = applicationContext
        val path = ModelManager.modelDir(appCtx).absolutePath
        LogStore.add(this, "মডেল লোড হচ্ছে...")
        Thread {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val eng = VoskWakeEngine(appCtx, path, WakeSensitivity.from(getSharedPreferences("buildx", MODE_PRIVATE).getInt("wakeSens", 1))) { main.post { onWake() } }
                val ms = SystemClock.elapsedRealtime() - t0
                main.post {
                    if (!running) {
                        eng.release()
                    } else {
                        engine = eng
                        eng.start()
                        LogStore.add(this, "LISTENING: Vosk চালু (মডেল লোড ${ms}ms)")
                    }
                }
            } catch (e: Throwable) {
                // Throwable on purpose: a missing native library is an Error, not an Exception.
                main.post {
                    LogStore.add(this, "ERROR মডেল লোড: ${e.javaClass.simpleName}: ${e.message}")
                    stopSelf()
                }
            }
        }.start()
    }

    // ---------- conversation flow ----------

    private fun onWake() {
        if (!running || conversation != null) return
        detections++
        LogStore.add(this, "WAKE #$detections")
        triggerConversation(showPopup = true)
    }

    /**
     * Start one conversation now (after "Hey BuildX", or from the assistant key). If BuildX is the phone's assistant
     * and the popup is enabled, a small popup with the last few lines is shown, even over the lock screen.
     */
    fun triggerConversation(showPopup: Boolean) {
        if (!running || conversation != null || callActive) return
        vibrate()
        engine?.pause() // frees the microphone

        val sp = speaker
        if (sp == null) {
            resumeWake()
            return
        }
        commands++
        val popup = getSharedPreferences("buildx", MODE_PRIVATE).getBoolean("assistPopup", true)
        if (showPopup && popup) {
            AssistantBridge.clear()
            BuildXVoiceInteractionService.instance?.show(fromWake = true)
        }
        val c = Conversation(this, lang, sp) { AssistantBridge.post(it) }
        conversation = c
        c.run {
            AssistantBridge.hideLater(2500)
            resumeWake()
        }
    }

    private fun resumeWake() {
        if (!running) return
        conversation = null
        if (callActive) {
            LogStore.add(this, "LISTENING: কল চলছে, কল শেষে চালু হবে")
            return
        }
        engine?.start()
        LogStore.add(this, "LISTENING: আবার চালু")
    }

    // ---------- phone calls ----------

    /** While any call is on, the wake engine stays off (the mic belongs to the call); it restarts when the call ends. */
    private fun startCallWatch() {
        val w = CallWatcher(this) { state -> onCallState(state) }
        if (w.start()) {
            callWatcher = w
            LogStore.add(this, "CALL WATCH চালু")
        } else {
            LogStore.add(this, "CALL WATCH বন্ধ (ফোনের অবস্থা দেখার পারমিশন নেই)")
        }
        CallTracker.shared.announce = { sentence -> main.post { speakThenResume(sentence) } }
    }

    private fun onCallState(state: Int) {
        if (!running) return
        val summary = CallTracker.shared.onState(state, System.currentTimeMillis())
        if (state == CallTracker.IDLE) {
            if (callActive) LogStore.add(this, "CALL শেষ")
            callActive = false
            if (summary != null) speakThenResume(summary) else restartEngineIfIdle()
        } else {
            if (!callActive) LogStore.add(this, "CALL ${if (state == CallTracker.RINGING) "ইনকামিং" else "চলছে/ডায়াল"}")
            callActive = true
            if (state == CallTracker.RINGING) {
                conversation?.cancel()
                conversation = null
            }
            engine?.pause()
        }
    }

    private fun speakThenResume(sentence: String) {
        val sp = speaker
        if (sp == null) {
            restartEngineIfIdle()
        } else {
            sp.speak(sentence) { main.post { restartEngineIfIdle() } }
        }
    }

    private fun restartEngineIfIdle() {
        if (!running || callActive || conversation != null) return
        engine?.start()
    }

    // ---------- helpers ----------

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun logBattery() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val mins = (SystemClock.elapsedRealtime() - startedAt) / 60000
        LogStore.add(this, "BATTERY $pct% charging=$charging elapsed=${mins}min wakes=$detections")
    }

    /** Asks the Quick Settings tile to re-read WakeService.running. */
    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, ListenTile::class.java))
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BuildX শোনা", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BuildX AI শুনছে")
            .setContentText("\"Hey BuildX\" বলুন")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "বন্ধ করুন", stopIntent).build())
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        conversation?.cancel()
        conversation = null
        callWatcher?.stop()
        callWatcher = null
        getSharedPreferences("buildx", MODE_PRIVATE).edit().putBoolean("svcActive", false).apply()
        CallTracker.shared.announce = null
        engine?.release()
        engine = null
        speaker?.shutdown()
        speaker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (running) {
            logBattery()
            LogStore.add(this, "SERVICE STOP wakes=$detections")
        }
        running = false
        instance = null
        refreshTile()
        super.onDestroy()
    }
}
