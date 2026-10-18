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
        sb.append("BuildX রিপোর্ট\n")
        sb.append("ফোন: ${i.manufacturer} ${i.model} (${i.brand}), Android ${i.release} (SDK ${i.sdk}), গ্রুপ: $maker\n")
        sb.append("অ্যাপ: ${i.appVersion}, ভাষা: ${i.lang}, wake সংবেদনশীলতা: ${i.wakeSensitivity}\n")
        sb.append("সার্ভিস এখন: ${if (i.serviceRunning) "চলছে" else "বন্ধ"}\n")
        sb.append("সার্ভিস নিজে বন্ধ না হয়ে মরেছে: ${if (i.diedLastTime) "হ্যাঁ, গতবার" else "না"} (মোট ${i.killCount} বার)\n")
        sb.append("পারমিশন:\n")
        for ((k, v) in i.permissions) sb.append("  ${if (v) "✓" else "✗"} $k\n")
        if (!i.lastCrash.isNullOrBlank()) {
            sb.append("শেষ ক্র্যাশ:\n").append(i.lastCrash.trim().lines().take(12).joinToString("\n") { "  $it" }).append('\n')
        } else {
            sb.append("ক্র্যাশ: নেই\n")
        }
        sb.append("লগের শেষ ${i.logTail.size} লাইন:\n")
        for (l in i.logTail) sb.append("  ").append(l).append('\n')
        return sb.toString().trimEnd()
    }
}
