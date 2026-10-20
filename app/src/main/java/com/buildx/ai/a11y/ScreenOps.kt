package com.buildx.ai.a11y

import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** What the assistant can read from and do to the screen, through the Accessibility service. Main thread only. */
object ScreenOps {

    /** the current screen as numbered elements, or null if nothing can be read (service off, screen locked) */
    fun capture(svc: BuildXAccessibilityService, ctx: Context, allowSensitive: Boolean = false): BuiltScreen? {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val root = svc.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (km?.isKeyguardLocked == true) {
            return BuiltScreen(ScreenSnapshot(pkg, emptyList(), blocked = "the phone is locked"), emptyMap())
        }
        return SnapshotBuilder.build(A11yNode(root), pkg, allowSensitive)
    }

    /** click the node, or the nearest clickable parent; else a plain tap on its middle. [done] says whether it was carried out. */
    fun tap(svc: BuildXAccessibilityService, node: UiNode, done: (Boolean) -> Unit) {
        val info = (node as? A11yNode)?.info ?: return done(false)
        if (!info.refresh()) return done(false)
        var cur: AccessibilityNodeInfo? = info
        var level = 0
        while (cur != null && level < 5) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return done(true)
            cur = cur.parent
            level++
        }
        val r = Rect()
        info.getBoundsInScreen(r)
        if (r.isEmpty) return done(false)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val sent = svc.dispatchGesture(g, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = done(true)
            override fun onCancelled(d: GestureDescription?) = done(false)
        }, null)
        if (!sent) done(false)
    }

    /** replaces the text of an editable field. Never touches a password field. */
    fun typeInto(node: UiNode?, text: String): Boolean {
        val info = (node as? A11yNode)?.info ?: return false
        if (!info.refresh() || info.isPassword || !info.isEditable) return false
        info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** the field that has input focus right now, if any */
    fun focusedField(svc: BuildXAccessibilityService): UiNode? {
        val root = svc.rootInActiveWindow ?: return null
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (f.isEditable) A11yNode(f) else null
    }

    /** scrolls the first scrollable thing on screen; if there is none, swipes the screen instead */
    fun scroll(svc: BuildXAccessibilityService, down: Boolean, done: (Boolean) -> Unit) {
        val root = svc.rootInActiveWindow
        val target = root?.let { firstScrollable(it) }
        if (target != null) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.performAction(action)) return done(true)
        }
        svc.swipe(next = down, done = done)
    }

    private fun firstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 800) {
            val n = queue.removeFirst()
            if (n.isScrollable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }
}
