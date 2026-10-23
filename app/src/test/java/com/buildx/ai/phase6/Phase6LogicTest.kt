package com.buildx.ai.phase6

import com.buildx.ai.actions.RiskPolicy
import com.buildx.ai.llm.LlmPlanParser
import com.buildx.ai.system.DatedAlarm
import com.buildx.ai.system.RoutineBook
import org.junit.Assert
import org.junit.Test
import java.util.Calendar

class DatedAlarmTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Calendar =
        Calendar.getInstance().apply { clear(); set(y, mo, d, h, mi, 0) }

    private fun slots(vararg kv: String) = kv.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun tomorrowMorningFromEveningIsTheNextOccurrenceSoTheClockAppCanDoIt() {
        val now = at(2026, Calendar.OCTOBER, 22, 22, 0)
        val p = DatedAlarm.plan(now, slots("hour=6", "minute=0", "when=tomorrow"))!!
        Assert.assertTrue(p.clockAppFires)
        Assert.assertEquals(at(2026, Calendar.OCTOBER, 23, 6, 0).timeInMillis, p.at)
    }

    @Test fun dayAfterTomorrowNeedsOurOwnAlarm() {
        val now = at(2026, Calendar.OCTOBER, 22, 22, 0)
        val p = DatedAlarm.plan(now, slots("hour=6", "minute=30", "when=day_after"))!!
        Assert.assertFalse(p.clockAppFires)
        Assert.assertEquals(at(2026, Calendar.OCTOBER, 24, 6, 30).timeInMillis, p.at)
    }

    @Test fun tomorrowSaidAfterMidnightIsNotTheNextOccurrence() {
        // 01:00 on the 23rd, "কাল ৬টায়" means the 24th at 6, but the Clock app would ring today at 6
        val now = at(2026, Calendar.OCTOBER, 23, 1, 0)
        val p = DatedAlarm.plan(now, slots("hour=6", "minute=0", "when=tomorrow"))!!
        Assert.assertFalse(p.clockAppFires)
        Assert.assertEquals(at(2026, Calendar.OCTOBER, 24, 6, 0).timeInMillis, p.at)
    }

    @Test fun todayThatHasPassedHasNoPlan() {
        val now = at(2026, Calendar.OCTOBER, 22, 22, 0)
        Assert.assertNull(DatedAlarm.plan(now, slots("hour=6", "minute=0", "when=today")))
    }

    @Test fun aTimerHasNoDatedPlan() {
        Assert.assertNull(DatedAlarm.plan(at(2026, Calendar.OCTOBER, 22, 22, 0), slots("in_minutes=20")))
    }

    @Test fun plainTimeIsTheNextOccurrence() {
        val now = at(2026, Calendar.OCTOBER, 22, 14, 0)
        val p = DatedAlarm.plan(now, slots("hour=18", "minute=15"))!!
        Assert.assertTrue(p.clockAppFires)
        Assert.assertEquals(at(2026, Calendar.OCTOBER, 22, 18, 15).timeInMillis, p.at)
    }

    @Test fun snoozeIsFiveMinutesAndClamped() {
        Assert.assertEquals(1_000L + 300_000L, DatedAlarm.snoozeAt(1_000L))
        Assert.assertEquals(0L + 3_600_000L, DatedAlarm.snoozeAt(0L, 500))
        Assert.assertEquals(60_000L, DatedAlarm.snoozeAt(0L, 0))
    }
}

class RiskPolicyTest {

    @Test fun constantsMatchTheServerPlanParser() {
        Assert.assertEquals(LlmPlanParser.VIA, RiskPolicy.VIA)
        Assert.assertEquals(LlmPlanParser.VIA_LLM, RiskPolicy.VIA_LLM)
    }

    @Test fun messagesCallsAndTheScreenAgentAreSelfConfirmed() {
        for (i in listOf("send_sms", "send_whatsapp", "call", "whatsapp_call", "ui_task")) {
            Assert.assertEquals(i, RiskPolicy.Level.SELF_CONFIRMED, RiskPolicy.level(i))
        }
    }

    @Test fun selfConfirmedIntentsNeverRunByThemselves() {
        for (i in RiskPolicy.selfConfirmedIntents()) {
            Assert.assertFalse("$i must not run unattended", RiskPolicy.unattendedAllowed(i))
            Assert.assertFalse("$i must be blocked in an automatic routine", RoutineBook.autoSafe(i))
        }
    }

    @Test fun modelChosenConnectivityChangesAskFirst() {
        for (i in listOf("airplane", "hotspot", "mobile_data", "dnd")) {
            Assert.assertTrue(i, RiskPolicy.mustAsk(i, mapOf("via" to "llm")))
        }
    }

    @Test fun whatThePersonSaidThemselvesIsNotAskedTwice() {
        Assert.assertFalse(RiskPolicy.mustAsk("airplane", mapOf("state" to "on")))
        Assert.assertFalse(RiskPolicy.mustAsk("airplane", mapOf("via" to "llm", "confirmed" to "1")))
        Assert.assertFalse(RiskPolicy.mustAsk("flashlight", mapOf("via" to "llm")))
        Assert.assertFalse(RiskPolicy.mustAsk("send_sms", mapOf("via" to "llm"))) // its own flow asks
    }

    @Test fun aCallPickedByTheModelIsAlwaysConfirmedEvenWithTheSwitchOff() {
        Assert.assertTrue(RiskPolicy.callNeedsConfirm(true, emptyMap()))
        Assert.assertTrue(RiskPolicy.callNeedsConfirm(false, mapOf("via" to "llm")))
        Assert.assertFalse(RiskPolicy.callNeedsConfirm(false, emptyMap()))
    }

    @Test fun everyListedIntentIsARealAction() {
        // keeps the policy in step with the registry (names only; no Android needed)
        val known = setOf(
            "call", "whatsapp_call", "send_sms", "send_whatsapp", "ui_task", "airplane", "hotspot", "mobile_data", "dnd"
        )
        Assert.assertEquals(known, RiskPolicy.selfConfirmedIntents() + RiskPolicy.askWhenLlmIntents())
    }
}
