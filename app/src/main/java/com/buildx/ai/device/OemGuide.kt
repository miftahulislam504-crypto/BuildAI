package com.buildx.ai.device

enum class Maker { XIAOMI, SAMSUNG, MOTOROLA, GOOGLE, OPPO, VIVO, HUAWEI, OTHER }

/** [components]: settings screens worth trying directly (package to class); each may not exist on every version. */
data class Guide(val maker: Maker, val title: String, val steps: List<String>, val components: List<Pair<String, String>>)

/**
 * Phones kill background apps in different ways. These are the steps that keep BuildX listening, per maker.
 * The screen names change between versions, so every step is text a person can follow by hand.
 */
object OemGuide {

    fun detect(manufacturer: String, brand: String): Maker {
        val s = "$manufacturer $brand".lowercase()
        return when {
            "xiaomi" in s || "redmi" in s || "poco" in s -> Maker.XIAOMI
            "samsung" in s -> Maker.SAMSUNG
            "motorola" in s || "lenovo" in s -> Maker.MOTOROLA
            "google" in s -> Maker.GOOGLE
            "oppo" in s || "realme" in s || "oneplus" in s -> Maker.OPPO
            "vivo" in s || "iqoo" in s -> Maker.VIVO
            "huawei" in s || "honor" in s -> Maker.HUAWEI
            else -> Maker.OTHER
        }
    }

    private val common = listOf(
        "Open Recent apps, press and hold the BuildX AI card and choose \"Lock\", so that \"Close all\" does not kill it.",
        "Tap the app's \"Battery optimization exemption\" button and allow it."
    )

    fun guide(maker: Maker): Guide = when (maker) {
        Maker.XIAOMI -> Guide(
            maker, "Xiaomi / Redmi / POCO (MIUI, HyperOS)",
            listOf(
                "Autostart: Security app > Permissions > Autostart > turn on BuildX AI.",
                "Battery: Settings > Apps > Manage apps > BuildX AI > Battery saver > \"No restrictions\".",
                "Background pop-ups: Settings > Apps > Manage apps > BuildX AI > Other permissions > \"Display pop-up windows while running in the background\" and \"Show on Lock screen\" > Allow."
            ) + common,
            listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            )
        )
        Maker.SAMSUNG -> Guide(
            maker, "Samsung (One UI)",
            listOf(
                "Settings > Battery > Background usage limits > \"Never auto sleeping apps\" > + > add BuildX AI.",
                "In the same place, keep \"Put unused apps to sleep\" off (or at least exclude BuildX AI).",
                "Settings > Apps > BuildX AI > Battery > \"Unrestricted\"."
            ) + common,
            listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        )
        Maker.MOTOROLA -> Guide(
            maker, "Motorola / Lenovo",
            listOf(
                "Settings > Apps > BuildX AI > App battery usage (Battery) > \"Unrestricted\" or \"Don't optimize\".",
                "Settings > Battery > turn off Adaptive battery (at least while you are testing)."
            ) + common,
            emptyList()
        )
        Maker.GOOGLE -> Guide(
            maker, "Google Pixel",
            listOf(
                "Settings > Apps > BuildX AI > App battery usage > \"Unrestricted\".",
                "On the same page, turn off \"Pause app activity if unused\"."
            ) + common,
            emptyList()
        )
        Maker.OPPO -> Guide(
            maker, "OPPO / realme / OnePlus (ColorOS, OxygenOS)",
            listOf(
                "Settings > Apps > App management > BuildX AI > Battery usage > turn on \"Allow background activity\" and \"Allow auto launch\".",
                "Settings > Battery > menu at the top right > App Battery Management > BuildX AI > \"Don't optimize\"."
            ) + common,
            listOf("com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity")
        )
        Maker.VIVO -> Guide(
            maker, "vivo / iQOO (Funtouch OS)",
            listOf(
                "Settings > Battery > Background power consumption management > BuildX AI > \"Allow high background power consumption\".",
                "i Manager > App manager > Autostart > turn on BuildX AI."
            ) + common,
            emptyList()
        )
        Maker.HUAWEI -> Guide(
            maker, "Huawei / Honor",
            listOf(
                "Settings > Battery > App launch > BuildX AI > \"Manage manually\" > turn on all three: Auto-launch, Secondary launch, Run in background."
            ) + common,
            listOf("com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        Maker.OTHER -> Guide(
            maker, "Your phone",
            listOf(
                "Settings > Apps > BuildX AI > Battery > \"Unrestricted\" / \"No restrictions\" / \"Don't optimize\" (the name differs by phone).",
                "If there is an Autostart, Auto-launch or Background activity option, turn it on for BuildX AI."
            ) + common,
            emptyList()
        )
    }

    fun forDevice(manufacturer: String, brand: String): Guide = guide(detect(manufacturer, brand))

    fun format(g: Guide): String = buildString {
        append(g.title).append('\n')
        g.steps.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
    }.trimEnd()
}
