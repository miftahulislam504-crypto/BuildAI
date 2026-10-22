package com.buildx.ai.phase4

import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.T.ok
import org.junit.Assert
import org.junit.Test

class RoutineParseTest {
    @Test fun create() {
        ok("রুটিন বানাও গুড মর্নিং", "routine", "op=save", "name=গুড মর্নিং")
        ok("গুড মর্নিং রুটিন তৈরি করো", "routine", "op=save", "name=গুড মর্নিং")
        ok("create a routine called leaving home", "routine", "op=save", "name=called leaving home")
        ok("রুটিন বানাও", "routine", "op=save")
    }
    @Test fun saveTheLastCommand() {
        ok("আগের কমান্ড রুটিন হিসেবে সেভ করো", "routine", "op=save", "source=last")
    }
    @Test fun run() {
        ok("গুড মর্নিং রুটিন চালাও", "routine", "op=run", "name=গুড মর্নিং")
        ok("run the work routine", "routine", "op=run", "name=work")
    }
    @Test fun listAndDelete() {
        ok("আমার রুটিনগুলো কী কী", "routine", "op=list")
        ok("list my routines", "routine", "op=list")
        ok("গুড মর্নিং রুটিন মুছে ফেলো", "routine", "op=delete", "name=গুড মর্নিং")
    }
    @Test fun triggers() {
        ok("প্রতিদিন সকাল ৭টায় গুড মর্নিং রুটিন চালাও", "routine", "op=schedule", "hour=7", "minute=0", "name=গুড মর্নিং")
        ok("চার্জে দিলে নাইট রুটিন চালাও", "routine", "op=power", "event=plug", "name=নাইট")
        ok("চার্জার খুললে ওয়ার্ক রুটিন চালাও", "routine", "op=power", "event=unplug", "name=ওয়ার্ক")
        ok("গাড়ি রুটিন ব্লুটুথ কানেক্ট হলে চালাও", "routine", "op=bt", "event=connect", "name=গাড়ি")
        ok("গুড মর্নিং রুটিনের ট্রিগার বন্ধ করো", "routine", "op=untrigger")
    }
    @Test fun routineWordIsOneCommand() {
        val steps = IntentParser.parseChain("রুটিন বানাও রুটি আর ডিম")
        Assert.assertEquals(1, steps.size)
    }
}

class ReferenceParseTest {
    @Test fun againIsNotAName() {
        val p = IntentParser.parse("ওকে আবার কল করো")
        Assert.assertEquals("call", p.intent)
        Assert.assertTrue(p.slots["target"], com.buildx.ai.nlu.Reference.isPronoun(p.slots["target"]))
        val q = IntentParser.parse("আবার কল করো")
        Assert.assertEquals("call", q.intent)
        Assert.assertNull(q.slots["target"])
    }
    @Test fun existingBehaviourIsKept() {
        ok("আবার চালু করো", "media")
        ok("ইউটিউব খোলো", "open_app", "app=youtube")
    }
}
