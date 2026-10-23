package com.buildx.ai.device

import org.junit.Assert
import org.junit.Test

class OemGuideTest {
    @Test fun detectsMakers() {
        Assert.assertEquals(Maker.XIAOMI, OemGuide.detect("Xiaomi", "Redmi"))
        Assert.assertEquals(Maker.XIAOMI, OemGuide.detect("Xiaomi", "POCO"))
        Assert.assertEquals(Maker.XIAOMI, OemGuide.detect("Redmi", "redmi"))
        Assert.assertEquals(Maker.SAMSUNG, OemGuide.detect("samsung", "samsung"))
        Assert.assertEquals(Maker.MOTOROLA, OemGuide.detect("motorola", "motorola"))
        Assert.assertEquals(Maker.GOOGLE, OemGuide.detect("Google", "google"))
        Assert.assertEquals(Maker.OPPO, OemGuide.detect("realme", "realme"))
        Assert.assertEquals(Maker.OPPO, OemGuide.detect("OnePlus", "OnePlus"))
        Assert.assertEquals(Maker.VIVO, OemGuide.detect("vivo", "vivo"))
        Assert.assertEquals(Maker.HUAWEI, OemGuide.detect("HUAWEI", "HONOR"))
        Assert.assertEquals(Maker.OTHER, OemGuide.detect("Nokia", "Nokia"))
    }
    @Test fun everyMakerHasStepsAndTheLockTip() {
        for (m in Maker.values()) {
            val g = OemGuide.guide(m)
            Assert.assertTrue("$m has no steps", g.steps.size >= 3)
            Assert.assertTrue("$m lacks the recents-lock tip", g.steps.any { "Lock" in it })
            Assert.assertEquals(m, g.maker)
        }
    }
    @Test fun redmiGuideCoversTheThreeSettingsThePlanNames() {
        val text = OemGuide.format(OemGuide.forDevice("Xiaomi", "Redmi"))
        Assert.assertTrue("Autostart" in text)
        Assert.assertTrue("No restrictions" in text)
        Assert.assertTrue("pop-up" in text)
    }
    @Test fun formatNumbersTheSteps() {
        val text = OemGuide.format(OemGuide.guide(Maker.GOOGLE))
        Assert.assertTrue(text.contains("\n1. ") && text.contains("\n2. "))
    }
}

class ChecklistTest {
    @Test fun idsAreUnique() = Assert.assertEquals(Checklist.items.size, Checklist.items.map { it.id }.toSet().size)
    @Test fun coversPhasesZeroToNineAndTheRoadmapPhasesFiveAndSix() = Assert.assertEquals(((0..9) + listOf(11, 12)).toSet(), Checklist.items.map { it.phase }.toSet())
    @Test fun phasesAreInOrder() = Assert.assertEquals(Checklist.items.map { it.phase }.sorted(), Checklist.items.map { it.phase })
    @Test fun summaryCounts() {
        val r = mapOf("wake10" to Checklist.Result.PASS, "time" to Checklist.Result.FAIL, "flash" to Checklist.Result.PASS)
        val (p, f, n) = Checklist.summary(r)
        Assert.assertEquals(2, p)
        Assert.assertEquals(1, f)
        Assert.assertEquals(Checklist.items.size - 3, n)
    }
    @Test fun formatShowsMarksPerPhase() {
        val text = Checklist.format("Xiaomi Redmi Note 12", mapOf("wake10" to Checklist.Result.PASS, "lock" to Checklist.Result.FAIL))
        Assert.assertTrue(text.startsWith("চেকলিস্ট: Xiaomi Redmi Note 12"))
        Assert.assertTrue(text.contains("✓ \"Hey BuildX\" ১০ বারে"))
        Assert.assertTrue(text.contains("✗ স্ক্রিন লক হয়"))
        Assert.assertTrue(text.contains("- ফ্ল্যাশলাইট"))
        Assert.assertTrue(text.contains("ফেজ 5"))
    }
}

class DeviceReportTest {
    private fun info(crash: String? = null, died: Boolean = false) = DeviceInfo(
        "Xiaomi", "Redmi", "23053RN02A", 33, "13", "0.7.0 (8)", "bn-BD", "মাঝারি",
        linkedMapOf("মাইক" to true, "কল" to false), true, died, if (died) 2 else 0, crash, listOf("12:00 WAKE", "12:01 CMD")
    )

    @Test fun containsTheEssentials() {
        val t = DeviceReport.format(info())
        Assert.assertTrue(t.contains("Xiaomi 23053RN02A (Redmi), Android 13 (SDK 33), গ্রুপ: XIAOMI"))
        Assert.assertTrue(t.contains("✓ মাইক") && t.contains("✗ কল"))
        Assert.assertTrue(t.contains("ক্র্যাশ: নেই"))
        Assert.assertTrue(t.contains("12:00 WAKE"))
        Assert.assertTrue(t.contains("সার্ভিস এখন: চলছে"))
    }
    @Test fun saysWhenTheServiceDied() {
        val t = DeviceReport.format(info(died = true))
        Assert.assertTrue(t.contains("হ্যাঁ, গতবার") && t.contains("মোট 2 বার"))
    }
    @Test fun crashIsShortened() {
        val long = (1..40).joinToString("\n") { "at line $it" }
        val t = DeviceReport.format(info(crash = long))
        Assert.assertTrue(t.contains("শেষ ক্র্যাশ:") && t.contains("at line 12") && !t.contains("at line 13"))
    }
}

class CrashFormatTest {
    @Test fun entryHasThreadAndStack() {
        val e = CrashFormat.entry(0L, "main", IllegalStateException("boom"), "v1")
        Assert.assertTrue(e.startsWith("=== 19") || e.startsWith("=== 20"))
        Assert.assertTrue(e.contains("thread=main") && e.contains("v1") && e.contains("IllegalStateException: boom"))
    }
    @Test fun smallFilesGrowNormally() = Assert.assertEquals("=== a\n=== b\n", CrashFormat.append("=== a\n", "=== b\n"))
    @Test fun bigFilesKeepTheNewestAtAnEntryBoundary() {
        val old = ("=== old\n" + "x".repeat(300) + "\n").repeat(100)
        val out = CrashFormat.append(old, "=== newest\nboom\n")
        Assert.assertTrue(out.length <= CrashFormat.MAX_CHARS)
        Assert.assertTrue(out.startsWith("=== "))
        Assert.assertTrue(out.endsWith("=== newest\nboom\n"))
    }
}
