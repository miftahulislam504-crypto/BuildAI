package com.buildx.ai.call

import com.buildx.ai.actions.ActionResult
import com.buildx.ai.actions.FollowUp
import com.buildx.ai.contacts.ContactEntry
import com.buildx.ai.contacts.NameMatcher
import com.buildx.ai.contacts.PhoneEntry
import com.buildx.ai.contacts.PhoneNumbers
import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.Reply

/** Sends the text. May answer with a deferred result (SMS reports back when the network says it went). */
interface MessageSender {
    fun send(number: String, label: String, text: String): ActionResult
}

/**
 * "রহিমকে মেসেজ পাঠাও আমি আসছি": find the contact -> ask which one / which number -> ask what to write if missing ->
 * read the text back and ask "পাঠাব?" -> send. The read-back is NOT optional: a message goes out only after a spoken yes.
 * No Android classes.
 */
class MessageFlow(
    private val bn: Boolean,
    private val contacts: ContactSource,
    private val sender: MessageSender,
    private val nicknames: Map<String, String> = emptyMap(),
    /** how to say the channel: bn "এসএমএস" / "WhatsApp-এ", en "an SMS" / "on WhatsApp" */
    private val channelBn: String = "এসএমএস",
    private val channelEn: String = "by SMS",
    private val log: (String) -> Unit = {}
) {
    companion object {
        const val MAX_LEN = 500
    }

    private fun t(bnText: String, enText: String) = if (bn) bnText else enText
    private fun fail(bnText: String, enText: String) = ActionResult(false, t(bnText, enText))
    private fun ask(bnText: String, enText: String, onReply: (String?) -> ActionResult) =
        ActionResult(true, t(bnText, enText), FollowUp(onReply))
    private fun objective(name: String) = if (bn) "${name}কে" else name
    private fun digits(n: String) = PhoneNumbers.spell(n, bn)
    private fun num(n: Int): String = if (bn) n.toString().map { '০' + (it - '0') }.joinToString("") else n.toString()

    private val notSent get() = t("মেসেজ পাঠাইনি", "Not sent")

    fun start(target: String?, number: String?, text: String?): ActionResult {
        val body = text?.trim()?.takeIf { it.isNotEmpty() }
        if (!number.isNullOrBlank()) return byNumber(number, body)
        if (target.isNullOrBlank()) return askWho(body, retry = true)
        return byName(target, body)
    }

    // ---------- who ----------

    private fun askWho(body: String?, retry: Boolean): ActionResult =
        ask("কাকে মেসেজ পাঠাব?", "Who should I message?") { reply ->
            if (reply.isNullOrBlank()) fail("উত্তর পাইনি। $notSent", "No answer. $notSent")
            else if (Reply.yesNo(reply) == Reply.YesNo.NO) fail("ঠিক আছে। $notSent", "Okay. $notSent")
            else {
                val slots = IntentParser.callTarget(reply)
                when {
                    slots["number"] != null -> byNumber(slots["number"]!!, body)
                    slots["target"] != null -> byName(slots["target"]!!, body)
                    retry -> askWho(body, retry = false)
                    else -> fail("বুঝতে পারিনি। $notSent", "Didn't get that. $notSent")
                }
            }
        }

    private fun byNumber(raw: String, body: String?): ActionResult {
        val n = PhoneNumbers.dialable(raw) ?: return fail("নম্বরটা ঠিক মনে হচ্ছে না", "That number doesn't look right")
        return needText(digits(n), n, body)
    }

    private fun byName(target: String, body: String?): ActionResult {
        val all = contacts.all()
            ?: return fail("কন্টাক্ট দেখার পারমিশন নেই। অ্যাপ খুলে পারমিশন দিন", "No permission to read contacts. Open the app and allow it")
        val found = NameMatcher.find(target, all, nicknames)
        val group = NameMatcher.bestGroup(found)
        log("MATCH \"$target\" -> ${found.take(4).joinToString { "${it.contact.name}=${"%.2f".format(it.score)}" }}")
        return when {
            group.isEmpty() -> fail("$target নামে কন্টাক্ট পাইনি", "I couldn't find a contact named $target")
            group.size == 1 -> chooseNumber(group[0].contact, body)
            else -> askWhich(target, group.map { it.contact }, body, retry = true)
        }
    }

    private fun askWhich(target: String, options: List<ContactEntry>, body: String?, retry: Boolean): ActionResult {
        val list = options.mapIndexed { i, c -> "${num(i + 1)}. ${c.name}" }.joinToString("। ")
        return ask("$target নামে ${num(options.size)} জন আছে। $list। কাকে পাঠাব?", "I found ${options.size} for $target. $list. Which one?") { reply ->
            if (reply.isNullOrBlank()) fail("উত্তর পাইনি। $notSent", "No answer. $notSent")
            else if (Reply.yesNo(reply) == Reply.YesNo.NO) fail("ঠিক আছে। $notSent", "Okay. $notSent")
            else {
                val byIndex = Reply.pick(reply, options.size)
                val picked = if (byIndex != null) options[byIndex] else pickByName(reply, options)
                when {
                    picked != null -> chooseNumber(picked, body)
                    retry -> askWhich(target, options, body, retry = false)
                    else -> fail("বুঝতে পারিনি। $notSent", "Didn't get that. $notSent")
                }
            }
        }
    }

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

    private fun chooseNumber(c: ContactEntry, body: String?): ActionResult {
        val nums = distinctNumbers(c)
        if (nums.isEmpty()) return fail("${c.name}-এর কোনো নম্বর নেই", "${c.name} has no phone number")
        if (nums.size == 1) return needText(c.name, nums[0].number, body)
        return askNumber(c, nums, body, retry = true)
    }

    private fun askNumber(c: ContactEntry, nums: List<PhoneEntry>, body: String?, retry: Boolean): ActionResult {
        val list = nums.mapIndexed { i, p -> "${num(i + 1)}. ${t("শেষ চার সংখ্যা", "ending")} ${digits(p.number.filter { it.isDigit() }.takeLast(4))}" }.joinToString("। ")
        return ask("${c.name}-এর ${num(nums.size)}টি নম্বর। $list। কোনটায় পাঠাব?", "${c.name} has ${nums.size} numbers. $list. Which one?") { reply ->
            if (reply.isNullOrBlank()) fail("উত্তর পাইনি। $notSent", "No answer. $notSent")
            else if (Reply.yesNo(reply) == Reply.YesNo.NO) fail("ঠিক আছে। $notSent", "Okay. $notSent")
            else {
                val i = Reply.pick(reply, nums.size)
                when {
                    i != null -> needText(c.name, nums[i].number, body)
                    retry -> askNumber(c, nums, body, retry = false)
                    else -> fail("বুঝতে পারিনি। $notSent", "Didn't get that. $notSent")
                }
            }
        }
    }

    // ---------- what to write ----------

    private fun needText(label: String, number: String, body: String?): ActionResult {
        if (body != null) return confirm(label, number, body, retry = true)
        return ask("কী লিখব?", "What should I write?") { reply ->
            val said = reply?.trim().orEmpty()
            when {
                said.isEmpty() -> fail("উত্তর পাইনি। $notSent", "No answer. $notSent")
                Reply.yesNo(said) == Reply.YesNo.NO && said.split(' ').size <= 2 -> fail("ঠিক আছে। $notSent", "Okay. $notSent")
                else -> confirm(label, number, said, retry = true)
            }
        }
    }

    // ---------- read back, then send ----------

    private fun confirm(label: String, number: String, body: String, retry: Boolean): ActionResult {
        if (body.length > MAX_LEN) return fail("মেসেজটা অনেক বড়। $notSent", "That message is too long. $notSent")
        return ask(
            "${objective(label)} $channelBn লিখব: $body। পাঠাব?",
            "Message $label $channelEn: $body. Send it?"
        ) { reply ->
            when (Reply.yesNo(reply)) {
                Reply.YesNo.YES -> {
                    val r = sender.send(number, label, body)
                    log("MESSAGE to $label ($number) via $channelEn len=${body.length} ok=${r.success}")
                    r
                }
                Reply.YesNo.NO -> fail("ঠিক আছে। $notSent", "Okay. $notSent")
                Reply.YesNo.UNKNOWN ->
                    if (reply.isNullOrBlank()) fail("উত্তর পাইনি। $notSent", "No answer. $notSent")
                    else if (retry) confirm(label, number, body, retry = false)
                    else fail("বুঝতে পারিনি। $notSent", "Didn't get that. $notSent")
            }
        }
    }
}

/** Contact number -> the digits WhatsApp's wa.me link wants (country code, no plus). Bangladesh is the default country. */
object WaNumber {
    fun digits(raw: String): String? {
        val s = raw.trim()
        val d = s.filter { it.isDigit() }
        if (d.length < 7) return null
        if (s.startsWith("+")) return d
        if (d.startsWith("00") && d.length > 9) return d.drop(2)
        if (d.startsWith("880")) return d
        if (d.length == 11 && d.startsWith("01")) return "880" + d.drop(1)
        if (d.length == 10 && d.startsWith("1")) return "880$d"
        return d
    }
}
