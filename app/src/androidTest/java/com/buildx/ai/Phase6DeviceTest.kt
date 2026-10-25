package com.buildx.ai

import android.app.AlarmManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buildx.ai.actions.ActionContext
import com.buildx.ai.nlu.CommandPipeline
import com.buildx.ai.nlu.Parsed
import com.buildx.ai.reminder.DatedAlarmScheduler
import com.buildx.ai.stt.Wav
import com.buildx.ai.voice.SpeechText
import com.buildx.ai.wake.WakePhrase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that need a real Android (an emulator or a phone): the system alarm service, real views, the real pipeline.
 * Run with: gradle connectedDebugAndroidTest. Nothing here calls, texts, toggles radios or rings anything.
 */
@RunWith(AndroidJUnit4::class)
class DatedAlarmDeviceTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before @After fun clean() { DatedAlarmScheduler.cancelAll(ctx) }

    @Test fun anAlarmForTheDayAfterTomorrowIsKnownToTheSystemAndSurvivesCancel() {
        val at = System.currentTimeMillis() + 2 * 24 * 3_600_000L
        val r = DatedAlarmScheduler.add(ctx, at, "test")
        assertNotNull("the system refused the alarm (exact-alarm permission?)", r)
        assertEquals(1, DatedAlarmScheduler.load(ctx).size)

        val next = (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock
        assertNotNull("AlarmManager does not list an alarm clock", next)
        assertTrue("the next alarm must be ours or earlier", next!!.triggerTime <= at)

        assertEquals(1, DatedAlarmScheduler.cancelAll(ctx))
        assertTrue(DatedAlarmScheduler.load(ctx).isEmpty())
    }

    @Test fun idsDoNotClash() {
        val at = System.currentTimeMillis() + 3 * 24 * 3_600_000L
        val a = DatedAlarmScheduler.add(ctx, at, "a")!!
        val b = DatedAlarmScheduler.add(ctx, at + 60_000L, "b")!!
        assertFalse(a.id == b.id)
        assertEquals(2, DatedAlarmScheduler.load(ctx).size)
    }
}

@RunWith(AndroidJUnit4::class)
class RiskGateDeviceTest {
    private val ac get() = ActionContext(ApplicationProvider.getApplicationContext(), "bn-BD")

    @Test fun aModelChosenAirplaneSwitchAsksFirstAndDoesNothingOnNo() {
        val p = Parsed("airplane", mapOf("state" to "on", "via" to "llm"), "x")
        val r = CommandPipeline.runOne(ac, p, "x")
        assertTrue("should ask: ${r.message}", r.message.trim().endsWith("করব?"))
        val follow = r.followUp
        assertNotNull(follow)
        val no = follow!!.onReply("না")
        assertEquals("ঠিক আছে, করিনি", no.message)
    }

    @Test fun anUnclearAnswerAlsoDoesNothing() {
        val p = Parsed("hotspot", mapOf("state" to "on", "via" to "llm"), "x")
        val r = CommandPipeline.runOne(ac, p, "x")
        val out = r.followUp!!.onReply("hmm")
        assertEquals("বুঝতে পারিনি, করিনি", out.message)
    }
}

@RunWith(AndroidJUnit4::class)
class MainScreenDeviceTest {

    private fun texts(v: View, out: MutableList<TextView> = ArrayList()): MutableList<TextView> {
        if (v is TextView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    // The tab bar's labels sit inside a clickable column; the page title above the pages is not clickable.
    // (This does not depend on the screen density, unlike comparing text sizes.)
    private fun tabLabels(root: View): List<TextView> =
        texts(root).filter { (it.parent as? View)?.hasOnClickListeners() == true }

    @Test fun fourTabsOpenAndTheTitleFollows() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // tab label -> the title shown above the page (home's title is the app name)
            val tabs = listOf(
                "Setup" to "Setup",
                "Settings" to "Settings",
                "Test" to "Tests and log",
                "Home" to "BuildX AI"
            )
            for ((name, expected) in tabs) {
                scenario.onActivity { act ->
                    val tab = tabLabels(act.window.decorView).firstOrNull { it.text.toString() == name }
                    assertNotNull("tab '$name' not found in the tab bar", tab)
                    (tab!!.parent as View).performClick()
                }
                scenario.onActivity { act ->
                    val all = texts(act.window.decorView).map { it.text.toString() }
                    assertTrue("title '$expected' missing after opening $name", all.contains(expected))
                }
            }
        }
    }

    @Test fun homeOffersTheListenToggleAndTheWakePhrase() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val all = texts(act.window.decorView).map { it.text.toString() }
                assertTrue(
                    "the listen toggle (Start listening / Stop listening) is missing",
                    all.any { it.equals("Start listening", ignoreCase = true) || it.equals("Stop listening", ignoreCase = true) }
                )
                assertTrue(
                    "the wake phrase is not shown on home",
                    all.any { it.contains(WakePhrase.fromPref("").display) }
                )
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class PureLogicOnDeviceTest {
    // the same code runs on the real runtime (a different Calendar, charset and locale than a laptop's)
    @Test fun wavAndSpeechTextWorkOnTheDevice() {
        assertEquals(44 + 200, Wav.encode(ShortArray(100)).size)
        assertEquals(listOf("আজ আবহাওয়া ভালো।"), SpeechText.chunks("আজ আবহাওয়া ভালো।"))
        assertEquals("hey jarvis", (WakePhrase.parse("Hey Jarvis", null) as WakePhrase.Parse.Ok).phrase.text)
    }
}
