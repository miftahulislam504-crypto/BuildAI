package com.buildx.ai.a11y

import com.buildx.ai.a11y.ToggleLabels.Kind
import org.junit.Assert
import org.junit.Test

class ToggleLabelsTest {
    private fun wifi(vararg t: String?) = ToggleLabels.matches(Kind.WIFI, t.toList())
    private fun bt(vararg t: String?) = ToggleLabels.matches(Kind.BLUETOOTH, t.toList())

    @Test fun wifiEnglish() = Assert.assertTrue(wifi("Wi-Fi"))
    @Test fun wifiPlain() = Assert.assertTrue(wifi(null, "WiFi", "On"))
    @Test fun wifiBangla() = Assert.assertTrue(wifi("ওয়াই-ফাই"))
    @Test fun wifiBanglaNoDash() = Assert.assertTrue(wifi("ওয়াইফাই"))
    @Test fun wifiRowWithNetworkName() = Assert.assertTrue(wifi("Wi-Fi", "HomeNetwork_5G"))
    @Test fun wifiCallingIsNotTheSwitch() = Assert.assertTrue(!wifi("Wi-Fi calling"))
    @Test fun hotspotIsNotTheSwitch() = Assert.assertTrue(!wifi("Wi-Fi hotspot"))
    @Test fun wifiDirectIsNotTheSwitch() = Assert.assertTrue(!wifi("Wi-Fi Direct"))
    @Test fun mobileDataIsNotWifi() = Assert.assertTrue(!wifi("Mobile data", "Airplane mode"))
    @Test fun bluetoothEnglish() = Assert.assertTrue(bt("Bluetooth"))
    @Test fun bluetoothBangla() = Assert.assertTrue(bt("ব্লুটুথ"))
    @Test fun bluetoothTethering() = Assert.assertTrue(!bt("Bluetooth tethering"))
    @Test fun wifiIsNotBluetooth() = Assert.assertTrue(!bt("Wi-Fi"))
    @Test fun emptyTexts() = Assert.assertTrue(!wifi(null, "", "  "))
}
