package com.buildx.ai.llm

import org.json.JSONObject
import org.junit.Assert
import org.junit.Test

class LlmPlanTest {
    private val said = "মাকে বলো আমি দেরি করব"

    private fun plan(body: String): LlmPlan? = LlmPlanParser.parse(body, said)

    private fun step(intent: String, slots: String = "{}") = """{"intent":"$intent","slots":$slots}"""

    private fun steps(vararg s: String) = """{"steps":[${s.joinToString(",")}]}"""

    @Test fun aValidStepIsAccepted() {
        val p = plan(steps(step("flashlight", """{"state":"on"}""")))!!
        Assert.assertEquals(1, p.steps.size)
        Assert.assertEquals("flashlight", p.steps[0].intent)
        Assert.assertEquals("on", p.steps[0].slots["state"])
        Assert.assertEquals(said, p.steps[0].normalized)
    }

    @Test fun everyStepIsMarkedAsComingFromTheModel() {
        val p = plan(steps(step("time"), step("battery")))!!
        Assert.assertTrue(p.steps.all { it.slots["via"] == "llm" })
    }

    @Test fun aSpokenAnswerAloneIsAPlan() {
        val p = plan("""{"say":"প্যারিস","steps":[]}""")!!
        Assert.assertEquals("প্যারিস", p.say)
        Assert.assertTrue(p.steps.isEmpty())
        Assert.assertEquals("Paris", plan("""{"say":"Paris"}""")!!.say)
    }

    @Test fun nothingToDoAndNothingToSayIsNotAPlan() {
        Assert.assertNull(plan("{}"))
        Assert.assertNull(plan("""{"steps":[]}"""))
        Assert.assertNull(plan("""{"say":"   ","steps":[]}"""))
    }

    @Test fun jsonNullIsTreatedAsMissing() {
        val p = plan("""{"say":null,"steps":[{"intent":"time","slots":null}]}""")!!
        Assert.assertNull(p.say)
        Assert.assertEquals("time", p.steps[0].intent)
    }

    @Test fun garbageIsNotAPlan() {
        for (bad in listOf("", "not json", "[]", """{"steps":"flashlight"}""", """{"steps":[1]}""", """{"steps":[{"slots":{}}]}""")) {
            Assert.assertNull("should reject: $bad", plan(bad))
        }
    }

    @Test fun anIntentOutsideTheMenuRejectsTheWholePlan() {
        Assert.assertNull(plan(steps(step("flashlight", """{"state":"on"}"""), step("format_phone"))))
        for (internal in listOf("unknown", "echo", "cancel", "end_call")) {
            Assert.assertNull("$internal must not be reachable", plan(steps(step(internal))))
        }
    }

    @Test fun aValueOutsideTheChoicesIsRejected() {
        Assert.assertNull(plan(steps(step("flashlight", """{"state":"blink"}"""))))
        Assert.assertNull(plan(steps(step("media", """{"action":"delete"}"""))))
        Assert.assertEquals("on", plan(steps(step("flashlight", """{"state":" ON "}""")))!!.steps[0].slots["state"])
    }

    @Test fun aRequiredSlotMustBePresent() {
        Assert.assertNull(plan(steps(step("ringer"))))
        Assert.assertNull(plan(steps(step("volume"))))
        Assert.assertNull(plan(steps(step("navigate"))))
        Assert.assertNull(plan(steps(step("open_app", """{"app":"   "}"""))))
    }

    @Test fun numbersAreRangeChecked() {
        Assert.assertNull(plan(steps(step("alarm", """{"hour":25}"""))))
        Assert.assertNull(plan(steps(step("alarm", """{"hour":7,"minute":60}"""))))
        Assert.assertNull(plan(steps(step("volume", """{"action":"set","level":150}"""))))
        Assert.assertNull(plan(steps(step("alarm", """{"in_minutes":0}"""))))
    }

    @Test fun numbersMayArriveAsNumbersOrStrings() {
        for (h in listOf("7", "\"7\"", "7.0")) {
            Assert.assertEquals(h, "7", plan(steps(step("alarm", """{"hour":$h}""")))!!.steps[0].slots["hour"])
        }
        Assert.assertNull(plan(steps(step("alarm", """{"hour":7.5}"""))))
        Assert.assertNull(plan(steps(step("alarm", """{"hour":true}"""))))
    }

    @Test fun anAlarmIsShapedLikeTheOfflineParserShapesIt() {
        val s = plan(steps(step("alarm", """{"hour":19}""")))!!.steps[0].slots
        Assert.assertEquals("19", s["hour"])
        Assert.assertEquals("0", s["minute"])
        Assert.assertEquals("false", s["ambiguous"]) // 24-hour time: nothing left to guess
        Assert.assertEquals("auto", s["when"])
    }

    @Test fun aTimerCarriesNoClockFields() {
        val s = plan(steps(step("alarm", """{"in_minutes":10,"hour":7,"minute":30}""")))!!.steps[0].slots
        Assert.assertEquals("10", s["in_minutes"])
        Assert.assertNull(s["hour"])
        Assert.assertNull(s["minute"])
    }

    @Test fun slotsNobodyAskedForAreDropped() {
        val s = plan(steps(step("flashlight", """{"state":"on","evil":"x","via":"user"}""")))!!.steps[0].slots
        Assert.assertNull(s["evil"])
        Assert.assertEquals("llm", s["via"]) // the model cannot claim the command came from the user
    }

    @Test fun atMostFiveSteps() {
        val one = step("time")
        Assert.assertNotNull(plan(steps(one, one, one, one, one)))
        Assert.assertNull(plan(steps(one, one, one, one, one, one)))
    }

    @Test fun aPhoneNumberMustLookLikeOne() {
        Assert.assertEquals("01711223344", plan(steps(step("call", """{"number":"01711223344"}""")))!!.steps[0].slots["number"])
        Assert.assertEquals("+880 1711-223344", plan(steps(step("whatsapp_call", """{"number":"+880 1711-223344"}""")))!!.steps[0].slots["number"])
        Assert.assertNotNull(plan(steps(step("call", """{"number":"০১৭১১২২৩৩৪৪"}"""))))
        Assert.assertNull(plan(steps(step("call", """{"number":"call 999; rm"}"""))))
        Assert.assertNull(plan(steps(step("call", """{"number":"12"}"""))))
        Assert.assertEquals("রহিম", plan(steps(step("call", """{"target":"রহিম"}""")))!!.steps[0].slots["target"])
    }

    @Test fun aCallIsAllowedWithNoNameSoThePhoneAsksWho() {
        val s = plan(steps(step("call")))!!.steps[0].slots
        Assert.assertNull(s["target"])
        Assert.assertNull(s["number"])
    }

    @Test fun aKnownAppGetsTheOfflineParsersKeyAndAnotherIsSearchedByLabel() {
        val known = plan(steps(step("open_app", """{"app":"YouTube"}""")))!!.steps[0].slots
        Assert.assertEquals("true", known["known"])
        val other = plan(steps(step("open_app", """{"app":"Foodpanda"}""")))!!.steps[0].slots
        Assert.assertEquals("false", other["known"])
        Assert.assertEquals("foodpanda", other["app"]?.lowercase()) // the parser lower-cases spoken names too; the label search ignores case
    }

    @Test fun aCalculationMayOnlyHoldNumbersAndOperatorWords() {
        Assert.assertEquals("12 times 5", plan(steps(step("calc", """{"expr":"12 times 5"}""")))!!.steps[0].slots["expr"])
        Assert.assertEquals("2 point 5 plus 1", plan(steps(step("calc", """{"expr":"2 point 5 plus 1"}""")))!!.steps[0].slots["expr"])
        Assert.assertNull(plan(steps(step("calc", """{"expr":"12 times 5; drop"}"""))))
        Assert.assertNull(plan(steps(step("calc", """{"expr":"twelve times five"}"""))))
        Assert.assertNull(plan(steps(step("calc"))))
    }

    @Test fun textIsCleanedAndCapped() {
        val q = plan(steps(step("navigate", JSONObject().put("query", "ঢাকা\n\n  মিরপুর\u0000 ১০").toString())))!!.steps[0].slots["query"]
        Assert.assertEquals("ঢাকা মিরপুর ১০", q)
        val long = plan(steps(step("web_search", JSONObject().put("query", "a".repeat(500)).toString())))!!.steps[0].slots["query"]
        Assert.assertEquals(120, long!!.length)
        val say = plan(JSONObject().put("say", "x".repeat(1000)).toString())!!.say
        Assert.assertEquals(400, say!!.length)
    }

    // ----- HTTP status -> outcome -----

    @Test fun httpStatusesBecomeOutcomes() {
        val good = steps(step("time"))
        Assert.assertTrue(LlmPlanParser.fromHttp(200, good, said) is LlmOutcome.Planned)
        fun why(code: Int, body: String = "") = (LlmPlanParser.fromHttp(code, body, said) as LlmOutcome.Failed).why
        Assert.assertEquals(Failure.BAD_RESPONSE, why(200, "<html>"))
        Assert.assertEquals(Failure.AUTH, why(401))
        Assert.assertEquals(Failure.AUTH, why(403))
        Assert.assertEquals(Failure.BUSY, why(429))
        Assert.assertEquals(Failure.TIMEOUT, why(504))
        Assert.assertEquals(Failure.SERVER, why(500))
        Assert.assertEquals(Failure.SERVER, why(302))
    }

    // ----- what is sent -----

    @Test fun theRequestCarriesTheSentenceAndTheMenuAndNothingElse() {
        val o = JSONObject(LlmRequestJson.build(LlmRequest(said, "bn-BD", "2026-10-20T14:03+06:00")))
        Assert.assertEquals(setOf("text", "lang", "now", "tools"), o.keys().asSequence().toSet())
        Assert.assertEquals(said, o.getString("text"))
        Assert.assertEquals("bn-BD", o.getString("lang"))
        val tools = o.getJSONArray("tools")
        Assert.assertEquals(LlmTools.all.count { !it.agent }, tools.length())
        Assert.assertEquals("call", tools.getJSONObject(0).getString("name"))
    }

    @Test fun theScreenToolIsOfferedOnlyWhenTheAgentIsOn() {
        fun names(agent: Boolean): List<String> {
            val tools = JSONObject(LlmRequestJson.build(LlmRequest(said, "bn-BD", "now", agent))).getJSONArray("tools")
            return (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        }
        Assert.assertFalse("ui_task" in names(false))
        Assert.assertTrue("ui_task" in names(true))
        Assert.assertTrue("tap" in names(false))
    }

    // ----- screen commands -----

    @Test fun systemButtonsNeedNoSlots() {
        for (i in listOf("back", "home", "recents", "notifications", "screenshot", "read_screen")) {
            Assert.assertEquals(i, plan(steps(step(i)))!!.steps[0].intent)
        }
    }

    @Test fun tapAndTypeNeedTheirWords() {
        Assert.assertNull(plan(steps(step("tap"))))
        Assert.assertNull(plan(steps(step("type_text", """{"text":"  "}"""))))
        Assert.assertEquals("Send", plan(steps(step("tap", """{"text":"Send"}""")))!!.steps[0].slots["text"])
        val long = "w".repeat(500)
        Assert.assertEquals(300, plan(steps(step("type_text", JSONObject().put("text", long).toString())))!!.steps[0].slots["text"]!!.length)
        Assert.assertEquals(80, plan(steps(step("tap", JSONObject().put("text", long).toString())))!!.steps[0].slots["text"]!!.length)
    }

    @Test fun aScreenTaskCarriesItsGoal() {
        val s = plan(steps(step("open_app", """{"app":"WhatsApp"}"""), step("ui_task", """{"goal":"send hi to Rahim"}""")))!!.steps
        Assert.assertEquals(listOf("open_app", "ui_task"), s.map { it.intent })
        Assert.assertEquals("send hi to Rahim", s[1].slots["goal"])
        Assert.assertNull(plan(steps(step("ui_task"))))
    }

    // ----- asking back, and what context is sent -----

    @Test fun aQuestionIsAPlanWithNoStepsEvenIfStepsCameWithIt() {
        val torch = step("flashlight", """{"state":"on"}""")
        val p = plan("""{"ask":"কাকে পাঠাব?","steps":[$torch]}""")!!
        Assert.assertEquals("কাকে পাঠাব?", p.ask)
        Assert.assertTrue(p.steps.isEmpty())
        Assert.assertNull(p.say)
    }

    @Test fun anEmptyQuestionIsNotAQuestion() {
        Assert.assertNull(plan("""{"ask":"  ","say":"ঠিক আছে","steps":[]}""")!!.ask)
        Assert.assertNull(plan("""{"ask":"  ","steps":[]}"""))
    }

    @Test fun recentAndClarifyAreSentOnlyWhenPresent() {
        val plain = JSONObject(LlmRequestJson.build(LlmRequest(said, "bn-BD", "now")))
        Assert.assertFalse(plain.has("recent"))
        Assert.assertFalse(plain.has("clarify"))

        val full = JSONObject(
            LlmRequestJson.build(
                LlmRequest(said, "bn-BD", "now", recent = listOf("wifi state=on"), clarify = "কাকে?" to "রহিমকে")
            )
        )
        Assert.assertEquals("wifi state=on", full.getJSONArray("recent").getString(0))
        Assert.assertEquals("কাকে?", full.getJSONObject("clarify").getString("question"))
        Assert.assertEquals("রহিমকে", full.getJSONObject("clarify").getString("answer"))
    }
}
