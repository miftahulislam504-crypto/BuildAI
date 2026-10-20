package com.buildx.ai.a11y

import android.view.accessibility.AccessibilityNodeInfo

/** [UiNode] over a real accessibility node. Children are fetched only when the walk gets to them. */
class A11yNode(val info: AccessibilityNodeInfo) : UiNode {
    override val text: String? get() = if (info.isShowingHintText) null else info.text?.toString()
    override val desc: String? get() = info.contentDescription?.toString()
    override val hint: String? get() = info.hintText?.toString() ?: if (info.isShowingHintText) info.text?.toString() else null
    override val clickable: Boolean get() = info.isClickable && info.isEnabled
    override val editable: Boolean get() = info.isEditable
    override val scrollable: Boolean get() = info.isScrollable
    override val checkable: Boolean get() = info.isCheckable
    override val checked: Boolean get() = info.isChecked
    override val password: Boolean get() = info.isPassword
    override val visible: Boolean get() = info.isVisibleToUser
    override val children: List<UiNode> by lazy {
        (0 until info.childCount).mapNotNull { i -> info.getChild(i)?.let { A11yNode(it) } }
    }
}
