package com.buildx.ai.nlu

import org.junit.Assert
import org.junit.Test

/** Back / Home / Recents / notifications / screenshot / read the screen, and "tap X" / "type Y". */
class OnScreenParserTest {
    private fun intent(s: String) = IntentParser.parse(s).intent
    private fun text(s: String) = IntentParser.parse(s).slots["text"]

    @Test fun back() {
        for (s in listOf("ব্যাক", "পেছনে যাও", "পিছনে যান", "go back", "back", "back please", "ব্যাক করো", "প্লিজ ব্যাক করো", "ব্যাক বাটন চাপো")) {
            Assert.assertEquals(s, "back", intent(s))
        }
    }

    @Test fun home() {
        for (s in listOf("হোমে যাও", "হোম স্ক্রিনে যাও", "হোম স্ক্রিন", "go home", "home screen", "go to the home screen", "home", "হোম")) {
            Assert.assertEquals(s, "home", intent(s))
        }
    }

    @Test fun recents() {
        for (s in listOf("রিসেন্ট অ্যাপস দেখাও", "রিসেন্ট অ্যাপ", "show recent apps", "recents", "open recent apps")) {
            Assert.assertEquals(s, "recents", intent(s))
        }
    }

    @Test fun notifications() {
        for (s in listOf("নোটিফিকেশন দেখাও", "নোটিফিকেশন প্যানেল খোলো", "show notifications", "open notifications", "notification panel")) {
            Assert.assertEquals(s, "notifications", intent(s))
        }
    }

    @Test fun screenshot() {
        for (s in listOf("স্ক্রিনশট নাও", "স্ক্রিনশট", "একটা স্ক্রিনশট নিন", "take a screenshot", "screenshot", "take screenshot please")) {
            Assert.assertEquals(s, "screenshot", intent(s))
        }
    }

    @Test fun readTheScreen() {
        for (s in listOf("স্ক্রিনে কী আছে", "স্ক্রিন পড়ে শোনাও", "read the screen", "read screen", "what's on the screen", "what is on my screen")) {
            Assert.assertEquals(s, "read_screen", intent(s))
        }
    }

    // ---------- a sentence that merely contains those words is not that command ----------

    @Test fun aLongerSentenceIsNotAButtonPress() {
        for (s in listOf("I want to go back to my home town", "ফ্রান্সের রাজধানীতে হোম ম্যাচ কবে", "tell me about recent news", "do I have any notifications from rahim", "screenshot of the weather")) {
            val i = intent(s)
            Assert.assertTrue("$s -> $i", i !in setOf("back", "home", "recents", "notifications", "screenshot", "read_screen"))
        }
    }

    @Test fun twoDifferentButtonsInOneSentenceAreNotGuessed() {
        Assert.assertEquals("unknown", intent("back home"))
    }

    @Test fun theBackCameraIsStillACamera() {
        val p = IntentParser.parse("back camera দিয়ে ছবি তোলো")
        Assert.assertEquals("take_photo", p.intent)
        Assert.assertEquals("back", p.slots["camera"])
    }

    @Test fun existingCommandsAreUntouched() {
        Assert.assertEquals("flashlight", intent("ফ্ল্যাশলাইট জ্বালাও"))
        Assert.assertEquals("swipe", intent("scroll down"))
        Assert.assertEquals("swipe", intent("স্ক্রল করো"))
        Assert.assertEquals("screen_lock", intent("স্ক্রিন লক করো"))
        Assert.assertEquals("open_app", intent("settings খোলো"))
        Assert.assertEquals("media", intent("পরের গান"))
        Assert.assertEquals("take_photo", intent("ছবি তোলো"))
    }

    @Test fun buttonsChainWithOtherCommands() {
        Assert.assertEquals(listOf("back", "home"), IntentParser.parseChain("ব্যাক আর হোমে যাও").map { it.intent })
        Assert.assertEquals(listOf("screenshot", "flashlight"), IntentParser.parseChain("স্ক্রিনশট নাও এবং ফ্ল্যাশলাইট জ্বালাও").map { it.intent })
    }

    // ---------- tap ----------

    @Test fun tapNamesWhatToTap() {
        Assert.assertEquals("send", text("tap send"))
        Assert.assertEquals("send", text("tap on send"))
        Assert.assertEquals("compose", text("click on the compose"))
        Assert.assertEquals("সেন্ড", text("ট্যাপ করো সেন্ড"))
        Assert.assertEquals("সেন্ড", text("সেন্ড এ ট্যাপ করো"))
        Assert.assertEquals("সেন্ড", text("সেন্ড ট্যাপ করো"))
        for (s in listOf("tap send", "ট্যাপ করো সেন্ড", "সেন্ড এ ট্যাপ করো")) Assert.assertEquals(s, "tap", intent(s))
    }

    @Test fun tapWithNothingToTapAsksWhat() {
        val p = IntentParser.parse("ট্যাপ করো")
        Assert.assertEquals("tap", p.intent)
        Assert.assertNull(p.slots["text"])
    }

    @Test fun tapMustBeAtTheEdgeOfTheSentence() {
        Assert.assertEquals("unknown", intent("the tap in my kitchen is broken today"))
    }

    // ---------- type ----------

    @Test fun typeKeepsTheWordsAsSaid() {
        Assert.assertEquals("hello world", text("type hello world"))
        Assert.assertEquals("আমি আসছি", text("টাইপ করো আমি আসছি"))
        Assert.assertEquals("আমি আসছি", text("আমি আসছি টাইপ করো"))
        Assert.assertEquals("my name is rahim", text("type my name is rahim"))
        for (s in listOf("type hello", "টাইপ করো hello", "hello টাইপ করো")) Assert.assertEquals(s, "type_text", intent(s))
    }

    @Test fun typeWithNothingToTypeAsksWhat() {
        val p = IntentParser.parse("টাইপ করো")
        Assert.assertEquals("type_text", p.intent)
        Assert.assertNull(p.slots["text"])
    }

    @Test fun theWordTypeInTheMiddleOfAQuestionIsNotACommand() {
        Assert.assertEquals("unknown", intent("what type of phone is this"))
    }

    @Test fun otherIntentsKeepTheirWordsEvenNextToTapOrPress() {
        Assert.assertTrue(intent("press play") !in setOf("tap", "type_text"))
        Assert.assertEquals("take_photo", intent("click a photo"))
    }

    @Test fun everyNewIntentHasASpokenDescription() {
        for (i in listOf("back", "home", "recents", "notifications", "screenshot", "read_screen", "tap", "type_text", "ui_task")) {
            Assert.assertTrue(i, Describe.say(Parsed(i, mapOf("text" to "x", "goal" to "y"), ""), true).isNotBlank())
            Assert.assertTrue(i, Describe.say(Parsed(i, mapOf("text" to "x", "goal" to "y"), ""), false).isNotBlank())
        }
    }
}
