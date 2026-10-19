package com.buildx.ai.actions

import android.content.Context
import java.util.Calendar

/** Everything an action needs. [lang] is a tag like "bn-BD" or "en-US". */
class ActionContext(val context: Context, val lang: String) {
    val bn: Boolean get() = lang.startsWith("bn")
}

interface Action {
    val name: String
    fun execute(ac: ActionContext, params: Map<String, String>): ActionResult
}

object ActionRegistry {
    private val map = LinkedHashMap<String, Action>()

    init {
        register(TimeAction)
        register(EchoAction)
        register(UnknownAction)
        register(CancelAction)
        register(CallAction)
        register(WhatsAppCallAction)
        register(PhotoAction)
        register(FlashlightAction)
        register(VolumeAction)
        register(OpenAppAction)
        register(AlarmAction)
        register(WifiAction)
        register(BluetoothAction)
        register(ScreenWakeAction)
        register(ScreenLockAction)
    }

    fun register(a: Action) {
        map[a.name] = a
    }

    fun get(name: String): Action? = map[name]

    fun names(): List<String> = map.keys.toList()
}

// ---------- Phase 1 dummy actions (real ones come in Phase 3/4) ----------

object TimeAction : Action {
    override val name = "time"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val cal = Calendar.getInstance()
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return if (ac.bn) {
            ActionResult(true, "এখন সময় ${toBanglaDigits(h12)}টা ${toBanglaDigits(m)} মিনিট")
        } else {
            ActionResult(true, "It is $h12:${m.toString().padStart(2, '0')}")
        }
    }
}

object EchoAction : Action {
    override val name = "echo"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val text = params["text"] ?: ""
        return if (ac.bn) {
            ActionResult(true, "আপনি বলেছেন: $text")
        } else {
            ActionResult(true, "You said: $text")
        }
    }
}

/** The parser could not tell what was meant. */
object UnknownAction : Action {
    override val name = "unknown"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val text = params["text"] ?: ""
        return if (ac.bn) {
            ActionResult(false, "বুঝতে পারিনি: $text")
        } else {
            ActionResult(false, "I didn't understand: $text")
        }
    }
}

/** "থাক", "cancel": nothing to do. */
object CancelAction : Action {
    override val name = "cancel"

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult =
        ActionResult(true, if (ac.bn) "ঠিক আছে" else "Okay")
}

fun toBanglaDigits(n: Int): String =
    n.toString().map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")
