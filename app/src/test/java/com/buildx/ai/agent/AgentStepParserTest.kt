package com.buildx.ai.agent

import com.buildx.ai.llm.Failure
import org.json.JSONObject
import org.junit.Assert
import org.junit.Test

class AgentStepParserTest {
    private fun one(intent: String, slots: String = "{}") = """{"steps":[{"intent":"$intent","slots":$slots}]}"""

    @Test fun eachToolBecomesItsStep() {
        Assert.assertEquals(AgentStep.Tap(12), AgentStepParser.parse(one("tap", """{"id":12}""")))
        Assert.assertEquals(AgentStep.Type("hello", null), AgentStepParser.parse(one("type", """{"text":"hello"}""")))
        Assert.assertEquals(AgentStep.Type("hello", 3), AgentStepParser.parse(one("type", """{"text":"hello","id":3}""")))
        Assert.assertEquals(AgentStep.Scroll(true), AgentStepParser.parse(one("scroll", """{"dir":"down"}""")))
        Assert.assertEquals(AgentStep.Scroll(false), AgentStepParser.parse(one("scroll", """{"dir":"up"}""")))
        Assert.assertEquals(AgentStep.Back, AgentStepParser.parse(one("back")))
        Assert.assertEquals(AgentStep.Home, AgentStepParser.parse(one("home")))
        Assert.assertEquals(AgentStep.Wait, AgentStepParser.parse(one("wait")))
        Assert.assertEquals(AgentStep.Done("Sent"), AgentStepParser.parse(one("done", """{"summary":"Sent"}""")))
        Assert.assertEquals(AgentStep.Done(""), AgentStepParser.parse(one("done")))
        Assert.assertEquals(AgentStep.Fail("No such chat"), AgentStepParser.parse(one("fail", """{"reason":"No such chat"}""")))
    }

    @Test fun anIdMayComeAsAStringOrAWholeNumber() {
        Assert.assertEquals(AgentStep.Tap(5), AgentStepParser.parse(one("tap", """{"id":"5"}""")))
        Assert.assertEquals(AgentStep.Tap(5), AgentStepParser.parse(one("tap", """{"id":5.0}""")))
        Assert.assertNull(AgentStepParser.parse(one("tap", """{"id":5.5}""")))
    }

    @Test fun badValuesAreRejected() {
        Assert.assertNull(AgentStepParser.parse(one("tap")))
        Assert.assertNull(AgentStepParser.parse(one("tap", """{"id":0}""")))
        Assert.assertNull(AgentStepParser.parse(one("tap", """{"id":201}""")))
        Assert.assertNull(AgentStepParser.parse(one("tap", """{"id":"twelve"}""")))
        Assert.assertNull(AgentStepParser.parse(one("scroll", """{"dir":"sideways"}""")))
        Assert.assertNull(AgentStepParser.parse(one("scroll")))
        Assert.assertNull(AgentStepParser.parse(one("type")))
        Assert.assertNull(AgentStepParser.parse(one("type", """{"text":"x","id":0}""")))
    }

    @Test fun onlyAgentToolsAreAllowed() {
        for (other in listOf("call", "open_app", "flashlight", "ui_task", "unknown", "say", "wifi")) {
            Assert.assertNull("$other must not be reachable from the agent", AgentStepParser.parse(one(other, """{"app":"x","number":"123"}""")))
        }
    }

    @Test fun exactlyOneStep() {
        val tap = """{"intent":"tap","slots":{"id":1}}"""
        Assert.assertNull(AgentStepParser.parse("""{"steps":[$tap,$tap]}"""))
        Assert.assertNull(AgentStepParser.parse("""{"steps":[]}"""))
        Assert.assertNull(AgentStepParser.parse("""{"say":"hello"}"""))
        Assert.assertNull(AgentStepParser.parse("""{"steps":"tap"}"""))
        Assert.assertNull(AgentStepParser.parse("not json"))
        Assert.assertNull(AgentStepParser.parse(""))
    }

    @Test fun typedTextKeepsUpTo300CharactersAndIsCleaned() {
        val long = "w".repeat(500)
        val t = AgentStepParser.parse(one("type", JSONObject().put("text", long).toString())) as AgentStep.Type
        Assert.assertEquals(300, t.text.length)
        val c = AgentStepParser.parse(one("type", JSONObject().put("text", "hi\n\nthere\u0000").toString())) as AgentStep.Type
        Assert.assertEquals("hi there", c.text)
    }

    @Test fun httpStatusesBecomeOutcomes() {
        Assert.assertEquals(AgentOutcome.Next(AgentStep.Back), AgentStepParser.fromHttp(200, one("back")))
        fun why(code: Int, body: String = "") = (AgentStepParser.fromHttp(code, body) as AgentOutcome.Failed).why
        Assert.assertEquals(Failure.BAD_RESPONSE, why(200, "<html>"))
        Assert.assertEquals(Failure.AUTH, why(401))
        Assert.assertEquals(Failure.BUSY, why(429))
        Assert.assertEquals(Failure.TIMEOUT, why(504))
        Assert.assertEquals(Failure.SERVER, why(500))
    }

    @Test fun theRequestCarriesTheGoalTheScreenAndTheMenu() {
        val o = JSONObject(AgentRequestJson.build(AgentRequest("send hi to Rahim", "bn-BD", "2026-10-20T14:03+06:00", "com.whatsapp", "[1] button \"OK\"\n", listOf("tap [3] \"Rahim\" -> ok"))))
        Assert.assertEquals("agent", o.getString("mode"))
        Assert.assertEquals("send hi to Rahim", o.getString("goal"))
        Assert.assertEquals("com.whatsapp", o.getString("package"))
        Assert.assertEquals("[1] button \"OK\"\n", o.getString("screen"))
        Assert.assertEquals(1, o.getJSONArray("history").length())
        val names = (0 until o.getJSONArray("tools").length()).map { o.getJSONArray("tools").getJSONObject(it).getString("name") }
        Assert.assertEquals(listOf("tap", "type", "scroll", "back", "home", "wait", "done", "fail"), names)
        Assert.assertEquals(setOf("mode", "goal", "lang", "now", "package", "screen", "history", "tools"), o.keys().asSequence().toSet())
    }
}
