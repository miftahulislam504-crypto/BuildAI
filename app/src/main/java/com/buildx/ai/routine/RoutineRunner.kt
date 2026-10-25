package com.buildx.ai.routine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.os.Build
import com.buildx.ai.LogStore
import com.buildx.ai.WakeService
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.actions.ActionResult
import com.buildx.ai.nlu.CommandPipeline
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.system.Routine
import com.buildx.ai.system.RoutineBook
import com.buildx.ai.system.Trigger

/**
 * Starts a routine without anyone asking: the daily alarm, a Bluetooth device, the charger. Only steps that need nobody
 * to answer may run this way (no calls, no messages). What the steps say is spoken if the listening service is up,
 * otherwise it is left as a notification.
 */
object RoutineRunner {
    private const val CHANNEL = "buildx_routines"
    private val lastRun = HashMap<String, Long>()
    private const val DEBOUNCE_MS = 30_000L

    /** the first step that cannot run by itself, said in words; null if all can */
    fun unsafeStep(r: Routine, bn: Boolean): String? {
        for (text in r.steps) {
            for (p in IntentParser.parseChain(text)) {
                if (!RoutineBook.autoSafe(p.intent)) return com.buildx.ai.nlu.Describe.say(p, bn)
            }
        }
        return null
    }

    /** main thread only */
    fun run(ctx: Context, r: Routine, why: String) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val key = RoutineBook.key(r.name)
        val prev = lastRun[key] ?: 0L
        if (now - prev < DEBOUNCE_MS) {
            LogStore.add(app, "ROUTINE \"${r.name}\" skipped ($why): ran ${(now - prev) / 1000}s ago")
            return
        }
        lastRun[key] = now
        val lang = app.getSharedPreferences("buildx", Context.MODE_PRIVATE).getString("lang", "bn-BD") ?: "bn-BD"
        val ac = ActionContext(app, lang)
        val bad = unsafeStep(r, ac.bn)
        if (bad != null) {
            LogStore.add(app, "ROUTINE \"${r.name}\" not run ($why): \"$bad\" needs a person")
            tell(app, ac.bn, r.name, if (ac.bn) "‘$bad’ ধাপটা নিজে নিজে চালানো যায় না" else "the step \"$bad\" can't run by itself")
            return
        }
        LogStore.add(app, "ROUTINE \"${r.name}\" starts ($why)")
        val first = try {
            CommandPipeline.runRoutine(ac, r)
        } catch (e: Exception) {
            LogStore.add(app, "ROUTINE error: ${e.message}")
            return
        }
        drain(app, ac.bn, r.name, first, StringBuilder())
    }

    private fun drain(ctx: Context, bn: Boolean, name: String, r: ActionResult, said: StringBuilder) {
        if (r.message.isNotBlank()) {
            if (said.isNotEmpty()) said.append(". ")
            said.append(r.message)
            speak(ctx, r.message)
        }
        val later = r.deferred
        when {
            later != null -> later.run { next -> drain(ctx, bn, name, next, said) }
            r.followUp != null -> {
                LogStore.add(ctx, "ROUTINE \"$name\" stopped: a step wanted an answer")
                tell(ctx, bn, name, if (bn) "একটা ধাপ উত্তর চাইছিল, তাই থেমে গেছি" else "a step wanted an answer, so I stopped")
            }
            else -> {
                LogStore.add(ctx, "ROUTINE \"$name\" done ok=${r.success}")
                // nobody heard it if the service is not running: keep the words as a notification
                if (WakeService.instance == null) tell(ctx, bn, name, said.toString())
            }
        }
    }

    private fun speak(ctx: Context, text: String) {
        WakeService.instance?.announce(text)
    }

    private fun tell(ctx: Context, bn: Boolean, name: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "BuildX routines", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (bn) "রুটিন: $name" else "Routine: $name")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(20_000 + (RoutineBook.key(name).hashCode() and 0xFFFF), n)
        } catch (e: Exception) {
            LogStore.add(ctx, "ROUTINE notify failed: ${e.message}")
        }
    }
}

/** The daily alarm went off: run the routine, then set tomorrow's alarm. */
class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(RoutineStore.EXTRA_NAME) ?: return
        val r = RoutineStore.load(context).firstOrNull { RoutineBook.key(it.name) == RoutineBook.key(name) } ?: return
        if (r.trigger is Trigger.Daily) {
            RoutineStore.arm(context, r)
            RoutineRunner.run(context, r, "daily")
        }
    }
}

/**
 * Listens for a Bluetooth device connecting or disconnecting and the charger being plugged in or out, while the
 * listening service is running (the service registers it). It is not a manifest receiver on purpose: the system
 * does not deliver these broadcasts to apps that are not running.
 */
class RoutineEventReceiver : BroadcastReceiver() {

    companion object {
        fun register(ctx: Context): RoutineEventReceiver {
            val r = RoutineEventReceiver()
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            // all four are protected system broadcasts: no other app can send them
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
            return r
        }

        fun unregister(ctx: Context, r: RoutineEventReceiver?) {
            if (r != null) try { ctx.unregisterReceiver(r) } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val routines = RoutineStore.load(context)
        if (routines.none { it.trigger is Trigger.Bluetooth || it.trigger is Trigger.Charger }) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val addr = dev?.address ?: return
                val connect = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                for (r in routines) {
                    val t = r.trigger as? Trigger.Bluetooth ?: continue
                    if (t.address.equals(addr, ignoreCase = true) && t.connect == connect) RoutineRunner.run(context, r, "bluetooth ${t.name}")
                }
            }
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                val plugged = intent.action == Intent.ACTION_POWER_CONNECTED
                for (r in routines) {
                    val t = r.trigger as? Trigger.Charger ?: continue
                    if (t.plugged == plugged) RoutineRunner.run(context, r, if (plugged) "charger in" else "charger out")
                }
            }
        }
    }
}
