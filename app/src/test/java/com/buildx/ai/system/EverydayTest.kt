package com.buildx.ai.system

import com.buildx.ai.nlu.IntentParser
import com.buildx.ai.nlu.T.ok
import org.junit.Assert
import org.junit.Test

class CalcTest {
    private fun e(s: String) = Calc.eval(s)
    @Test fun basic() {
        Assert.assertEquals(35.0, e("5 times 7")!!, 1e-9)
        Assert.assertEquals(12.0, e("5 plus 7")!!, 1e-9)
        Assert.assertEquals(-2.0, e("5 minus 7")!!, 1e-9)
        Assert.assertEquals(2.5, e("5 divided 2")!!, 1e-9)
    }
    @Test fun precedence() {
        Assert.assertEquals(23.0, e("3 plus 4 times 5")!!, 1e-9)
        Assert.assertEquals(14.0, e("2 times 3 plus 4 times 2")!!, 1e-9)
        Assert.assertEquals(8.0, e("10 minus 4 divided 2")!!, 1e-9)
    }
    @Test fun decimals() = Assert.assertEquals(7.75, e("5 point 5 plus 2 point 25")!!, 1e-9)
    @Test fun divisionByZeroAndBadInput() {
        Assert.assertNull(e("5 divided 0"))
        Assert.assertNull(e("5 plus"))
        Assert.assertNull(e(""))
        Assert.assertNull(e("5 7"))
    }
    @Test fun format() {
        Assert.assertEquals("৩৫", Calc.format(35.0, true))
        Assert.assertEquals("2.5", Calc.format(2.5, false))
        Assert.assertEquals("০.৩৩৩৩", Calc.format(1.0 / 3, true))
        Assert.assertEquals("-2", Calc.format(-2.0, false))
    }
}

class DateWordsTest {
    @Test fun bangla() = Assert.assertEquals("আজ বুধবার, ১৮ অক্টোবর ২০২৬", DateWords.say(2026, 9, 18, 4, true))
    @Test fun english() = Assert.assertEquals("Today is Sunday, January 4, 2026", DateWords.say(2026, 0, 4, 1, false))
}

class YoutubeResultsTest {
    @Test fun picksTheFirstVideo() {
        val html = """..."videoRenderer":{"videoId":"dQw4w9WgXcQ","thumbnail":{}} ... "videoId":"abcdefghijk" ..."""
        Assert.assertEquals("dQw4w9WgXcQ", YoutubeResults.firstVideoId(html))
    }
    @Test fun noVideo() = Assert.assertNull(YoutubeResults.firstVideoId("<html>nothing</html>"))
    @Test fun urlIsEncoded() {
        val u = YoutubeResults.searchUrl("আরিজিত সিং গান")
        Assert.assertTrue(u.startsWith("https://www.youtube.com/results?search_query=%E0%A6%86"))
        Assert.assertTrue(u.contains("+"))
    }
}

class EverydayParseTest {
    @Test fun youtubePlay() {
        ok("ইউটিউবে আরিজিত সিংয়ের গান চালাও", "youtube_play", "query=আরিজিত সিংয়ের গান")
        ok("আরিজিত সিংয়ের গান বাজাও", "youtube_play", "query=আরিজিত সিংয়ের গান")
        ok("ইউটিউবে tum hi ho চালাও", "youtube_play", "query=tum hi ho")
        ok("play Shape of You on youtube", "youtube_play", "query=shape of you")
        ok("ইউটিউবে ওয়াজ মাহফিল সার্চ করো", "youtube_play", "query=ওয়াজ মাহফিল")
        ok("আমাকে একটা গান শোনাও লতা মঙ্গেশকরের", "youtube_play", "query=একটা গান লতা মঙ্গেশকরের")
    }

    @Test fun youtubePlayWithoutAnySongAsks() {
        ok("গান চালাও", "youtube_play", "query=")
        ok("ইউটিউবে গান চালাও", "youtube_play", "query=")
    }

    @Test fun openingYouTubeIsStillOpening() {
        ok("ইউটিউব খোলো", "open_app", "app=youtube")
        ok("ইউটিউব চালু করো", "open_app", "app=youtube")
    }

    @Test fun mediaKeys() {
        ok("গান থামাও", "media", "action=pause")
        ok("গান বন্ধ করো", "media", "action=pause")
        ok("pause", "media", "action=pause")
        ok("পজ করো", "media", "action=pause")
        ok("পরের গান", "media", "action=next")
        ok("next song", "media", "action=next")
        ok("আগের গান", "media", "action=prev")
        ok("গান চালু করো", "media", "action=play")
        ok("resume", "media", "action=play")
        ok("প্লে করো", "media", "action=play")
        ok("ভিডিও থামাও", "media", "action=pause")
    }

    @Test fun scrolling() {
        ok("পরের রিল", "swipe", "dir=next")
        ok("পরের শর্টস", "swipe", "dir=next")
        ok("next reel", "swipe", "dir=next")
        ok("স্ক্রল করো", "swipe", "dir=next")
        ok("নিচে স্ক্রল করো", "swipe", "dir=next")
        ok("scroll down", "swipe", "dir=next")
        ok("আগের রিল", "swipe", "dir=prev")
        ok("আগের ভিডিও", "swipe", "dir=prev")
        ok("উপরে স্ক্রল করো", "swipe", "dir=prev")
        ok("scroll up", "swipe", "dir=prev")
    }

    @Test fun openFeeds() {
        ok("শর্টস চালাও", "open_feed", "feed=shorts")
        ok("ইউটিউব শর্টস খোলো", "open_feed", "feed=shorts")
        ok("ইউটিউবে শর্টস চালাও", "open_feed", "feed=shorts")
        ok("রিলস খোলো", "open_feed", "feed=reels")
        ok("রিল দেখাও", "open_feed", "feed=reels")
        ok("open reels", "open_feed", "feed=reels")
    }

    @Test fun calculator() {
        ok("৫ গুণ ৭ কত", "calc", "expr=5 times 7")
        ok("পাঁচ গুণ সাত কত", "calc", "expr=5 times 7")
        ok("১২ যোগ ৩০", "calc", "expr=12 plus 30")
        ok("১০০ বিয়োগ ২৫ কত", "calc", "expr=100 minus 25")
        ok("৫০ ভাগ ৪ কত", "calc", "expr=50 divided 4")
        ok("3 plus 4 times 5", "calc", "expr=3 plus 4 times 5")
        ok("5 + 7", "calc", "expr=5 plus 7")
        ok("পাঁচ দশমিক পাঁচ যোগ দুই", "calc", "expr=5 point 5 plus 2")
    }

    @Test fun addingAnAlarmIsNotAddition() {
        ok("সকাল ৬টায় অ্যালার্ম যোগ করো", "alarm", "hour=6")
    }

    @Test fun brightness() {
        ok("ব্রাইটনেস বাড়াও", "brightness", "action=up")
        ok("ব্রাইটনেস কমাও", "brightness", "action=down")
        ok("ব্রাইটনেস ৫০ করো", "brightness", "action=set", "level=50")
        ok("ব্রাইটনেস ফুল করো", "brightness", "action=set", "level=100")
        ok("brightness down", "brightness", "action=down")
        ok("ব্রাইটনেস ১০ বাড়াও", "brightness", "action=up", "delta=10")
    }

    @Test fun ringerModes() {
        ok("সাইলেন্ট মোড চালু করো", "ringer", "mode=silent")
        ok("ফোন সাইলেন্ট করো", "ringer", "mode=silent")
        ok("ভাইব্রেট মোড করো", "ringer", "mode=vibrate")
        ok("ফোন ভাইব্রেটে দাও", "ringer", "mode=vibrate")
        ok("নরমাল মোড করো", "ringer", "mode=normal")
        ok("রিং চালু করো", "ringer", "mode=normal")
    }

    @Test fun mediaMuteStaysVolume() {
        ok("মিউট করো", "volume", "action=mute")
    }

    @Test fun batteryAndDate() {
        ok("ব্যাটারি কত", "battery")
        ok("চার্জ কত আছে", "battery")
        ok("battery level", "battery")
        ok("আজ কত তারিখ", "date")
        ok("আজ কী বার", "date")
        ok("what date is it", "date")
        ok("আজকের তারিখ বলো", "date")
    }

    @Test fun navigate() {
        ok("ঢাকা যাওয়ার রাস্তা দেখাও", "navigate", "query=ঢাকা")
        ok("navigate to Mirpur", "navigate", "query=mirpur")
        ok("মিরপুর যেতে ম্যাপ দেখাও", "navigate", "query=মিরপুর")
        ok("গুলশানের রুট দেখাও", "navigate")
    }

    @Test fun webSearch() {
        ok("গুগলে আবহাওয়া সার্চ করো", "web_search", "query=আবহাওয়া")
        ok("search for best biryani in dhaka", "web_search", "query=best biryani in dhaka")
        ok("গুগলে ক্রিকেট স্কোর খোঁজো", "web_search", "query=ক্রিকেট স্কোর")
        ok("সার্চ করো", "web_search", "query=")
    }

    @Test fun chainsWithTheNewOnes() {
        val a = IntentParser.parseChain("ইউটিউবে আরিজিত সিংয়ের গান চালাও আর ভলিউম বাড়াও").map { it.intent }
        Assert.assertEquals(listOf("youtube_play", "volume"), a)
        val b = IntentParser.parseChain("গান থামাও আর স্ক্রিন লক করো").map { it.intent }
        Assert.assertEquals(listOf("media", "screen_lock"), b)
        val c = IntentParser.parseChain("ব্যাটারি কত আর আজ কত তারিখ").map { it.intent }
        Assert.assertEquals(listOf("battery", "date"), c)
    }

    @Test fun oldCommandsStayTheSame() {
        ok("ভলিউম বাড়াও", "volume", "action=up")
        ok("টর্চ জ্বালাও", "flashlight", "state=on")
        ok("এখন কয়টা বাজে", "time")
        ok("ইউটিউব খোলো", "open_app", "app=youtube")
        ok("play store খোলো", "open_app", "app=playstore")
        ok("থাক", "cancel")
        ok("থামো", "cancel")
    }
}
