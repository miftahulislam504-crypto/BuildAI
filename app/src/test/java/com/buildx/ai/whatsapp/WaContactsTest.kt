package com.buildx.ai.whatsapp

import com.buildx.ai.call.CallFlow
import com.buildx.ai.call.CallPlacer
import com.buildx.ai.call.ContactSource
import com.buildx.ai.call.PlaceOutcome
import com.buildx.ai.contacts.ContactEntry
import com.buildx.ai.contacts.PhoneEntry
import com.buildx.ai.contacts.PhoneNumbers
import org.junit.Assert
import org.junit.Test

class WaContactsTest {
    @Test fun mimeTypes() {
        Assert.assertEquals("vnd.android.cursor.item/vnd.com.whatsapp.voip.call", WaContacts.mime(WaContacts.PERSONAL, false))
        Assert.assertEquals("vnd.android.cursor.item/vnd.com.whatsapp.video.call", WaContacts.mime(WaContacts.PERSONAL, true))
        Assert.assertEquals("vnd.android.cursor.item/vnd.com.whatsapp.w4b.voip.call", WaContacts.mime(WaContacts.BUSINESS, false))
        Assert.assertEquals("vnd.android.cursor.item/vnd.com.whatsapp.w4b.video.call", WaContacts.mime(WaContacts.BUSINESS, true))
    }
    @Test fun jidDigits() {
        Assert.assertEquals("8801711223344", WaContacts.digits("8801711223344@s.whatsapp.net"))
        Assert.assertEquals("", WaContacts.digits(null))
    }
    @Test fun jidMatchesPhoneNumberInAnyFormat() {
        val jid = WaContacts.digits("8801711223344@s.whatsapp.net")
        Assert.assertTrue(PhoneNumbers.same(jid, "01711223344"))
        Assert.assertTrue(PhoneNumbers.same(jid, "+880 1711-223344"))
        Assert.assertTrue(!PhoneNumbers.same(jid, "01811223344"))
    }

    private class Src(val l: List<ContactEntry>) : ContactSource { override fun all() = l }
    private class Pl : CallPlacer {
        val placed = ArrayList<String>()
        override fun place(number: String, label: String): PlaceOutcome { placed.add("$label|$number"); return PlaceOutcome(true) }
    }

    @Test fun flowSaysWhatsAppInTheQuestionAndTheAnswer() {
        val book = listOf(ContactEntry("1", "রহিম", listOf(PhoneEntry("01711000001", "mobile"))))
        val pl = Pl()
        val flow = CallFlow(true, Src(book), pl, viaBn = "WhatsApp-এ ", viaEn = " on WhatsApp")
        val q = flow.start("রহিম", null)
        Assert.assertEquals("রহিমকে WhatsApp-এ কল করব?", q.message)
        val done = q.followUp!!.onReply("হ্যাঁ")
        Assert.assertEquals("রহিমকে WhatsApp-এ কল করছি", done.message)
        Assert.assertEquals(listOf("রহিম|01711000001"), pl.placed)
    }

    @Test fun flowEnglishAndVideo() {
        val book = listOf(ContactEntry("1", "Rahim", listOf(PhoneEntry("01711000001", "mobile"))))
        val flow = CallFlow(false, Src(book), Pl(), viaBn = "WhatsApp-এ ভিডিও ", viaEn = " on WhatsApp video")
        val q = flow.start("Rahim", null)
        Assert.assertEquals("Call Rahim on WhatsApp video?", q.message)
        Assert.assertEquals("Calling Rahim on WhatsApp video", q.followUp!!.onReply("yes").message)
    }

    @Test fun ordinaryCallsKeepTheirWording() {
        val book = listOf(ContactEntry("1", "রহিম", listOf(PhoneEntry("01711000001", "mobile"))))
        Assert.assertEquals("রহিমকে কল করব?", CallFlow(true, Src(book), Pl()).start("রহিম", null).message)
    }
}
