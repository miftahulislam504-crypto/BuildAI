package com.buildx.ai.system

import com.buildx.ai.nlu.Lexicon
import org.junit.Assert
import org.junit.Test

class VolumeMathTest {
    @Test fun percentConversion() {
        Assert.assertEquals(8, VolumeMath.percentToIndex(50, 15))
        Assert.assertEquals(15, VolumeMath.percentToIndex(100, 15))
        Assert.assertEquals(0, VolumeMath.percentToIndex(0, 15))
        Assert.assertEquals(15, VolumeMath.percentToIndex(250, 15))
        Assert.assertEquals(53, VolumeMath.indexToPercent(8, 15))
        Assert.assertEquals(0, VolumeMath.indexToPercent(3, 0))
    }
    @Test fun setLevel() {
        Assert.assertEquals(8, VolumeMath.target(2, 15, "set", 50, null))
        Assert.assertEquals(15, VolumeMath.target(2, 15, "set", 100, null))
        Assert.assertEquals(0, VolumeMath.target(9, 15, "set", 0, null))
    }
    @Test fun upDownDefaultStep() {
        Assert.assertEquals(8, VolumeMath.target(5, 15, "up", null, null))
        Assert.assertEquals(2, VolumeMath.target(5, 15, "down", null, null))
    }
    @Test fun upDownByPercent() {
        Assert.assertEquals(7, VolumeMath.target(5, 15, "up", null, 10))
        Assert.assertEquals(4, VolumeMath.target(8, 15, "down", null, 25))
    }
    @Test fun alwaysMovesAtLeastOneStep() {
        Assert.assertEquals(6, VolumeMath.target(5, 15, "up", null, 1))
        Assert.assertEquals(4, VolumeMath.target(5, 15, "down", null, 1))
    }
    @Test fun clampsAtEnds() {
        Assert.assertEquals(15, VolumeMath.target(14, 15, "up", null, null))
        Assert.assertEquals(0, VolumeMath.target(1, 15, "down", null, null))
        Assert.assertEquals(15, VolumeMath.target(15, 15, "up", null, null))
    }
    @Test fun unmuteRestores() {
        Assert.assertEquals(9, VolumeMath.restore(9, 15))
        Assert.assertEquals(8, VolumeMath.restore(0, 15))
        Assert.assertEquals(15, VolumeMath.restore(40, 15))
    }
}

class AlarmMathTest {
    private fun plan(now: String, at: String, amb: Boolean, slot: String): AlarmPlan {
        val (nh, nm) = now.split(":").map { it.toInt() }
        val (h, m) = at.split(":").map { it.toInt() }
        return AlarmMath.plan(nh, nm, h, m, amb, slot)
    }

    @Test fun tomorrowMorningFromEvening() {
        val p = plan("22:00", "06:00", false, "tomorrow")
        Assert.assertEquals(6, p.hour)
        Assert.assertEquals(480, p.minutesUntil)
        Assert.assertTrue(!p.firesToday)
        Assert.assertTrue(!p.mismatch)
    }
    @Test fun tomorrowSaidAtNightAfterMidnightWarns() {
        val p = plan("01:00", "06:00", false, "tomorrow")
        Assert.assertTrue(p.firesToday)
        Assert.assertTrue(p.mismatch)
    }
    @Test fun autoPicksNextOccurrence() {
        val p = plan("23:00", "06:00", false, "auto")
        Assert.assertEquals(420, p.minutesUntil)
        Assert.assertTrue(!p.mismatch)
    }
    @Test fun ambiguousPicksTheSoonerOfAmAndPm() {
        Assert.assertEquals(18, plan("14:00", "06:00", true, "auto").hour)
        Assert.assertEquals(6, plan("20:00", "06:00", true, "auto").hour)
        Assert.assertEquals(6, plan("03:00", "06:00", true, "auto").hour)
    }
    @Test fun ambiguousTomorrowMeansMorning() = Assert.assertEquals(6, plan("14:00", "06:00", true, "tomorrow").hour)
    @Test fun ambiguousTwelveTomorrowMeansNoon() = Assert.assertEquals(12, plan("14:00", "12:00", true, "tomorrow").hour)
    @Test fun todayThatAlreadyPassedWarns() {
        val p = plan("10:00", "06:00", false, "today")
        Assert.assertTrue(p.mismatch)
        Assert.assertTrue(!p.firesToday)
    }
    @Test fun todayLaterIsFine() {
        val p = plan("10:00", "23:00", false, "today")
        Assert.assertTrue(p.firesToday)
        Assert.assertTrue(!p.mismatch)
        Assert.assertEquals(780, p.minutesUntil)
    }
    @Test fun dayAfterTomorrowAlwaysWarns() = Assert.assertTrue(plan("10:00", "06:00", false, "day_after").mismatch)
    @Test fun sameMinuteMeansNextDay() = Assert.assertEquals(1440, plan("06:00", "06:00", false, "auto").minutesUntil)
    @Test fun twentyFourHourTimeKept() = Assert.assertEquals(22, plan("10:00", "22:30", false, "auto").hour)
}

class AlarmWordsTest {
    @Test fun morning() = Assert.assertEquals("সকাল ৬টা", AlarmWords.clock(6, 0, true))
    @Test fun withMinutes() = Assert.assertEquals("সকাল ৭টা ৩০ মিনিট", AlarmWords.clock(7, 30, true))
    @Test fun dawn() = Assert.assertEquals("ভোর ৫টা", AlarmWords.clock(5, 0, true))
    @Test fun noon() = Assert.assertEquals("দুপুর ১২টা", AlarmWords.clock(12, 0, true))
    @Test fun afternoon() = Assert.assertEquals("বিকাল ৪টা", AlarmWords.clock(16, 0, true))
    @Test fun evening() = Assert.assertEquals("সন্ধ্যা ৬টা", AlarmWords.clock(18, 0, true))
    @Test fun night() = Assert.assertEquals("রাত ১০টা", AlarmWords.clock(22, 0, true))
    @Test fun midnight() = Assert.assertEquals("রাত ১২টা", AlarmWords.clock(0, 0, true))
    @Test fun english() = Assert.assertEquals("6:05 PM", AlarmWords.clock(18, 5, false))
    @Test fun durationBn() {
        Assert.assertEquals("৭ ঘণ্টা ২০ মিনিট পরে", AlarmWords.duration(440, true))
        Assert.assertEquals("৩০ মিনিট পরে", AlarmWords.duration(30, true))
        Assert.assertEquals("২ ঘণ্টা পরে", AlarmWords.duration(120, true))
    }
    @Test fun durationEn() = Assert.assertEquals("in 1 h 5 min", AlarmWords.duration(65, false))
}

class AppCatalogTest {
    @Test fun everyLexiconAppKeyHasASpec() {
        for (k in Lexicon.appKeys) Assert.assertNotNull("no spec for $k", AppCatalog.spec(k))
    }
    @Test fun names() {
        Assert.assertEquals("ইউটিউব", AppCatalog.name("youtube", true))
        Assert.assertEquals("YouTube", AppCatalog.name("youtube", false))
        Assert.assertEquals("somethingelse", AppCatalog.name("somethingelse", true))
    }

    private val installed = listOf(
        Launchable("Foodpanda", "com.foodpanda"), Launchable("ফুডপান্ডা", "com.fp.bn"), Launchable("Pathao", "com.pathao"),
        Launchable("Daraz", "com.daraz"), Launchable("Uber", "com.uber"), Launchable("Prayer Times", "com.prayer"),
        Launchable("Quran Majeed", "com.quran"), Launchable("My Files", "com.files")
    )

    @Test fun findsByBanglaSpellingOfEnglishLabel() {
        Assert.assertEquals("com.pathao", AppCatalog.findByLabel("পাঠাও", installed).single().pkg)
        Assert.assertEquals("com.daraz", AppCatalog.findByLabel("দারাজ", installed).single().pkg)
        Assert.assertEquals("com.uber", AppCatalog.findByLabel("উবার", installed).single().pkg)
    }
    @Test fun findsPartOfALabel() {
        Assert.assertEquals("com.quran", AppCatalog.findByLabel("কুরআন", installed).single().pkg)
    }
    @Test fun nothingFound() = Assert.assertTrue(AppCatalog.findByLabel("zzzzz", installed).isEmpty())
}
