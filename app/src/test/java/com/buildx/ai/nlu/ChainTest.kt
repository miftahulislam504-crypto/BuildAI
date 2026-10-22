package com.buildx.ai.nlu

import org.junit.Assert
import org.junit.Test

class ChainSplitterTest {
    /** "intent[key=value,...]" per step, in order */
    private fun steps(text: String): List<String> = IntentParser.parseChain(text).map { p ->
        p.intent + if (p.slots.isEmpty()) "" else p.slots.entries.filter { it.key != "text" }.joinToString(",", "[", "]") { "${it.key}=${it.value}" }
    }

    private fun intents(text: String): List<String> = IntentParser.parseChain(text).map { it.intent }

    private fun check(text: String, vararg expected: String) {
        val got = steps(text)
        Assert.assertEquals("steps of \"$text\": $got", expected.toList().size, got.size)
        for (i in expected.indices) {
            Assert.assertTrue("step ${i + 1} of \"$text\": expected ${expected[i]} but was ${got[i]}", got[i].startsWith(expected[i].removeSuffix("]")))
        }
    }

    @Test fun openThenVolume() = check("ইউটিউব খুলে ভলিউম কমাও", "open_app[app=youtube", "volume[action=down")
    @Test fun withAar() = check("ফ্ল্যাশলাইট জ্বালাও আর ভলিউম কমাও", "flashlight[state=on", "volume[action=down")
    @Test fun participleVerbs() = check("ওয়াইফাই বন্ধ করে ব্লুটুথ চালু করো", "wifi[state=off", "bluetooth[state=on")
    @Test fun alarmArgsBeforeTrigger() = check("কাল সকাল ৬টায় অ্যালার্ম দাও আর ফ্ল্যাশলাইট জ্বালাও", "alarm[hour=6,minute=0,ambiguous=false,when=tomorrow", "flashlight[state=on")
    @Test fun threeSteps() = check("টর্চ জ্বালাও আর ভলিউম পঞ্চাশ করো তারপর ইউটিউব খোলো", "flashlight[state=on", "volume[action=set,level=50", "open_app[app=youtube")
    @Test fun englishAnd() = check("open youtube and set volume to 30", "open_app[app=youtube", "volume[action=set,level=30")
    @Test fun englishTurnOnTurnOff() = check("turn on the flashlight and turn off wifi", "flashlight[state=on", "wifi[state=off")
    @Test fun englishThen() = check("lock the screen then turn off bluetooth", "screen_lock", "bluetooth[state=off")
    @Test fun noSeparatorWordsBelongToNextCommand() = check("ভলিউম কমাও রহিমকে ফোন করো", "volume[action=down", "call[target=রহিম")
    @Test fun lockThenWifi() = check("স্ক্রিন লক করো আর ওয়াইফাই বন্ধ করো", "screen_lock", "wifi[state=off")
    @Test fun volumeThenTime() = check("ভলিউম কমাও আর কয়টা বাজে", "volume[action=down", "time")
    @Test fun callThenNothingElse() = check("বাবাকে ফোন করো আর ভলিউম কমাও", "call[target=বাবা", "volume[action=down")
    @Test fun callWithoutSeparator() = check("বাবাকে ফোন করো ভলিউম কমাও", "call[target=বাবা", "volume[action=down")
    @Test fun twoAppsInARow() = check("ইউটিউব খুলে ফেসবুক খোলো", "open_app[app=youtube", "open_app[app=facebook")
    @Test fun sameThingTwice() = check("ভলিউম কমাও আর ভলিউম বাড়াও", "volume[action=down", "volume[action=up")
    @Test fun wakeMeIsStillAnAlarm() = check("ভোর ৫টায় আমাকে জাগিয়ে দিও আর টর্চ জ্বালাও", "alarm[hour=5", "flashlight[state=on")
    @Test fun unknownStepIsKept() = check("টর্চ জ্বালাও আর জোক বলো", "flashlight[state=on", "unknown")

    // single commands must stay single
    @Test fun singleCommands() {
        for (s in listOf(
            "ওয়াইফাই সেটিংস খোলো", "ফ্ল্যাশলাইট চালু করো", "ভলিউম ৫০ করো", "আলোকে ফোন করো", "ছোট ভাইকে কল করো",
            "turn on the flashlight", "কাল সকাল ৬টায় অ্যালার্ম দাও", "ভলিউম মিউট করো", "লক স্ক্রিন দেখাও",
            "ব্লু টুথ সেটিংস খোলো", "ফোন আনলক করো", "এখন কয়টা বাজে", "ইউটিউব অ্যাপটা খুলে দাও"
        )) Assert.assertEquals(s, 1, intents(s).size)
    }

    @Test fun singleCommandSameResultAsParse() {
        for (s in listOf("টর্চ জ্বালাও", "রহিমকে ফোন করো", "কাল সকাল ৬টায় অ্যালার্ম দাও")) {
            Assert.assertEquals(IntentParser.parse(s), IntentParser.parseChain(s).single())
        }
    }

    @Test fun trailingConnectorIsIgnored() = Assert.assertEquals(listOf("flashlight"), intents("টর্চ জ্বালাও আর"))
    @Test fun emptyStaysOneUnknown() = Assert.assertEquals(listOf("unknown"), intents(""))
}
