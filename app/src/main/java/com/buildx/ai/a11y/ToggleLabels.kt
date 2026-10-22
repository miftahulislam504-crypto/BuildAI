package com.buildx.ai.a11y

/** Recognises the Wi-Fi / Bluetooth switch among the texts around a switch on a settings screen (any UI language we know). */
object ToggleLabels {

    enum class Kind { WIFI, BLUETOOTH, AIRPLANE, MOBILE_DATA }

    private val wifi = listOf("wi-fi", "wifi", "wi fi", "ওয়াই-ফাই", "ওয়াইফাই", "ওয়াই ফাই")
    private val bluetooth = listOf("bluetooth", "ব্লুটুথ")
    private val airplane = listOf("airplane", "aeroplane", "flight mode", "এয়ারপ্লেন", "এরোপ্লেন", "ফ্লাইট")
    private val mobileData = listOf("mobile data", "cellular data", "মোবাইল ডেটা", "মোবাইল ডাটা")
    // other switches that live next to the real one and must not be tapped by mistake
    private val notThese = listOf("calling", "hotspot", "direct", "assistant", "scanning", "tethering", "হটস্পট", "কলিং")

    private fun norm(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).lowercase()

    /** on a page that holds only one switch ("first switch on the page") it is safe to tap it unlabelled; not on the data page */
    fun firstSwitchOk(kind: Kind): Boolean = kind != Kind.MOBILE_DATA

    fun matches(kind: Kind, texts: List<CharSequence?>): Boolean {
        val words = when (kind) {
            Kind.WIFI -> wifi
            Kind.BLUETOOTH -> bluetooth
            Kind.AIRPLANE -> airplane
            Kind.MOBILE_DATA -> mobileData
        }
        val all = texts.filterNotNull().map { norm(it.toString()) }.filter { it.isNotBlank() }
        if ((kind == Kind.WIFI || kind == Kind.BLUETOOTH) && all.any { t -> notThese.any { it in t } }) return false
        return all.any { t -> words.any { norm(it) in t } }
    }
}
