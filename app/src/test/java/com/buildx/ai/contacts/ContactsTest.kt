package com.buildx.ai.contacts

import org.junit.Assert
import org.junit.Test

class PhoneticTest {
    @Test fun rahimBothWays() = Assert.assertEquals(Phonetic.skeleton("রহিম"), Phonetic.skeleton("Rahim"))
    @Test fun karimBothWays() = Assert.assertEquals(Phonetic.skeleton("করিম"), Phonetic.skeleton("Karim"))
    @Test fun abdullah() = Assert.assertEquals(Phonetic.skeleton("আব্দুল্লাহ"), Phonetic.skeleton("Abdullah"))
    @Test fun muhammad() = Assert.assertEquals(Phonetic.skeleton("মোহাম্মদ"), Phonetic.skeleton("Muhammad"))
    @Test fun tanvir() = Assert.assertEquals(Phonetic.skeleton("তানভীর"), Phonetic.skeleton("Tanvir"))
    @Test fun shamim() = Assert.assertEquals(Phonetic.skeleton("শামিম"), Phonetic.skeleton("Shamim"))
    @Test fun rayhanWithYa() = Assert.assertEquals(Phonetic.skeleton("রায়হান"), Phonetic.skeleton("Rayhan"))
    @Test fun lakshmi() = Assert.assertEquals(Phonetic.skeleton("লক্ষ্মী"), Phonetic.skeleton("Lakshmi"))
    @Test fun chowdhury() = Assert.assertEquals(Phonetic.skeleton("চৌধুরী"), Phonetic.skeleton("Chowdhury"))
    @Test fun bangla() = Assert.assertEquals(Phonetic.skeleton("বাংলা"), Phonetic.skeleton("Bangla"))
    @Test fun differentNamesStayDifferent() = Assert.assertTrue(Phonetic.skeleton("Rahim") != Phonetic.skeleton("Karim"))
}

class PhoneNumbersTest {
    @Test fun plain() = Assert.assertEquals("01711223344", PhoneNumbers.dialable("01711223344"))
    @Test fun withSeparators() = Assert.assertEquals("01711223344", PhoneNumbers.dialable("01711-223 344"))
    @Test fun international() = Assert.assertEquals("+8801711223344", PhoneNumbers.dialable("+880 1711-223344"))
    @Test fun countryCodeWithoutPlus() = Assert.assertEquals("+8801711223344", PhoneNumbers.dialable("8801711223344"))
    @Test fun doubleZero() = Assert.assertEquals("+8801711223344", PhoneNumbers.dialable("008801711223344"))
    @Test fun lettersRejected() = Assert.assertNull(PhoneNumbers.dialable("017abc"))
    @Test fun ussdRejected() = Assert.assertNull(PhoneNumbers.dialable("*123#"))
    @Test fun tooShort() = Assert.assertNull(PhoneNumbers.dialable("12"))
    @Test fun shortCodeAllowed() = Assert.assertEquals("999", PhoneNumbers.dialable("999"))
    @Test fun sameNumber() {
        Assert.assertTrue(PhoneNumbers.same("+8801711223344", "01711-223344"))
        Assert.assertTrue(!PhoneNumbers.same("01711223344", "01811223344"))
    }
    @Test fun emergency() {
        Assert.assertTrue(PhoneNumbers.isEmergency("999"))
        Assert.assertTrue(!PhoneNumbers.isEmergency("01711223344"))
    }
    @Test fun spelled() {
        Assert.assertEquals("০ ১ ৭", PhoneNumbers.spell("017", true))
        Assert.assertEquals("0 1 7", PhoneNumbers.spell("017", false))
    }
}

class NicknameBookTest {
    @Test fun parsesLines() {
        val m = NicknameBook.parse("বাবা = রহিম উদ্দিন\n\nবস=Karim Hossain\nbad line")
        Assert.assertEquals("রহিম উদ্দিন", m["বাবা"])
        Assert.assertEquals("Karim Hossain", m["বস"])
        Assert.assertEquals(2, m.size)
    }
}

class NameMatcherTest {
    private fun c(id: String, name: String, nick: String = "", num: String = "0171100000$id") =
        ContactEntry(id, name, listOf(PhoneEntry(num, "mobile")), nick)

    private val book = listOf(
        c("1", "রহিম উদ্দিন"), c("2", "Karim Hossain"), c("3", "আব্বু"), c("4", "Mom"), c("5", "শামিম"),
        c("6", "Tanvir Ahmed"), c("7", "আব্দুল্লাহ"), c("8", "মোহাম্মদ আলী"), c("9", "Sumon"),
        c("10", "Rakib Hasan"), c("11", "হাসান ভাই"), c("12", "Nusrat Jahan"), c("13", "চৌধুরী সাহেব")
    )

    private fun one(query: String, expected: String, nicknames: Map<String, String> = emptyMap(), list: List<ContactEntry> = book) {
        val g = NameMatcher.bestGroup(NameMatcher.find(query, list, nicknames))
        Assert.assertEquals("matches for \"$query\": ${g.map { it.contact.name }}", 1, g.size)
        Assert.assertEquals("contact for \"$query\"", expected, g[0].contact.name)
    }

    // Phase 3 pass rule: 10 contacts, each found correctly — here with Bengali AND English spellings
    @Test fun banglaQueryEnglishContact() {
        one("রহিম", "রহিম উদ্দিন")
        one("করিম", "Karim Hossain")
        one("তানভীর", "Tanvir Ahmed")
        one("সুমন", "Sumon")
        one("রাকিব", "Rakib Hasan")
        one("নুসরাত", "Nusrat Jahan")
        one("মা", "Mom")
    }

    @Test fun englishQueryBanglaContact() {
        one("Rahim", "রহিম উদ্দিন")
        one("Shamim", "শামিম")
        one("Abdullah", "আব্দুল্লাহ")
        one("Muhammad", "মোহাম্মদ আলী")
        one("Chowdhury", "চৌধুরী সাহেব")
    }

    @Test fun sameScriptExact() {
        one("শামিম", "শামিম")
        one("Karim", "Karim Hossain")
        one("Karim Hossain", "Karim Hossain")
        one("রহিম উদ্দিন", "রহিম উদ্দিন")
    }

    @Test fun familyWords() {
        one("বাবা", "আব্বু")
        one("Dad", "আব্বু")
        one("আব্বা", "আব্বু")
        one("আম্মু", "Mom")
        one("আম্মা", "Mom")
        one("mother", "Mom")
    }

    @Test fun literalContactBeatsFamilyWord() {
        val list = book + c("20", "বাবা", num = "01799999999")
        one("বাবা", "বাবা", list = list)
    }

    @Test fun partOfAName() {
        one("উদ্দিন", "রহিম উদ্দিন")
        one("Hossain", "Karim Hossain")
        one("আলী", "মোহাম্মদ আলী")
    }

    @Test fun tooShortToGuess() {
        // "মা" must not match মামা-like or unrelated short names
        val list = listOf(c("1", "মামা"), c("2", "Mom"))
        one("মা", "Mom", list = list)
    }

    @Test fun twoPeopleSameNameMeansAsk() {
        val list = listOf(c("1", "Rakib Hasan"), c("2", "Nasir Hasan"), c("3", "Karim Hossain"))
        for (q in listOf("হাসান", "Hasan")) {
            val g = NameMatcher.bestGroup(NameMatcher.find(q, list))
            Assert.assertEquals(q, setOf("Rakib Hasan", "Nasir Hasan"), g.map { it.contact.name }.toSet())
        }
    }

    @Test fun sameScriptBeatsOtherScript() {
        // "হাসান" typed exactly like the contact is a better fit than a phonetic match
        one("হাসান", "হাসান ভাই")
    }

    @Test fun exactNameBeatsLongerName() {
        val list = listOf(c("1", "রহিম"), c("2", "রহিম উদ্দিন"))
        one("রহিম", "রহিম", list = list)
    }

    @Test fun sameNumberSavedTwiceCountsOnce() {
        val list = listOf(c("1", "রহিম", num = "01711223344"), c("2", "Rahim", num = "+8801711223344"))
        Assert.assertEquals(1, NameMatcher.bestGroup(NameMatcher.find("রহীম", list)).size)
    }

    @Test fun contactNicknameField() {
        val list = listOf(c("1", "Md. Anisur Rahman", nick = "বস"), c("2", "Karim Hossain"))
        one("বস", "Md. Anisur Rahman", list = list)
    }

    @Test fun usersOwnNicknameWins() {
        one("বস", "Karim Hossain", nicknames = mapOf("বস" to "Karim Hossain"))
    }

    @Test fun nothingFound() {
        Assert.assertTrue(NameMatcher.find("Zubayer", book).isEmpty())
        Assert.assertTrue(NameMatcher.find("", book).isEmpty())
    }
}
