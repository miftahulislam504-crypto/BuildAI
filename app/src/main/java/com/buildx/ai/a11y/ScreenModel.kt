package com.buildx.ai.a11y

import com.buildx.ai.contacts.ContactEntry
import com.buildx.ai.contacts.NameMatcher
import com.buildx.ai.nlu.TextNormalizer

/** The kinds of thing the agent can see on a screen. */
enum class ElKind { BUTTON, FIELD, SWITCH, SCROLL, TEXT }

/** One numbered thing on screen. [label] is already cleaned, masked and capped. */
data class ScreenElement(
    val id: Int,
    val kind: ElKind,
    val label: String,
    val value: String? = null,
    val checked: Boolean? = null,
    /** tapping it does something that cannot be undone or reaches other people (send, pay, delete, call ...) */
    val risky: Boolean = false
)

/**
 * What the agent is allowed to know about the current screen. [blocked] (a short reason) means the screen was
 * deliberately not read at all: no elements, nothing to send anywhere.
 */
data class ScreenSnapshot(
    val pkg: String,
    val elements: List<ScreenElement>,
    val blocked: String? = null,
    val truncated: Boolean = false
) {
    /** compact text for the model: one line per element */
    fun toPrompt(): String {
        val sb = StringBuilder()
        for (e in elements) {
            sb.append('[').append(e.id).append("] ")
            when (e.kind) {
                ElKind.BUTTON -> sb.append("button \"").append(e.label).append('"')
                ElKind.TEXT -> sb.append("text \"").append(e.label).append('"')
                ElKind.SCROLL -> sb.append("scrollable").append(if (e.label.isEmpty()) "" else " \"${e.label}\"")
                ElKind.SWITCH -> sb.append("switch \"").append(e.label).append("\" ").append(if (e.checked == true) "on" else "off")
                ElKind.FIELD -> {
                    sb.append("field \"").append(e.label).append('"')
                    sb.append(if (e.value.isNullOrEmpty()) " empty" else " value=\"${e.value}\"")
                }
            }
            sb.append('\n')
            if (sb.length > MAX_PROMPT) break
        }
        if (truncated) sb.append("(more elements not shown: scroll to see them)\n")
        return sb.toString().take(MAX_PROMPT)
    }

    companion object {
        const val MAX_PROMPT = 6000
    }
}

/** The part of Android's node tree the snapshot needs. The Android side wraps AccessibilityNodeInfo in this. */
interface UiNode {
    val text: String?
    val desc: String?
    val hint: String?
    val clickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val password: Boolean
    val visible: Boolean
    val children: List<UiNode>
}

/** A snapshot plus the node behind each element id, so an action can be carried out on exactly what the model saw. */
class BuiltScreen(val snapshot: ScreenSnapshot, val nodes: Map<Int, UiNode>)

object SnapshotBuilder {
    const val MAX_ELEMENTS = 60
    private const val MAX_VISITS = 2000
    private const val MAX_DEPTH = 40

    /**
     * Walks the tree in reading order. A clickable row takes the text inside it as its own label (so "Rahim, Hello,
     * 11:02" is one tappable thing, not three); anything interactive inside it is still listed on its own.
     * [allowSensitive] is only for commands the user gave directly ("tap Send", "read the screen"): the model
     * never gets a snapshot built with it.
     */
    fun build(root: UiNode, pkg: String, allowSensitive: Boolean = false): BuiltScreen {
        if (!allowSensitive) {
            val why = SensitiveApps.blocks(pkg) ?: if (hasPassword(root)) "a password field is on screen" else null
            if (why != null) return BuiltScreen(ScreenSnapshot(pkg, emptyList(), blocked = why), emptyMap())
        }
        val els = ArrayList<ScreenElement>()
        val nodes = LinkedHashMap<Int, UiNode>()
        var visits = 0
        var truncated = false

        fun add(node: UiNode, kind: ElKind, label: String, value: String? = null, checked: Boolean? = null) {
            if (els.size >= MAX_ELEMENTS) {
                truncated = true
                return
            }
            val id = els.size + 1
            val risky = kind == ElKind.BUTTON && RiskyLabels.isRisky(label)
            els.add(ScreenElement(id, kind, label, value, checked, risky))
            nodes[id] = node
        }

        fun walk(n: UiNode, depth: Int, textToo: Boolean) {
            if (visits++ > MAX_VISITS || depth > MAX_DEPTH || !n.visible) return
            when {
                n.editable -> {
                    val label = Redactor.label(first(n.hint, n.desc))
                    add(n, ElKind.FIELD, label, if (n.password) null else Redactor.label(n.text).ifEmpty { null })
                }
                n.checkable -> add(n, ElKind.SWITCH, ownOrInner(n), checked = n.checked)
                n.clickable -> {
                    add(n, ElKind.BUTTON, ownOrInner(n))
                    for (c in n.children) walk(c, depth + 1, textToo = false)
                }
                n.scrollable -> {
                    add(n, ElKind.SCROLL, Redactor.label(first(n.desc, n.text)))
                    for (c in n.children) walk(c, depth + 1, textToo)
                }
                else -> {
                    val label = Redactor.label(first(n.text, n.desc))
                    if (textToo && label.isNotEmpty()) add(n, ElKind.TEXT, label)
                    for (c in n.children) walk(c, depth + 1, textToo)
                }
            }
        }
        walk(root, 0, textToo = true)
        return BuiltScreen(ScreenSnapshot(pkg, els, truncated = truncated), nodes)
    }

    private fun first(vararg s: String?): String? = s.firstOrNull { !it.isNullOrBlank() }

    /** the node's own label, else the first three texts inside it */
    private fun ownOrInner(n: UiNode): String {
        Redactor.label(first(n.text, n.desc)).let { if (it.isNotEmpty()) return it }
        val parts = ArrayList<String>()
        fun dig(c: UiNode, depth: Int) {
            if (parts.size >= 3 || depth > 4 || !c.visible) return
            if (!c.clickable && !c.editable && !c.checkable) {
                Redactor.label(first(c.text, c.desc)).let { if (it.isNotEmpty()) parts.add(it) }
            }
            for (k in c.children) dig(k, depth + 1)
        }
        for (c in n.children) dig(c, 1)
        return Redactor.label(parts.joinToString(" · "))
    }

    private fun hasPassword(root: UiNode): Boolean {
        var seen = 0
        fun found(n: UiNode, depth: Int): Boolean {
            if (seen++ > MAX_VISITS || depth > MAX_DEPTH) return false
            if (n.password && n.visible) return true
            return n.children.any { found(it, depth + 1) }
        }
        return found(root, 0)
    }
}

/** Cleans text taken from a screen before anyone else sees it. */
object Redactor {
    const val MAX_LABEL = 60
    private val spacedDigits = Regex("(?:[0-9\u09E6-\u09EF][ -]?){10,}")
    private val longDigits = Regex("[0-9\u09E6-\u09EF]{6,}")

    /** one line, long digit runs (OTPs, card and phone numbers) masked, at most [MAX_LABEL] characters */
    fun label(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder()
        var gap = false
        for (ch in raw) {
            if (ch.isISOControl() || ch.isWhitespace()) {
                gap = sb.isNotEmpty()
            } else {
                if (gap) sb.append(' ')
                gap = false
                sb.append(if (ch == '"') '\'' else ch)
            }
        }
        val masked = sb.toString().replace(spacedDigits, "•••").replace(longDigits, "•••")
        return if (masked.length > MAX_LABEL) masked.take(MAX_LABEL - 1) + "…" else masked
    }
}

/** Apps whose screens are never handed to the model: money, passwords, and this app itself. */
object SensitiveApps {
    const val OWN_PACKAGE = "com.buildx.ai"

    private val keywords = listOf(
        "bank", "bkash", "nagad", "rocket", "upay", "wallet", "paypal", "payment", "paisa", "venmo", "cashapp",
        "authenticator", "keepass", "bitwarden", "1password", "lastpass", "dashlane", "password", "keychain", "otp"
    )

    /** null = fine, otherwise a short reason */
    fun blocks(pkg: String): String? {
        val p = pkg.lowercase()
        if (p == OWN_PACKAGE) return "this is the assistant's own app"
        val hit = keywords.firstOrNull { p.contains(it) } ?: return null
        return "the app looks like a money or password app ($hit)"
    }
}

/** Buttons the agent may not press on its own: it asks the user first. */
object RiskyLabels {
    private val words = setOf(
        "send", "post", "pay", "confirm", "delete", "remove", "order", "buy", "purchase", "submit", "transfer",
        "checkout", "uninstall", "reset", "erase", "call", "dial", "share", "publish", "install", "unfollow", "block",
        "পাঠান", "পাঠাও", "সেন্ড", "পোস্ট", "পেমেন্ট", "কিনুন", "কিনো", "অর্ডার", "মুছুন", "ডিলিট", "জমা", "নিশ্চিত",
        "কনফার্ম", "ট্রান্সফার", "আনইনস্টল", "কল", "শেয়ার", "ইনস্টল", "ব্লক"
    )
    private val phrases = listOf("place order", "sign out", "log out", "logout", "clear all", "সাইন আউট", "লগ আউট")

    fun isRisky(label: String): Boolean {
        val l = label.lowercase()
        if (phrases.any { l.contains(it) }) return true
        return l.split(Regex("[^\\p{L}\\p{M}]+")).any { it in words }
    }
}

/** Finding what a spoken phrase points at on the screen, and reading the screen out. */
object ScreenMatch {
    const val MAX_READ = 500

    /**
     * The elements the person means by [query], best matches only. Tappable things are tried before plain text,
     * and for each group: the same words, then the words inside a longer label, then the sound of the words
     * (a Bengali "সেন্ড" finds a button labelled "Send").
     */
    fun find(elements: List<ScreenElement>, query: String): List<ScreenElement> {
        val q = TextNormalizer.normalize(query).trim()
        if (q.isEmpty()) return emptyList()
        val labelled = elements.filter { it.kind != ElKind.SCROLL && it.label.isNotEmpty() }
        for (pool in listOf(labelled.filter { it.kind != ElKind.TEXT }, labelled)) {
            if (pool.isEmpty()) continue
            val exact = pool.filter { TextNormalizer.normalize(it.label).trim() == q }
            if (exact.isNotEmpty()) return exact
            val inside = pool.filter { " ${TextNormalizer.normalize(it.label).trim()} ".contains(" $q ") }
            if (inside.isNotEmpty()) return inside
            val entries = pool.map { ContactEntry(it.id.toString(), it.label, emptyList()) }
            val byId = pool.associateBy { it.id.toString() }
            val sounds = NameMatcher.bestGroup(NameMatcher.find(query, entries)).mapNotNull { byId[it.contact.id] }
            if (sounds.isNotEmpty()) return sounds
        }
        return emptyList()
    }

    /** the visible text in reading order, one sentence per item, at most [MAX_READ] characters */
    fun readAloud(elements: List<ScreenElement>): String =
        elements.mapNotNull {
            when (it.kind) {
                ElKind.TEXT, ElKind.BUTTON -> it.label
                ElKind.FIELD -> it.value ?: it.label
                ElKind.SWITCH -> "${it.label} ${if (it.checked == true) "on" else "off"}"
                ElKind.SCROLL -> null
            }
        }.filter { it.isNotBlank() }.distinct().joinToString(". ").take(MAX_READ)
}
