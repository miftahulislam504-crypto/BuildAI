package com.buildx.ai.a11y

import org.junit.Assert
import org.junit.Test

class ScreenModelTest {

    private class N(
        override val text: String? = null,
        override val desc: String? = null,
        override val hint: String? = null,
        override val clickable: Boolean = false,
        override val editable: Boolean = false,
        override val scrollable: Boolean = false,
        override val checkable: Boolean = false,
        override val checked: Boolean = false,
        override val password: Boolean = false,
        override val visible: Boolean = true,
        override val children: List<UiNode> = emptyList()
    ) : UiNode

    private fun build(root: UiNode, pkg: String = "com.example.app", allowSensitive: Boolean = false) =
        SnapshotBuilder.build(root, pkg, allowSensitive)

    private fun els(root: UiNode) = build(root).snapshot.elements

    // ---------- reading order and labels ----------

    @Test fun aTappableRowTakesTheTextInsideItAsItsLabel() {
        val row = N(clickable = true, children = listOf(N(text = "Rahim"), N(text = "Hello there"), N(text = "11:02")))
        val e = els(N(children = listOf(row)))
        Assert.assertEquals(1, e.size)
        Assert.assertEquals(ElKind.BUTTON, e[0].kind)
        Assert.assertEquals("Rahim · Hello there · 11:02", e[0].label)
    }

    @Test fun plainTextIsListedAndNumberedInReadingOrder() {
        val e = els(N(children = listOf(N(text = "Chats"), N(clickable = true, text = "Search"), N(text = "Today"))))
        Assert.assertEquals(listOf(1, 2, 3), e.map { it.id })
        Assert.assertEquals(listOf("Chats", "Search", "Today"), e.map { it.label })
        Assert.assertEquals(listOf(ElKind.TEXT, ElKind.BUTTON, ElKind.TEXT), e.map { it.kind })
    }

    @Test fun anInteractiveThingInsideARowIsStillListed() {
        val row = N(clickable = true, children = listOf(N(text = "Wi-Fi"), N(checkable = true, checked = true, desc = "Wi-Fi switch")))
        val e = els(row)
        Assert.assertEquals(listOf(ElKind.BUTTON, ElKind.SWITCH), e.map { it.kind })
        Assert.assertEquals(true, e[1].checked)
    }

    @Test fun aFieldShowsItsHintAndItsValue() {
        val e = els(N(children = listOf(N(editable = true, hint = "Type a message"), N(editable = true, hint = "To", text = "Rahim"))))
        Assert.assertEquals(ElKind.FIELD, e[0].kind)
        Assert.assertEquals("Type a message", e[0].label)
        Assert.assertNull(e[0].value)
        Assert.assertEquals("Rahim", e[1].value)
    }

    @Test fun invisibleNodesAreSkipped() {
        val e = els(N(children = listOf(N(text = "seen"), N(text = "hidden", visible = false))))
        Assert.assertEquals(listOf("seen"), e.map { it.label })
    }

    @Test fun aScrollableListIsMarked() {
        val e = els(N(scrollable = true, desc = "Chats", children = listOf(N(text = "one"))))
        Assert.assertEquals(ElKind.SCROLL, e[0].kind)
        Assert.assertEquals("one", e[1].label)
    }

    @Test fun everyElementKeepsItsNodeForLaterActions() {
        val button = N(clickable = true, text = "OK")
        val built = build(N(children = listOf(button)))
        Assert.assertTrue(built.nodes[1] === button)
    }

    @Test fun atMostSixtyElementsAndTheRestIsFlagged() {
        val many = N(children = (1..80).map { N(text = "line $it") })
        val snap = build(many).snapshot
        Assert.assertEquals(SnapshotBuilder.MAX_ELEMENTS, snap.elements.size)
        Assert.assertTrue(snap.truncated)
        Assert.assertTrue(snap.toPrompt().contains("not shown"))
    }

    @Test fun thePromptIsOneLinePerElement() {
        val snap = build(
            N(children = listOf(
                N(text = "Chat with Rahim"),
                N(editable = true, hint = "Message", text = "hel"),
                N(editable = true, hint = "Subject"),
                N(clickable = true, text = "Send"),
                N(checkable = true, checked = false, text = "Mute"),
                N(scrollable = true)
            ))
        ).snapshot
        val lines = snap.toPrompt().trim().lines()
        Assert.assertEquals("[1] text \"Chat with Rahim\"", lines[0])
        Assert.assertEquals("[2] field \"Message\" value=\"hel\"", lines[1])
        Assert.assertEquals("[3] field \"Subject\" empty", lines[2])
        Assert.assertEquals("[4] button \"Send\"", lines[3])
        Assert.assertEquals("[5] switch \"Mute\" off", lines[4])
        Assert.assertEquals("[6] scrollable", lines[5])
    }

    // ---------- what must never be read ----------

    @Test fun aScreenWithAPasswordFieldIsNotReadAtAll() {
        val snap = build(N(children = listOf(N(text = "Log in"), N(editable = true, password = true, hint = "Password")))).snapshot
        Assert.assertNotNull(snap.blocked)
        Assert.assertTrue(snap.elements.isEmpty())
        Assert.assertEquals("", snap.toPrompt())
    }

    @Test fun aHiddenPasswordFieldDoesNotBlock() {
        val snap = build(N(children = listOf(N(text = "Hi"), N(editable = true, password = true, visible = false)))).snapshot
        Assert.assertNull(snap.blocked)
    }

    @Test fun moneyPasswordAndOwnAppsAreBlockedWhateverIsOnScreen() {
        for (pkg in listOf(
            "com.bkash.customerapp", "com.konasl.nagad", "com.dutchbanglabank.rocket", "com.google.android.apps.nbu.paisa.user",
            "com.paypal.android.p2pmobile", "com.google.android.apps.authenticator2", "org.keepassdroid", "com.bitwarden.mobile",
            "com.buildx.ai", "com.example.MyBankApp"
        )) {
            val snap = build(N(children = listOf(N(text = "Balance"))), pkg).snapshot
            Assert.assertNotNull("$pkg should be blocked", snap.blocked)
            Assert.assertTrue(snap.elements.isEmpty())
        }
        for (pkg in listOf("com.whatsapp", "com.android.chrome", "com.google.android.youtube", "com.android.settings", "com.facebook.katana")) {
            Assert.assertNull("$pkg should be fine", build(N(children = listOf(N(text = "x"))), pkg).snapshot.blocked)
        }
    }

    @Test fun theUsersOwnCommandMayReadWhatTheModelMayNot() {
        val screen = N(children = listOf(N(text = "Log in"), N(editable = true, password = true, hint = "Password", text = "hunter2")))
        val snap = build(screen, allowSensitive = true).snapshot
        Assert.assertNull(snap.blocked)
        Assert.assertEquals(2, snap.elements.size)
        Assert.assertNull("a password's content is never listed", snap.elements[1].value)
    }

    @Test fun longDigitRunsAreMasked() {
        Assert.assertEquals("code •••", Redactor.label("code 123456"))
        Assert.assertEquals("•••", Redactor.label("01711223344"))
        Assert.assertEquals("•••", Redactor.label("১২৩৪৫৬৭৮"))
        Assert.assertEquals("card •••", Redactor.label("card 1234 5678 9012 3456"))
        Assert.assertEquals("Room 12, floor 3", Redactor.label("Room 12, floor 3"))
        Assert.assertEquals("12:30 · 5 new", Redactor.label("12:30 · 5 new"))
    }

    @Test fun labelsAreOneCleanLineOfBoundedLength() {
        Assert.assertEquals("a b c", Redactor.label("  a\n\n b\t c \u0000"))
        Assert.assertEquals("say 'hi'", Redactor.label("say \"hi\""))
        Assert.assertEquals("", Redactor.label(null))
        Assert.assertEquals("", Redactor.label("   "))
        val long = Redactor.label("x".repeat(200))
        Assert.assertEquals(Redactor.MAX_LABEL, long.length)
        Assert.assertTrue(long.endsWith("…"))
    }

    // ---------- buttons that are not pressed without asking ----------

    @Test fun sendingPayingDeletingAndCallingAreRisky() {
        for (l in listOf("Send", "send message", "Pay now", "Confirm", "Delete", "Place order", "Sign out", "Log out", "Call", "Share", "Post",
            "পাঠান", "সেন্ড", "পেমেন্ট", "মুছুন", "কল", "নিশ্চিত করুন")) {
            Assert.assertTrue("$l should be risky", RiskyLabels.isRisky(l))
        }
    }

    @Test fun ordinaryButtonsAreNot() {
        for (l in listOf("Search", "Next", "Back", "Chats", "Posts", "Resend code", "Calls", "Settings", "OK", "Cancel", "")) {
            Assert.assertFalse("$l should not be risky", RiskyLabels.isRisky(l))
        }
    }

    @Test fun onlyButtonsAreMarkedRisky() {
        val e = els(N(children = listOf(N(text = "Send"), N(clickable = true, text = "Send"))))
        Assert.assertFalse(e[0].risky)
        Assert.assertTrue(e[1].risky)
    }

    // ---------- finding what the person pointed at ----------

    private fun el(id: Int, kind: ElKind, label: String) = ScreenElement(id, kind, label)

    @Test fun theSameWordsWin() {
        val found = ScreenMatch.find(listOf(el(1, ElKind.BUTTON, "Send"), el(2, ElKind.BUTTON, "Send later")), "send")
        Assert.assertEquals(listOf(1), found.map { it.id })
    }

    @Test fun aWordInsideALongerLabelFindsIt() {
        val found = ScreenMatch.find(listOf(el(1, ElKind.BUTTON, "Rahim · Hello · 11:02"), el(2, ElKind.BUTTON, "Karim · Hi")), "rahim")
        Assert.assertEquals(listOf(1), found.map { it.id })
    }

    @Test fun twoEqualMatchesAreBothReturnedSoTheCallerCanAsk() {
        val found = ScreenMatch.find(listOf(el(1, ElKind.BUTTON, "Follow"), el(2, ElKind.BUTTON, "Follow")), "follow")
        Assert.assertEquals(2, found.size)
    }

    @Test fun tappableThingsBeatPlainTextWithTheSameWords() {
        val found = ScreenMatch.find(listOf(el(1, ElKind.TEXT, "Send"), el(2, ElKind.BUTTON, "Send")), "send")
        Assert.assertEquals(listOf(2), found.map { it.id })
    }

    @Test fun plainTextIsTheLastResort() {
        val found = ScreenMatch.find(listOf(el(1, ElKind.TEXT, "Welcome"), el(2, ElKind.BUTTON, "OK")), "welcome")
        Assert.assertEquals(listOf(1), found.map { it.id })
    }

    @Test fun aBengaliSpellingFindsAnEnglishLabel() {
        val found = ScreenMatch.find(listOf(el(1, ElKind.BUTTON, "Send"), el(2, ElKind.BUTTON, "Cancel")), "সেন্ড")
        Assert.assertEquals(listOf(1), found.map { it.id })
    }

    @Test fun nothingMatchingIsEmpty() {
        Assert.assertTrue(ScreenMatch.find(listOf(el(1, ElKind.BUTTON, "OK")), "banana").isEmpty())
        Assert.assertTrue(ScreenMatch.find(listOf(el(1, ElKind.BUTTON, "OK")), "  ").isEmpty())
        Assert.assertTrue(ScreenMatch.find(emptyList(), "ok").isEmpty())
    }

    @Test fun readingAloudGivesTheVisibleWordsInOrderWithoutRepeats() {
        val said = ScreenMatch.readAloud(
            listOf(
                el(1, ElKind.TEXT, "Chats"), el(2, ElKind.BUTTON, "Rahim"), el(3, ElKind.BUTTON, "Rahim"),
                ScreenElement(4, ElKind.SWITCH, "Mute", checked = true), ScreenElement(5, ElKind.FIELD, "Message", value = "hel"),
                el(6, ElKind.SCROLL, "")
            )
        )
        Assert.assertEquals("Chats. Rahim. Mute on. hel", said)
        Assert.assertTrue(ScreenMatch.readAloud(List(100) { el(it, ElKind.TEXT, "word number $it") }).length <= ScreenMatch.MAX_READ)
    }
}
