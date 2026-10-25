package com.buildx.ai.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.buildx.ai.LogStore
import com.buildx.ai.a11y.BuildXAccessibilityService
import com.buildx.ai.a11y.ScreenOps
import com.buildx.ai.a11y.ScreenSnapshot
import com.buildx.ai.a11y.UiNode
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.llm.AndroidLlm

/** Reads the screen through the Accessibility service and remembers which node is behind which element number. */
class AndroidScreenPort(private val svc: BuildXAccessibilityService, private val ctx: Context) : ScreenPort {
    @Volatile var nodes: Map<Int, UiNode> = emptyMap()
        private set

    override fun capture(): ScreenSnapshot? {
        val built = ScreenOps.capture(svc, ctx) ?: return null
        nodes = built.nodes
        return built.snapshot
    }
}

/** Carries out steps with the Accessibility service. Every callback comes back on the main thread. */
class AndroidUiPort(private val svc: BuildXAccessibilityService, private val port: AndroidScreenPort) : UiPort {

    override fun perform(step: AgentStep, done: (Boolean, String) -> Unit) {
        when (step) {
            is AgentStep.Tap -> {
                val node = port.nodes[step.id] ?: return done(false, "no such element")
                ScreenOps.tap(svc, node) { ok -> AndroidLlm.runUi { done(ok, if (ok) "" else "tap not accepted") } }
            }
            is AgentStep.Type -> {
                val field = step.id?.let { port.nodes[it] } ?: ScreenOps.focusedField(svc)
                val ok = ScreenOps.typeInto(field, step.text)
                done(ok, if (ok) "" else "no editable field to type into")
            }
            is AgentStep.Scroll -> ScreenOps.scroll(svc, step.down) { ok -> AndroidLlm.runUi { done(ok, if (ok) "" else "could not scroll") } }
            AgentStep.Back -> done(svc.goBack(), "")
            AgentStep.Home -> done(svc.goHome(), "")
            AgentStep.Wait -> done(true, "")
            is AgentStep.Done, is AgentStep.Fail -> done(false, "not an action")
        }
    }
}

/** The one running task, so the notification button (and a new task) can stop it. */
object AgentRunner {
    @Volatile var current: UiAgent? = null

    fun start(ac: ActionContext, svc: BuildXAccessibilityService, goal: String, backend: AgentBackend): UiAgent {
        current?.cancel()
        val ctx = ac.context.applicationContext
        val screen = AndroidScreenPort(svc, ctx)
        lateinit var agent: UiAgent
        agent = UiAgent(
            goal = goal, lang = ac.lang, bn = ac.bn,
            screen = screen, ui = AndroidUiPort(svc, screen), backend = backend,
            io = { AndroidLlm.runIo(it) }, main = { AndroidLlm.runUi(it) },
            later = { ms, block -> AndroidLlm.runUiLater(ms, block) },
            clock = { System.currentTimeMillis() }, nowIso = { AndroidLlm.nowIso() },
            log = { LogStore.add(ctx, it) },
            onFinish = {
                if (current === agent) current = null
                AgentNotifier.hide(ctx)
            }
        )
        current = agent
        AgentNotifier.show(ctx, goal)
        return agent
    }
}

/** A heads-up notification with a stop button for as long as the agent is operating the phone. */
object AgentNotifier {
    private const val CHANNEL = "buildx_agent"
    private const val ID = 4207
    const val ACTION_STOP = "com.buildx.ai.AGENT_STOP"

    fun show(ctx: Context, goal: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "BuildX screen agent", NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AgentStopReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("BuildX is operating the phone")
            .setContentText(goal.take(80))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setTimeoutAfter(UiAgent.MAX_MILLIS + 30_000) // never left behind if a task is abandoned
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, android.R.drawable.ic_delete), "Stop", stop).build())
            .build()
        nm.notify(ID, n)
    }

    fun hide(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }
}

class AgentStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentRunner.current?.cancel()
        AgentNotifier.hide(context)
        LogStore.add(context, "AGENT stopped (from the notification)")
    }
}
