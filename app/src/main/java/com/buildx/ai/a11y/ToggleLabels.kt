package com.buildx.ai.a11y

/** Recognises the Wi-Fi / Bluetooth switch among the texts around a switch on a settings screen (any UI language we know). */
object ToggleLabels {

    enum class Kind { WIFI, BLUETOOTH }

    private val wifi = listOf("wi-fi", "wifi", "wi fi", "ওয়াই-ফাই", "ওয়াইফাই", "ওয়াই ফাই")
    private val bluetooth = listOf("bluetooth", "ব্লুটুথ")
    // other switches that live next to the real one and must not be tapped by mistake
    private val notThese = listOf("calling", "hotspot", "direct", "assistant", "scanning", "tethering", "হটস্পট", "কলিং")

    private fun norm(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).lowercase()

    fun matches(kind: Kind, texts: List<CharSequence?>): Boolean {
        val words = if (kind == Kind.WIFI) wifi else bluetooth
        val all = texts.filterNotNull().map { norm(it.toString()) }.filter { it.isNotBlank() }
        if (all.any { t -> notThese.any { it in t } }) return false
        return all.any { t -> words.any { norm(it) in t } }
    }
}
