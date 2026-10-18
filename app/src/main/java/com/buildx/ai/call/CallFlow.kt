package com.buildx.ai.call

import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.FollowUp
import com.buildx.ai.contacts.ContactEntry
import com.buildx.ai.contacts.NameMatcher
import com.buildx.ai.contacts.PhoneEntry
import com.buildx.ai.contacts.PhoneNumbers
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.Reply

/** null = the app has no permission to read contacts */
interface ContactSource {
    fun all(): List<ContactEntry>?
}

data class PlaceOutcome(val ok: Boolean, val note: String? = null)

interface CallPlacer {
    /** [label] is what to call the person when reporting back (a name, or the digits) */
    fun place(number: String, label: String): PlaceOutcome
}

/**
 * The whole "call someone" conversation, with no Android classes so it can be unit tested:
 * find the contact -> ask which one if several -> ask which number if several -> confirm -> place the call.
 */
class CallFlow(
    private val bn: Boolean,
    private val contacts: ContactSource,
    private val placer: CallPlacer,
    private val nicknames: Map<String, String> = emptyMap(),
    private val confirm: Boolean = true,
    private val log: (String) -> Unit = {}
) {
    private fun t(bnText: String, enText: String) = if (bn) bnText else enText
    private fun fail(bnText: String, enText: String) = ActionResult(false, t(bnText, enText))
    private fun ask(bnText: String, enText: String, onReply: (String?) -> ActionResult) =
        ActionResult(true, t(bnText, enText), FollowUp(onReply))
    private fun objective(name: String) = if (bn) "${name}কে" else name
    private fun digits(n: String) = PhoneNumbers.spell(n, bn)

    fun start(target: String?, number: String?): ActionResult {
        if (!number.isNullOrBlank()) return byNumber(number)
        if (target.isNullOrBlank()) return askWho(retry = true)
        return byName(target)
    }

    // ---------- who ----------

    private fun askWho(retry: Boolean): ActionResult =
        ask("কাকে কল করব?", "Who should I call?") { reply ->
            if (reply.isNullOrBlank()) {
                fail("উত্তর পাইনি, কল করছি না", "No answer, not calling")
            } else if (Reply.yesNo(reply) == Reply.YesNo.NO) {
                fail("ঠিক আছে, কল করছি না", "Okay, not calling")
            } else {
                val slots = IntentParser.callTarget(reply)
                val num = slots["number"]
                val name = slots["target"]
                when {
                    num != null -> byNumber(num)
                    name != null -> byName(name)
                    retry -> askWho(retry = false)
                    else -> fail("বুঝতে পারিনি, কল করছি না", "Didn't get that, not calling")
                }
            }
        }

    private fun byNumber(raw: String): ActionResult {
        val n = PhoneNumbers.dialable(raw) ?: return fail("নম্বরটা ঠিক মনে হচ্ছে না", "That number doesn't look right")
        return confirmThenPlace(digits(n), n)
    }

    private fun byName(target: String): ActionResult {
        val all = contacts.all()
            ?: return fail(
                "কন্টাক্ট দেখার পারমিশন নেই। অ্যাপ খুলে পারমিশন দিন",
                "No permission to read contacts. Open the app and allow it"
            )
        val found = NameMatcher.find(target, all, nicknames)
        val group = NameMatcher.bestGroup(found)
        log("MATCH \"$target\" -> ${found.take(4).joinToString { "${it.contact.name}=${"%.2f".format(it.score)}" }}")
        return when {
            group.isEmpty() -> fail("$target নামে কন্টাক্ট পাইনি", "I couldn't find a contact named $target")
            group.size == 1 -> chooseNumber(group[0].contact)
            else -> askWhich(target, group.map { it.contact }, retry = true)
        }
    }

    private fun askWhich(target: String, options: List<ContactEntry>, retry: Boolean): ActionResult {
        val list = options.mapIndexed { i, c -> "${num(i + 1)}. ${c.name}" }.joinToString("। ")
        val bnQ = "$target নামে ${num(options.size)} জন আছে। $list। কাকে কল করব?"
        val enQ = "I found ${options.size} for $target. $list. Which one?"
        return ask(bnQ, enQ) { reply ->
            if (reply.isNullOrBlank()) {
                fail("উত্তর পাইনি, কল করছি না", "No answer, not calling")
            } else if (Reply.yesNo(reply) == Reply.YesNo.NO) {
                fail("ঠিক আছে, কল করছি না", "Okay, not calling")
            } else {
                val byIndex = Reply.pick(reply, options.size)
                val picked = if (byIndex != null) options[byIndex] else pickByName(reply, options)
                when {
                    picked != null -> chooseNumber(picked)
                    retry -> askWhich(target, options, retry = false)
                    else -> fail("বুঝতে পারিনি, কল করছি না", "Didn't get that, not calling")
                }
            }
        }
    }

    /** the user said part of a name ("উদ্দিন") instead of a number */
    private fun pickByName(reply: String, options: List<ContactEntry>): ContactEntry? {
        val name = IntentParser.callTarget(reply)["target"] ?: return null
        val g = NameMatcher.bestGroup(NameMatcher.find(name, options, nicknames))
        return if (g.size == 1) g[0].contact else null
    }

    // ---------- which number ----------

    private fun distinctNumbers(c: ContactEntry): List<PhoneEntry> {
        val out = ArrayList<PhoneEntry>()
        for (p in c.numbers) if (out.none { PhoneNumbers.same(it.number, p.number) }) out.add(p)
        return out
    }

    private fun labelWord(p: PhoneEntry): String = when (p.label) {
        "mobile" -> t("মোবাইল", "mobile")
        "home" -> t("বাসা", "home")
        "work" -> t("অফিস", "work")
        else -> t("অন্য", "other")
    }

    private fun chooseNumber(c: ContactEntry): ActionResult {
        val nums = distinctNumbers(c)
        if (nums.isEmpty()) return fail("${c.name}-এর কোনো নম্বর নেই", "${c.name} has no phone number")
        if (nums.size == 1) return confirmThenPlace(c.name, nums[0].number)
        return askNumber(c, nums, retry = true)
    }

    private fun askNumber(c: ContactEntry, nums: List<PhoneEntry>, retry: Boolean): ActionResult {
        val list = nums.mapIndexed { i, p -> "${num(i + 1)}. ${labelWord(p)} ${t("শেষ চার সংখ্যা", "ending")} ${digits(p.number.filter { it.isDigit() }.takeLast(4))}" }
            .joinToString("। ")
        return ask(
            "${c.name}-এর ${num(nums.size)}টি নম্বর। $list। কোনটায় কল করব?",
            "${c.name} has ${nums.size} numbers. $list. Which one?"
        ) { reply ->
            if (reply.isNullOrBlank()) {
                fail("উত্তর পাইনি, কল করছি না", "No answer, not calling")
            } else if (Reply.yesNo(reply) == Reply.YesNo.NO) {
                fail("ঠিক আছে, কল করছি না", "Okay, not calling")
            } else {
                val i = Reply.pick(reply, nums.size) ?: nums.indexOfFirst { labelMentioned(reply, it) }.takeIf { it >= 0 }
                when {
                    i != null -> confirmThenPlace(c.name, nums[i].number)
                    retry -> askNumber(c, nums, retry = false)
                    else -> fail("বুঝতে পারিনি, কল করছি না", "Didn't get that, not calling")
                }
            }
        }
    }

    private fun labelMentioned(reply: String, p: PhoneEntry): Boolean {
        val r = com.buildx.ai.nlu.TextNormalizer.normalize(reply)
        return when (p.label) {
            "mobile" -> "মোবাইল" in r || "mobile" in r
            "home" -> "বাসা" in r || "বাড়ি" in r || "home" in r
            "work" -> "অফিস" in r || "work" in r
            else -> false
        }
    }

    // ---------- confirm, then call ----------

    private fun confirmThenPlace(label: String, number: String): ActionResult {
        val emergency = PhoneNumbers.isEmergency(number)
        if (!confirm && !emergency) return place(label, number)
        return askYesNo(label, number, retry = true)
    }

    private fun askYesNo(label: String, number: String, retry: Boolean): ActionResult =
        ask("${objective(label)} কল করব?", "Call $label?") { reply ->
            when (Reply.yesNo(reply)) {
                Reply.YesNo.YES -> place(label, number)
                Reply.YesNo.NO -> fail("ঠিক আছে, কল করছি না", "Okay, not calling")
                Reply.YesNo.UNKNOWN ->
                    if (reply.isNullOrBlank()) fail("উত্তর পাইনি, কল করছি না", "No answer, not calling")
                    else if (retry) askYesNo(label, number, retry = false)
                    else fail("বুঝতে পারিনি, কল করছি না", "Didn't get that, not calling")
            }
        }

    private fun place(label: String, number: String): ActionResult {
        val out = placer.place(number, label)
        log("CALL $label ($number) ok=${out.ok} ${out.note ?: ""}")
        return if (out.ok) {
            ActionResult(true, out.note ?: t("${objective(label)} কল করছি", "Calling $label"))
        } else {
            ActionResult(false, out.note ?: t("কল করতে পারিনি", "I couldn't place the call"))
        }
    }

    private fun num(n: Int): String = if (bn) n.toString().map { '০' + (it - '0') }.joinToString("") else n.toString()
}
