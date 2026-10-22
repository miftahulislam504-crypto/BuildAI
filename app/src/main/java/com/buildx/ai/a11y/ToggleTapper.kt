package com.buildx.ai.a11y

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.buildx.ai.LogStore

/**
 * After the Wi-Fi/Bluetooth settings screen is open: find the switch, tap it if it is in the wrong position, then
 * confirm with the phone's own state ([isOn]). Every phone lays the screen out differently, so it tries in turn:
 *  1. a switch whose nearby text says Wi-Fi / Bluetooth,
 *  2. (after a while) the first switch on the page, which on the full Wi-Fi/Bluetooth settings page is the main one,
 * and for the tap: the switch itself, then the clickable rows around it.
 * It always reports honestly what it managed, and on failure it logs what kind of switches it saw (class and its own
 * label only) so the matching can be fixed for that phone.
 */
object ToggleTapper {

    enum class Outcome { VERIFIED, ALREADY, UNVERIFIED, NOT_FOUND }

    private val main = Handler(Looper.getMainLooper())
    private const val FIND_TRIES = 16        // x 350 ms = about 5.5 s for the screen to appear
    private const val FALLBACK_AFTER = 8     // tries before "first switch on the page" is allowed
    private const val VERIFY_TRIES = 6       // x 500 ms per tap candidate

    fun run(
        svc: BuildXAccessibilityService,
        kind: ToggleLabels.Kind,
        want: Boolean,
        isOn: () -> Boolean?,
        done: (Outcome) -> Unit
    ) {
        var finished = false
        fun finish(o: Outcome) {
            if (finished) return
            finished = true
            LogStore.add(svc, "TOGGLE $kind want=$want -> $o")
            done(o)
        }

        fun closeLater(ms: Long) {
            main.postDelayed({ svc.goBack() }, ms)
        }

        // tap candidates one after another, each checked against the phone's real state
        fun tryCandidates(cands: List<AccessibilityNodeInfo>, index: Int) {
            if (index >= cands.size) return finish(Outcome.UNVERIFIED)
            val clicked = cands[index].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            LogStore.add(svc, "TOGGLE tap #${index + 1}/${cands.size} accepted=$clicked")
            fun verify(left: Int) {
                if (isOn() == want) {
                    closeLater(500)
                    finish(Outcome.VERIFIED)
                } else if (left <= 0) {
                    tryCandidates(cands, index + 1)
                } else {
                    main.postDelayed({ verify(left - 1) }, 500)
                }
            }
            main.postDelayed({ verify(VERIFY_TRIES) }, 400)
        }

        fun find(tries: Int) {
            val root = svc.rootInActiveWindow
            val labeled = findSwitch(root, kind, labeledOnly = true)
            val sw = labeled ?: if (tries <= FIND_TRIES - FALLBACK_AFTER && ToggleLabels.firstSwitchOk(kind)) findSwitch(root, kind, labeledOnly = false) else null
            if (sw == null) {
                if (tries <= 0) {
                    dump(svc, root)
                    finish(Outcome.NOT_FOUND)
                } else {
                    main.postDelayed({ find(tries - 1) }, 350)
                }
                return
            }
            LogStore.add(svc, "TOGGLE switch found (${if (labeled != null) "by label" else "first on page"}) checked=${sw.isChecked}")
            val state = isOn()
            if ((state ?: sw.isChecked) == want) {
                closeLater(300)
                return finish(Outcome.ALREADY)
            }
            val cands = clickCandidates(sw)
            if (cands.isEmpty()) {
                dump(svc, root)
                return finish(Outcome.NOT_FOUND)
            }
            tryCandidates(cands, 0)
        }

        main.postDelayed({ find(FIND_TRIES) }, 600)
    }

    private fun isSwitchLike(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString() ?: ""
        return cls.contains("Switch") || cls.contains("ToggleButton") || n.isCheckable
    }

    private fun findSwitch(root: AccessibilityNodeInfo?, kind: ToggleLabels.Kind, labeledOnly: Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 600) {
            val n = queue.removeFirst()
            seen++
            if (isSwitchLike(n) && (!labeledOnly || ToggleLabels.matches(kind, nearbyTexts(n)))) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun nearbyTexts(n: AccessibilityNodeInfo): List<CharSequence?> {
        val out = ArrayList<CharSequence?>()
        out.add(n.text)
        out.add(n.contentDescription)
        var p = n.parent
        var level = 0
        while (p != null && level < 2) {
            out.add(p.text)
            out.add(p.contentDescription)
            for (i in 0 until p.childCount) {
                val c = p.getChild(i) ?: continue
                out.add(c.text)
                out.add(c.contentDescription)
            }
            p = p.parent
            level++
        }
        return out
    }

    /** the switch itself (if clickable), then up to 3 clickable rows around it */
    private fun clickCandidates(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        var cur: AccessibilityNodeInfo? = n
        var level = 0
        while (cur != null && level < 4) {
            if (cur.isClickable) out.add(cur)
            cur = cur.parent
            level++
        }
        return out
    }

    /** failure aid: the kind and own label of the switches on screen, nothing else */
    private fun dump(svc: BuildXAccessibilityService, root: AccessibilityNodeInfo?) {
        if (root == null) {
            LogStore.add(svc, "TOGGLE-DUMP: কোনো স্ক্রিন পাওয়া যায়নি (rootInActiveWindow null)")
            return
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        var shown = 0
        LogStore.add(svc, "TOGGLE-DUMP package=${root.packageName}")
        while (queue.isNotEmpty() && seen < 600 && shown < 8) {
            val n = queue.removeFirst()
            seen++
            if (isSwitchLike(n)) {
                shown++
                LogStore.add(svc, "TOGGLE-DUMP ${n.className} text=${n.text} desc=${n.contentDescription} checked=${n.isChecked} clickable=${n.isClickable}")
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        if (shown == 0) LogStore.add(svc, "TOGGLE-DUMP: স্ক্রিনে কোনো সুইচ-ধরনের উপাদান পাইনি ($seen টি উপাদান দেখেছি)")
    }
}
