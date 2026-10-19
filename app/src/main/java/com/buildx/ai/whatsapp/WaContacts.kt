package com.buildx.ai.whatsapp

/**
 * WhatsApp adds a "call" row to the phone's contacts for every contact that is on WhatsApp. Opening that row
 * with the matching mime type starts a WhatsApp call straight away. The row's DATA1 is the WhatsApp id,
 * like "8801711223344@s.whatsapp.net".
 */
object WaContacts {
    const val PERSONAL = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    fun mime(pkg: String, video: Boolean): String {
        val kind = if (video) "video.call" else "voip.call"
        return if (pkg == BUSINESS) "vnd.android.cursor.item/vnd.com.whatsapp.w4b.$kind" else "vnd.android.cursor.item/vnd.com.whatsapp.$kind"
    }

    /** "8801711223344@s.whatsapp.net" -> "8801711223344" */
    fun digits(jid: String?): String = jid?.substringBefore('@')?.filter { it.isDigit() } ?: ""
}
