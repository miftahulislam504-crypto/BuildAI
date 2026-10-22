package com.buildx.ai.actions

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.buildx.ai.LogStore
import com.buildx.ai.nlu.CommandPipeline
import com.buildx.ai.nlu.Describe
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.Reply
import com.buildx.ai.nlu.TextNormalizer
import com.buildx.ai.routine.RoutineRunner
import com.buildx.ai.routine.RoutineStore
import com.buildx.ai.system.ConversationMemory
import com.buildx.ai.system.Routine
import com.buildx.ai.system.RoutineBook
import com.buildx.ai.system.Trigger

private fun ActionContext.t(bnText: String, enText: String) = if (bn) bnText else enText

/**
 * Routines: "রুটিন বানাও গুড মর্নিং" (then say the steps one by one, "শেষ" to finish), "গুড মর্নিং রুটিন চালাও" (or just
 * say its name), "রুটিনগুলো কী কী", "গুড মর্নিং রুটিন মুছে ফেলো", and the automatic starts: every day at a time,
 * when a Bluetooth device connects, when the charger goes in.
 */
object RoutineAction : Action {
    override val name = "routine"

    private fun list(ac: ActionContext) = RoutineStore.load(ac.context)

    override fun execute(ac: ActionContext, params: Map<String, String>): ActionResult {
        val said = params["name"].orEmpty().trim()
        return when (params["op"]) {
            "list" -> ActionResult(true, RoutineBook.sayList(list(ac), ac.bn))
            "delete" -> withRoutine(ac, said) { delete(ac, it) }
            "untrigger" -> withRoutine(ac, said) { untrigger(ac, it) }
            "schedule" -> withRoutine(ac, said) { schedule(ac, it, params) }
            "power" -> withRoutine(ac, said) { power(ac, it, params["event"] == "plug") }
            "bt" -> withRoutine(ac, said) { bluetooth(ac, it, params["event"] != "disconnect") }
            "save" -> save(ac, said, params["source"] == "last")
            else -> withRoutine(ac, said) { run(ac, it) }
        }
    }

    // ---------- finding the routine ----------

    private fun withRoutine(ac: ActionContext, said: String, then: (Routine) -> ActionResult): ActionResult {
        val all = list(ac)
        if (all.isEmpty()) return ActionResult(false, ac.t("কোনো রুটিন নেই। \"রুটিন বানাও\" বলুন", "You have no routines. Say \"create a routine\""))
        if (said.isEmpty()) {
            return ActionResult(true, ac.t("কোন রুটিন? ${all.joinToString("। ") { it.name }}", "Which routine? ${all.joinToString(". ") { it.name }}"), FollowUp { reply ->
                val r = reply?.let { RoutineBook.match(all, TextNormalizer.normalize(it)) }
                if (r != null) then(r) else ActionResult(false, ac.t("সেই নামে রুটিন পাইনি", "I don't have a routine by that name"))
            })
        }
        val r = RoutineBook.match(all, TextNormalizer.normalize(said))
            ?: return ActionResult(false, ac.t("‘$said’ নামে রুটিন নেই। আছে: ${all.joinToString("। ") { it.name }}", "No routine called \"$said\". You have: ${all.joinToString(". ") { it.name }}"))
        return then(r)
    }

    // ---------- run / delete ----------

    private fun run(ac: ActionContext, r: Routine): ActionResult {
        LogStore.add(ac.context, "ROUTINE run \"${r.name}\" (${r.steps.size} steps)")
        return CommandPipeline.runRoutine(ac, r)
    }

    private fun delete(ac: ActionContext, r: Routine): ActionResult =
        ActionResult(true, ac.t("‘${r.name}’ রুটিন মুছে ফেলব?", "Delete the routine \"${r.name}\"?"), FollowUp { reply ->
            when (Reply.yesNo(reply)) {
                Reply.YesNo.YES -> {
                    RoutineStore.cancel(ac.context, r.name)
                    RoutineStore.save(ac.context, RoutineBook.remove(list(ac), r.name))
                    ActionResult(true, ac.t("‘${r.name}’ মুছে ফেলেছি", "Deleted \"${r.name}\""))
                }
                Reply.YesNo.NO -> ActionResult(true, ac.t("ঠিক আছে, রেখে দিলাম", "Okay, keeping it"))
                Reply.YesNo.UNKNOWN -> ActionResult(false, ac.t("বুঝতে পারিনি, মুছিনি", "Didn't get that, not deleting"))
            }
        })

    // ---------- automatic starts ----------

    private fun setTrigger(ac: ActionContext, r: Routine, t: Trigger?): Routine {
        val updated = r.copy(trigger = t)
        RoutineStore.save(ac.context, RoutineBook.withTrigger(list(ac), r.name, t))
        RoutineStore.arm(ac.context, updated)
        return updated
    }

    /** a trigger may only be put on a routine whose steps need nobody to answer */
    private fun refuseIfUnsafe(ac: ActionContext, r: Routine): ActionResult? {
        val bad = RoutineRunner.unsafeStep(r, ac.bn) ?: return null
        return ActionResult(false, ac.t("‘${r.name}’-এ \"$bad\" ধাপটা আছে, ওটা নিজে নিজে চালানো যায় না (কাউকে উত্তর দিতে হয়)। তাই অটো-স্টার্ট দিলাম না", "\"${r.name}\" has the step \"$bad\", which can't run by itself (someone has to answer). No automatic start set"))
    }

    private fun untrigger(ac: ActionContext, r: Routine): ActionResult {
        if (r.trigger == null) return ActionResult(true, ac.t("‘${r.name}’-এর কোনো অটো-স্টার্ট নেই", "\"${r.name}\" has no automatic start"))
        setTrigger(ac, r, null)
        return ActionResult(true, ac.t("‘${r.name}’-এর অটো-স্টার্ট বন্ধ করেছি", "Automatic start of \"${r.name}\" turned off"))
    }

    private fun schedule(ac: ActionContext, r: Routine, p: Map<String, String>): ActionResult {
        refuseIfUnsafe(ac, r)?.let { return it }
        val h = p["hour"]?.toIntOrNull() ?: return ActionResult(false, ac.t("সময়টা বুঝতে পারিনি", "I didn't get the time"))
        val m = p["minute"]?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return ActionResult(false, ac.t("সময়টা ঠিক নেই", "That time isn't valid"))
        // "৭টা" with no am/pm: the parser flags it; a routine at 7 in the evening is rare, so the morning reading wins below 12
        val t = Trigger.Daily(h, m)
        setTrigger(ac, r, t)
        return ActionResult(true, ac.t("ঠিক আছে, ${RoutineBook.sayTrigger(t, true)} ‘${r.name}’ চালাব", "Okay, I'll run \"${r.name}\" ${RoutineBook.sayTrigger(t, false)}"))
    }

    private fun power(ac: ActionContext, r: Routine, plug: Boolean): ActionResult {
        refuseIfUnsafe(ac, r)?.let { return it }
        val t = Trigger.Charger(plug)
        setTrigger(ac, r, t)
        return ActionResult(true, ac.t("ঠিক আছে, ${RoutineBook.sayTrigger(t, true)} ‘${r.name}’ চালাব। এটা শুধু তখনই হবে যখন BuildX-এর লিসেনিং সার্ভিস চালু থাকে", "Okay, I'll run \"${r.name}\" ${RoutineBook.sayTrigger(t, false)}. This only works while the BuildX listening service is running"))
    }

    private fun bluetooth(ac: ActionContext, r: Routine, connect: Boolean): ActionResult {
        refuseIfUnsafe(ac, r)?.let { return it }
        val ctx = ac.context.applicationContext
        if (Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult(false, ac.t("ব্লুটুথের পারমিশন নেই। অ্যাপ খুলে \"কল ও কন্টাক্ট\" বাটন চেপে পারমিশন দিন", "No Bluetooth permission. Open the app and allow it"))
        }
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return ActionResult(false, ac.t("এই ফোনে ব্লুটুথ পাইনি", "No Bluetooth on this phone"))
        val devices = try {
            adapter.bondedDevices.map { (it.name ?: it.address) to it.address }.sortedBy { it.first.lowercase() }.take(8)
        } catch (e: Exception) {
            return ActionResult(false, ac.t("ব্লুটুথ ডিভাইসের তালিকা পাইনি", "Couldn't read the Bluetooth devices"))
        }
        if (devices.isEmpty()) return ActionResult(false, ac.t("কোনো পেয়ার করা ব্লুটুথ ডিভাইস নেই", "No paired Bluetooth devices"))
        fun num(n: Int) = if (ac.bn) n.toString().map { '০' + (it - '0') }.joinToString("") else n.toString()
        val menu = devices.mapIndexed { i, d -> "${num(i + 1)}. ${d.first}" }.joinToString(if (ac.bn) "। " else ". ")
        return ActionResult(true, ac.t("কোন ডিভাইস? $menu", "Which device? $menu"), FollowUp { reply ->
            val i = Reply.pick(reply, devices.size)
            if (i == null) ActionResult(false, ac.t("বুঝতে পারিনি, অটো-স্টার্ট দিইনি", "Didn't get that, no automatic start set"))
            else {
                val t = Trigger.Bluetooth(devices[i].second, devices[i].first, connect)
                setTrigger(ac, r, t)
                ActionResult(true, ac.t("ঠিক আছে, ${RoutineBook.sayTrigger(t, true)} ‘${r.name}’ চালাব। এটা শুধু তখনই হবে যখন BuildX-এর লিসেনিং সার্ভিস চালু থাকে", "Okay, I'll run \"${r.name}\" ${RoutineBook.sayTrigger(t, false)}. This only works while the BuildX listening service is running"))
            }
        })
    }

    // ---------- making one ----------

    private fun save(ac: ActionContext, said: String, fromLast: Boolean): ActionResult =
        RoutineBuilder(
            bn = ac.bn,
            load = { list(ac) },
            store = { RoutineStore.save(ac.context, it) },
            memory = ConversationMemory.shared,
            log = { LogStore.add(ac.context, it) }
        ).start(said, fromLast)
}
