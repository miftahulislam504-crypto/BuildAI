package com.buildx.ai.system

import org.junit.Assert
import org.junit.Test

class ConversationMemorySummaryTest {
    private var now = 0L
    private fun mem() = ConversationMemory({ now }, 10 * 60_000L)

    @Test fun nothingDoneYetMeansNothingToSend() {
        Assert.assertTrue(mem().safeSummary().isEmpty())
    }

    @Test fun newestFirstAndNeverANameANumberOrText() {
        val m = mem()
        now = 1000; m.record("open_app", mapOf("app" to "youtube", "known" to "true"))
        now = 2000; m.record("wifi", mapOf("state" to "on"))
        now = 3000; m.record("send_sms", mapOf("target" to "রহিম", "text" to "আসছি", "number" to "01711223344"))
        val s = m.safeSummary()
        Assert.assertEquals(listOf("send_sms", "wifi state=on", "open_app app=youtube"), s)
        Assert.assertTrue(s.none { it.contains("রহিম") || it.contains("আসছি") || it.contains("0171") })
    }

    @Test fun theSameStepIsListedOnce() {
        val m = mem()
        now = 1000; m.record("wifi", mapOf("state" to "off"))
        Assert.assertEquals(listOf("wifi state=off"), m.safeSummary())
    }

    @Test fun searchesAndNotesStayOnThePhone() {
        val m = mem()
        now = 1000; m.record("web_search", mapOf("query" to "আমার অসুখ"))
        Assert.assertEquals(listOf("web_search"), m.safeSummary())
        now = 2000; m.record("note", mapOf("op" to "add", "text" to "গোপন কথা"))
        val s = m.safeSummary() // only the newest step is remembered; the search is gone
        Assert.assertEquals(listOf("note"), s)
        Assert.assertTrue(s.none { it.contains("গোপন") || it.contains("অসুখ") })
    }

    @Test fun oldThingsAreForgottenAndEveryLineFitsTheProxyLimit() {
        val m = mem()
        now = 1000; m.record("open_app", mapOf("app" to "ক".repeat(200)))
        Assert.assertTrue(m.safeSummary().all { it.length <= 100 })
        now = 1000 + 10 * 60_000L + 1
        Assert.assertTrue(m.safeSummary().isEmpty())
    }
}
