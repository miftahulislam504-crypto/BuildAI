package com.buildx.ai.device

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The text of one crash entry, and keeping the crash file small. Pure JVM. */
object CrashFormat {
    const val MAX_CHARS = 20_000

    fun entry(timeMillis: Long, thread: String, error: Throwable, header: String): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMillis))
        return "=== $time  thread=$thread  $header\n$sw\n"
    }

    /** the newest entries are kept; the oldest text is dropped first, at an entry boundary when possible */
    fun append(existing: String, entry: String): String {
        val all = existing + entry
        if (all.length <= MAX_CHARS) return all
        val tail = all.substring(all.length - MAX_CHARS)
        val boundary = tail.indexOf("=== ")
        return if (boundary >= 0) tail.substring(boundary) else "=== (earlier part trimmed)\n$tail"
    }
}
