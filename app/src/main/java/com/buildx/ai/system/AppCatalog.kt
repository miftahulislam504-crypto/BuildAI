package com.buildx.ai.system

import com.buildx.ai.contacts.ContactEntry
import com.buildx.ai.contacts.NameMatcher

/** An app that can be launched: [label] is what the launcher shows, [pkg] its package name. */
data class Launchable(val label: String, val pkg: String)

/**
 * Known app keys (see the lexicon) -> package names to try, in order (Google first, then Samsung/others),
 * and a system "kind" to fall back on (default camera, calculator, ...).
 */
object AppCatalog {

    class Spec(val packages: List<String>, val kind: String?, val bn: String, val en: String)

    private val specs: Map<String, Spec> = mapOf(
        "youtube" to Spec(listOf("com.google.android.youtube"), null, "ইউটিউব", "YouTube"),
        "whatsapp" to Spec(listOf("com.whatsapp", "com.whatsapp.w4b"), null, "হোয়াটসঅ্যাপ", "WhatsApp"),
        "facebook" to Spec(listOf("com.facebook.katana", "com.facebook.lite"), null, "ফেসবুক", "Facebook"),
        "messenger" to Spec(listOf("com.facebook.orca", "com.facebook.mlite"), null, "মেসেঞ্জার", "Messenger"),
        "chrome" to Spec(listOf("com.android.chrome", "com.sec.android.app.sbrowser"), "browser", "ক্রোম", "Chrome"),
        "camera" to Spec(listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera"), "camera", "ক্যামেরা", "Camera"),
        "gallery" to Spec(listOf("com.sec.android.gallery3d", "com.google.android.apps.photos", "com.miui.gallery"), "gallery", "গ্যালারি", "Gallery"),
        "calculator" to Spec(listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.miui.calculator"), "calculator", "ক্যালকুলেটর", "Calculator"),
        "maps" to Spec(listOf("com.google.android.apps.maps"), "maps", "ম্যাপ", "Maps"),
        "gmail" to Spec(listOf("com.google.android.gm"), "email", "জিমেইল", "Gmail"),
        "playstore" to Spec(listOf("com.android.vending"), null, "প্লে স্টোর", "Play Store"),
        "dialer" to Spec(listOf("com.samsung.android.dialer", "com.google.android.dialer"), "dialer", "ফোন", "Phone"),
        "messages" to Spec(listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"), "messages", "মেসেজ", "Messages"),
        "clock" to Spec(listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"), "clock", "ঘড়ি", "Clock"),
        "calendar" to Spec(listOf("com.samsung.android.calendar", "com.google.android.calendar"), "calendar", "ক্যালেন্ডার", "Calendar"),
        "telegram" to Spec(listOf("org.telegram.messenger"), null, "টেলিগ্রাম", "Telegram"),
        "instagram" to Spec(listOf("com.instagram.android"), null, "ইনস্টাগ্রাম", "Instagram"),
        "bkash" to Spec(listOf("com.bKash.customerapp"), null, "বিকাশ", "bKash"),
        "nagad" to Spec(listOf("com.konasl.nagad"), null, "নগদ", "Nagad"),
        "spotify" to Spec(listOf("com.spotify.music"), null, "স্পটিফাই", "Spotify"),
        "contacts" to Spec(listOf("com.samsung.android.app.contacts", "com.google.android.contacts"), "contacts", "কন্টাক্টস", "Contacts"),
        "files" to Spec(listOf("com.sec.android.app.myfiles", "com.google.android.apps.nbu.files", "com.google.android.documentsui"), null, "ফাইল", "Files"),
        "settings" to Spec(listOf("com.android.settings"), "settings", "সেটিংস", "Settings")
    )

    fun spec(key: String): Spec? = specs[key]

    fun name(key: String, bn: Boolean): String = specs[key]?.let { if (bn) it.bn else it.en } ?: key

    val keys: Set<String> get() = specs.keys

    /** installed apps whose launcher label fits the spoken name, best first (same matching as contact names) */
    fun findByLabel(query: String, apps: List<Launchable>): List<Launchable> {
        val entries = apps.map { ContactEntry(it.pkg, it.label, emptyList()) }
        val byPkg = apps.associateBy { it.pkg }
        return NameMatcher.bestGroup(NameMatcher.find(query, entries)).mapNotNull { byPkg[it.contact.id] }
    }
}
