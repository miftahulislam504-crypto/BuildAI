package com.buildx.ai.phase3

import com.buildx.ai.a11y.ToggleLabels
import com.buildx.ai.a11y.ToggleLabels.Kind
import com.buildx.ai.llm.LlmPlan
import com.buildx.ai.llm.LlmPlanParser
import com.buildx.ai.llm.LlmTools
import org.junit.Assert
import org.junit.Test

class Phase3LlmTest {
    private fun plan(intent: String, slots: String): LlmPlan? =
        LlmPlanParser.parse("""{"steps":[{"intent":"$intent","slots":$slots}]}""", "মাকে বলো আমি দেরি করব")

    @Test fun messageToolsExistAndTakeText() {
        for (n in listOf("send_sms", "send_whatsapp")) {
            val p = plan(n, """{"target":"মা","text":"আমি দেরি করব"}""")!!
            Assert.assertEquals(n, p.steps[0].intent)
            Assert.assertEquals("আমি দেরি করব", p.steps[0].slots["text"])
            Assert.assertEquals("llm", p.steps[0].slots["via"])
        }
        Assert.assertNull(plan("send_sms", """{"number":"abc","text":"hi"}"""))
        Assert.assertNotNull(plan("send_sms", """{"number":"01711223344","text":"hi"}"""))
    }

    @Test fun remindShapedLikeTheParserWould() {
        val a = plan("remind", """{"text":"call mom","hour":17}""")!!.steps[0].slots
        Assert.assertEquals("0", a["minute"]); Assert.assertEquals("false", a["ambiguous"]); Assert.assertEquals("auto", a["when"])
        val b = plan("remind", """{"text":"tea","in_minutes":10,"hour":5}""")!!.steps[0].slots
        Assert.assertEquals("10", b["in_minutes"]); Assert.assertNull(b["hour"])
        Assert.assertNull(plan("remind", """{"hour":5}"""))
    }

    @Test fun convertIsChecked() {
        val ok = plan("convert", """{"value":"2.5","from":"maund","to":"kg"}""")!!.steps[0].slots
        Assert.assertEquals("2.5", ok["value"])
        Assert.assertNull(plan("convert", """{"value":"abc","from":"km"}"""))
        Assert.assertNull(plan("convert", """{"value":"3","from":"km","to":"kg"}"""))
        Assert.assertNull(plan("convert", """{"value":"3","from":"parsec"}"""))
        Assert.assertNotNull(plan("convert", """{"value":"3","from":"usd"}"""))
    }

    @Test fun switchesAndReaders() {
        Assert.assertEquals("on", plan("dnd", """{"state":"on"}""")!!.steps[0].slots["state"])
        Assert.assertEquals("open", plan("airplane", """{"state":"open"}""")!!.steps[0].slots["state"])
        Assert.assertNull(plan("hotspot", """{"state":"maybe"}"""))
        Assert.assertNotNull(plan("read_sms", "{}"))
        Assert.assertNotNull(plan("read_notifications", "{}"))
        Assert.assertNotNull(plan("who_calling", "{}"))
        Assert.assertEquals("clear", plan("note", """{"op":"clear"}""")!!.steps[0].slots["op"])
        Assert.assertNull(plan("note", """{"text":"x"}"""))
    }

    @Test fun allNewToolsArePublished() {
        val names = LlmTools.all.map { it.name }.toSet()
        for (n in listOf("send_sms", "send_whatsapp", "read_sms", "read_notifications", "who_calling", "note", "remind", "calendar_event", "weather", "convert", "dnd", "airplane", "mobile_data", "hotspot")) {
            Assert.assertTrue(n, n in names)
        }
    }
}

class ToggleLabelsPhase3Test {
    private fun m(k: Kind, vararg t: String?) = ToggleLabels.matches(k, t.toList())

    @Test fun airplaneAndData() {
        Assert.assertTrue(m(Kind.AIRPLANE, "Airplane mode"))
        Assert.assertTrue(m(Kind.AIRPLANE, "ফ্লাইট মোড"))
        Assert.assertFalse(m(Kind.AIRPLANE, "Wi-Fi"))
        Assert.assertTrue(m(Kind.MOBILE_DATA, "Mobile data"))
        Assert.assertTrue(m(Kind.MOBILE_DATA, "মোবাইল ডেটা"))
        Assert.assertFalse(m(Kind.MOBILE_DATA, "Data saver"))
    }
    @Test fun wifiRulesUnchanged() {
        Assert.assertTrue(m(Kind.WIFI, "Wi-Fi"))
        Assert.assertFalse(m(Kind.WIFI, "Wi-Fi hotspot"))
    }
    @Test fun noBlindTapOnTheDataPage() {
        Assert.assertTrue(ToggleLabels.firstSwitchOk(Kind.WIFI))
        Assert.assertTrue(ToggleLabels.firstSwitchOk(Kind.AIRPLANE))
        Assert.assertFalse(ToggleLabels.firstSwitchOk(Kind.MOBILE_DATA))
    }
}
