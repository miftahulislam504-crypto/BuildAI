package com.buildx.ai.nlu

import com.buildx.ai.nlu.T.ok
import org.junit.Assert
import org.junit.Test

class WhatsAppPhotoTest {
    @Test fun whatsappVoiceCall() {
        ok("রহিমকে হোয়াটসঅ্যাপে কল করো", "whatsapp_call", "target=রহিম")
        ok("হোয়াটসঅ্যাপে রহিমকে কল দাও", "whatsapp_call", "target=রহিম")
        ok("whatsapp call Rahim", "whatsapp_call", "target=rahim")
        ok("Rahim কে WhatsApp এ কল করো", "whatsapp_call", "target=rahim")
        ok("আব্বুকে হোয়াটসঅ্যাপ কল করো", "whatsapp_call", "target=আব্বু")
        ok("call Karim on whatsapp", "whatsapp_call", "target=karim")
        ok("০১৭১১২২৩৩৪৪ নম্বরে হোয়াটসঅ্যাপে কল দাও", "whatsapp_call", "number=01711223344")
    }

    @Test fun whatsappVideoCall() {
        ok("রহিমকে হোয়াটসঅ্যাপে ভিডিও কল করো", "whatsapp_call", "target=রহিম", "video=1")
        ok("হোয়াটসঅ্যাপে ভিডিও কল দাও আম্মুকে", "whatsapp_call", "target=আম্মু", "video=1")
        ok("whatsapp video call Rahim", "whatsapp_call", "target=rahim", "video=1")
    }

    @Test fun videoCallWithoutAnAppGoesToWhatsApp() {
        ok("রহিমকে ভিডিও কল করো", "whatsapp_call", "target=রহিম", "video=1")
        ok("video call Rahim", "whatsapp_call", "target=rahim", "video=1")
    }

    @Test fun plainCallsAreUnchanged() {
        ok("রহিমকে কল করো", "call", "target=রহিম")
        ok("বাবাকে ফোন করো", "call", "target=বাবা")
    }

    @Test fun openingWhatsAppIsStillJustOpening() {
        ok("হোয়াটসঅ্যাপ খোলো", "open_app", "app=whatsapp")
    }

    @Test fun backCameraPhoto() {
        for (s in listOf(
            "ছবি তোলো", "একটা ছবি তোলো", "ক্যামেরা দিয়ে ছবি তোলো", "ছবি তুলে দাও", "ফটো তোলো", "ছবি তুলুন", "ছবি নাও",
            "take a photo", "take a picture", "take photo", "ক্যামেরায় ছবি তোলো", "ক্যামেরা দিয়ে একটা ছবি তুলে দাও"
        )) ok(s, "take_photo", "camera=back")
    }

    @Test fun selfie() {
        for (s in listOf("সেলফি তোলো", "selfie", "take a selfie", "ফ্রন্ট ক্যামেরা দিয়ে ছবি তোলো", "সামনের ক্যামেরায় ছবি তোলো", "front camera take a photo"))
            ok(s, "take_photo", "camera=front")
    }

    @Test fun cameraAloneStillOpensTheApp() {
        ok("ক্যামেরা খোলো", "open_app", "app=camera")
    }

    @Test fun photoIsOneCommandButChainsWork() {
        Assert.assertEquals(1, IntentParser.parseChain("ক্যামেরা দিয়ে ছবি তোলো").size)
        Assert.assertEquals(1, IntentParser.parseChain("ক্যামেরা খুলে ছবি তোলো").size)
        val steps = IntentParser.parseChain("টর্চ জ্বালাও আর ছবি তোলো")
        Assert.assertEquals(listOf("flashlight", "take_photo"), steps.map { it.intent })
        val steps2 = IntentParser.parseChain("ছবি তুলে রহিমকে হোয়াটসঅ্যাপে কল করো")
        Assert.assertEquals(listOf("take_photo", "whatsapp_call"), steps2.map { it.intent })
    }

    @Test fun describe() {
        Assert.assertEquals("WhatsApp ভিডিও কল: রহিম", Describe.say(T.p("রহিমকে হোয়াটসঅ্যাপে ভিডিও কল করো"), true))
        Assert.assertEquals("take a selfie", Describe.say(T.p("selfie"), false))
        Assert.assertEquals("ছবি তোলা", Describe.say(T.p("ছবি তোলো"), true))
    }
}
