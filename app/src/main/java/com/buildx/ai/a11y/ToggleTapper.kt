package com.buildx.ai.a11y

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.buildx.ai.LogStore

/**
 * After the Wi-Fi/Bluetooth settings screen is open: find the right switch, tap it if it is in the wrong
 * position, then confirm with the phone's own state ([isOn]). Fragile by nature (every phone lays the screen out
 * differently), so it always reports honestly what it managed.
 */
object ToggleTapper {

    enum class Outcome { VERIFIED, ALREADY, UNVERIFIED, NOT_FOUND }

    private val main = Handler(Looper.getMainLooper())
    private const val FIND_TRIES = 14      // x 350 ms = about 5 s for the screen to appear
    private const val VERIFY_TRIES = 8     // x 500 ms = about 4 s for the radio to change

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

        fun verify(tries: Int) {
            val now = isOn()
            if (now == want) {
                main.postDelayed({ svc.goBack() }, 500) // close the panel again
                finish(Outcome.VERIFIED)
            } else if (tries <= 0) {
                finish(Outcome.UNVERIFIED)
            } else {
                main.postDelayed({ verify(tries - 1) }, 500)
            }
        }

        fun find(tries: Int) {
            val sw = findSwitch(svc.rootInActiveWindow, kind)
            if (sw == null) {
                if (tries <= 0) finish(Outcome.NOT_FOUND) else main.postDelayed({ find(tries - 1) }, 350)
                return
            }
            if (sw.isChecked == want) {
                main.postDelayed({ svc.goBack() }, 300)
                finish(Outcome.ALREADY)
                return
            }
            if (!clickSelfOrParent(sw)) {
                finish(Outcome.NOT_FOUND)
                return
            }
            main.postDelayed({ verify(VERIFY_TRIES) }, 500)
        }

        main.postDelayed({ find(FIND_TRIES) }, 600)
    }

    private fun isSwitchLike(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString() ?: ""
        return cls.contains("Switch") || cls.contains("ToggleButton") || n.isCheckable
    }

    /** breadth-first; a switch counts if its own text or the text near it (siblings, parent's row) names Wi-Fi/Bluetooth */
    private fun findSwitch(root: AccessibilityNodeInfo?, kind: ToggleLabels.Kind): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 600) {
            val n = queue.removeFirst()
            seen++
            if (isSwitchLike(n) && ToggleLabels.matches(kind, nearbyTexts(n))) return n
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

    private fun clickSelfOrParent(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var level = 0
        while (cur != null && level < 4) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            level++
        }
        return false
    }
}
