package com.buildx.ai.nlu

import org.junit.Assert
import org.junit.Test

class TimeParserTest {
    private fun clock(s: String, fallback: Boolean = true) =
        TimeParser.clock(Canonicalizer.tokenize(TextNormalizer.normalize(s)).map { it.canon }, fallback)

    private fun rel(s: String) =
        TimeParser.relativeMinutes(Canonicalizer.tokenize(TextNormalizer.normalize(s)).map { it.canon })

    private fun at(s: String, h: Int, m: Int, amb: Boolean) {
        val c = clock(s)
        Assert.assertNotNull("no time in \"$s\"", c)
        Assert.assertEquals("hour of \"$s\"", h, c!!.hour)
        Assert.assertEquals("minute of \"$s\"", m, c.minute)
        Assert.assertEquals("ambiguous of \"$s\"", amb, c.ambiguous)
    }

    @Test fun morning6() = at("সকাল ছয়টায়", 6, 0, false)
    @Test fun morning7_30() = at("সকাল ৭টা ৩০ মিনিটে", 7, 30, false)
    @Test fun night10() = at("রাত ১০টায়", 22, 0, false)
    @Test fun night2() = at("রাত ২টায়", 2, 0, false)
    @Test fun night12() = at("রাত ১২টায়", 0, 0, false)
    @Test fun noon2() = at("দুপুর ২টায়", 14, 0, false)
    @Test fun noon12() = at("দুপুর ১২টায়", 12, 0, false)
    @Test fun afternoon4() = at("বিকাল ৪টায়", 16, 0, false)
    @Test fun evening6() = at("সন্ধ্যা ৬টায়", 18, 0, false)
    @Test fun dawn5() = at("ভোর ৫টায়", 5, 0, false)
    @Test fun halfPast6() = at("সাড়ে ছয়টায়", 6, 30, true)
    @Test fun quarterPast8() = at("সওয়া আটটায়", 8, 15, true)
    @Test fun quarterTo7() = at("পৌনে সাতটায়", 6, 45, true)
    @Test fun quarterTo1() = at("পৌনে একটায়", 12, 45, true)
    @Test fun der() = at("দেড়টায়", 1, 30, true)
    @Test fun arai() = at("আড়াইটায়", 2, 30, true)
    @Test fun colonPm() = at("6:30 pm", 18, 30, false)
    @Test fun sixAm() = at("6 am", 6, 0, false)
    @Test fun sixThirtyPmNoColon() = at("6 30 pm", 18, 30, false)
    @Test fun spokenEnglishHalfPast() = at("half past six", 6, 30, true)
    @Test fun spokenEnglishQuarterTo() = at("quarter to seven", 6, 45, true)
    @Test fun twentyFourHour() = at("18টা ৩০", 18, 30, false)
    @Test fun bareHourWithPeriod() = at("সকাল 6", 6, 0, false)
    @Test fun fallbackBareHour() = at("at 6", 6, 0, true)
    @Test fun anAlarmIsNotOneOClock() = Assert.assertNull(clock("একটা অ্যালার্ম দাও"))
    @Test fun oneOClockWithSuffixTay() = at("একটায় অ্যালার্ম", 1, 0, true)
    @Test fun noTime() = Assert.assertNull(clock("অ্যালার্ম দাও"))
    @Test fun quantityIsNotATime() = Assert.assertNull(clock("10 মিনিট পরে"))
    @Test fun relativeMinutes() = Assert.assertEquals(10, rel("১০ মিনিট পরে"))
    @Test fun relativeHour() = Assert.assertEquals(60, rel("১ ঘণ্টা পরে"))
    @Test fun relativeHalfHour() = Assert.assertEquals(30, rel("আধা ঘণ্টা পর"))
    @Test fun relativeOneAndHalfHour() = Assert.assertEquals(90, rel("দেড় ঘণ্টা পরে"))
    @Test fun relativeMixed() = Assert.assertEquals(90, rel("১ ঘণ্টা ৩০ মিনিট পরে"))
    @Test fun relativeEnglish() = Assert.assertEquals(15, rel("in 15 minutes"))
    @Test fun relativeNone() = Assert.assertNull(rel("সকাল ৬টায়"))
}
