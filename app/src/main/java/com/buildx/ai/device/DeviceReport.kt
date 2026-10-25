package com.buildx.ai.device

data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val sdk: Int,
    val release: String,
    val appVersion: String,
    val lang: String,
    val wakeSensitivity: String,
    val permissions: Map<String, Boolean>,
    val serviceRunning: Boolean,
    val diedLastTime: Boolean,
    val killCount: Int,
    val lastCrash: String?,
    val logTail: List<String>
)

/** One block of text with everything needed to understand why a phone behaves differently; paste it in a message. */
object DeviceReport {

    fun format(i: DeviceInfo): String {
        val maker = OemGuide.detect(i.manufacturer, i.brand)
        val sb = StringBuilder()
        sb.append("BuildX report\n")
        sb.append("Phone: ${i.manufacturer} ${i.model} (${i.brand}), Android ${i.release} (SDK ${i.sdk}), group: $maker\n")
        sb.append("App: ${i.appVersion}, language: ${i.lang}, wake sensitivity: ${i.wakeSensitivity}\n")
        sb.append("Service now: ${if (i.serviceRunning) "running" else "stopped"}\n")
        sb.append("Service died instead of stopping cleanly: ${if (i.diedLastTime) "yes, last time" else "no"} (${i.killCount} times in total)\n")
        sb.append("Permissions:\n")
        for ((k, v) in i.permissions) sb.append("  $k: ${if (v) "granted" else "missing"}\n")
        if (!i.lastCrash.isNullOrBlank()) {
            sb.append("Last crash:\n").append(i.lastCrash.trim().lines().take(12).joinToString("\n") { "  $it" }).append('\n')
        } else {
            sb.append("Crashes: none\n")
        }
        sb.append("Last ${i.logTail.size} log lines:\n")
        for (l in i.logTail) sb.append("  ").append(l).append('\n')
        return sb.toString().trimEnd()
    }
}
