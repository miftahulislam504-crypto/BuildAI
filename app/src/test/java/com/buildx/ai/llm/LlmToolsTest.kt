package com.buildx.ai.llm

import com.buildx.ai.actions.ActionRegistry
import org.junit.Assert
import org.junit.Test

class LlmToolsTest {
    /** actions that exist for the app's own use and must never be reachable from the model */
    private val internal = setOf("unknown", "echo", "cancel")

    @Test fun everyToolIsARegisteredAction() {
        for (t in LlmTools.all) {
            Assert.assertNotNull("tool ${t.name} has no action", ActionRegistry.get(t.name))
        }
    }

    @Test fun everyActionIsEitherAToolOrDeliberatelyInternal() {
        // a new action must be added to LlmTools or to [internal] on purpose, not forgotten
        val actions = ActionRegistry.names().toSet() - internal
        Assert.assertEquals(actions, LlmTools.all.map { it.name }.toSet())
    }

    @Test fun toolNamesAreUnique() {
        Assert.assertEquals(LlmTools.all.size, LlmTools.all.map { it.name }.toSet().size)
    }

    @Test fun theSchemaHasTheShapeTheProxyForwards() {
        val tools = LlmTools.toJson()
        val byName = (0 until tools.length()).map { tools.getJSONObject(it) }.associateBy { it.getString("name") }

        val alarm = byName.getValue("alarm").getJSONObject("input_schema")
        Assert.assertEquals("object", alarm.getString("type"))
        val hour = alarm.getJSONObject("properties").getJSONObject("hour")
        Assert.assertEquals("integer", hour.getString("type"))
        Assert.assertEquals(0, hour.getInt("minimum"))
        Assert.assertEquals(23, hour.getInt("maximum"))
        Assert.assertFalse(alarm.has("required"))

        val volume = byName.getValue("volume").getJSONObject("input_schema")
        Assert.assertEquals("action", volume.getJSONArray("required").getString(0))
        val choices = volume.getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        Assert.assertEquals(listOf("up", "down", "set", "mute", "unmute"), (0 until choices.length()).map { choices.getString(it) })

        Assert.assertTrue(byName.getValue("time").getJSONObject("input_schema").getJSONObject("properties").length() == 0)
        Assert.assertTrue(LlmTools.all.all { it.description.isNotBlank() })
    }
}
