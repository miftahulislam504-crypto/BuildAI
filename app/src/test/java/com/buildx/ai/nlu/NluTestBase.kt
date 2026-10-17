package com.buildx.ai.nlu

import org.junit.Assert

/** Small helpers so each test line is one spoken sentence + what it must mean. */
object T {
    fun p(text: String): Parsed = IntentParser.parse(text)

    /** [slots] are "key=value" pairs that must all be present */
    fun ok(text: String, intent: String, vararg slots: String) {
        val r = p(text)
        Assert.assertEquals("intent for \"$text\" (normalized \"${r.normalized}\", slots ${r.slots})", intent, r.intent)
        for (s in slots) {
            val k = s.substringBefore('=')
            val v = s.substringAfter('=')
            Assert.assertEquals("slot $k for \"$text\" (slots ${r.slots})", v, r.slots[k])
        }
    }
}
