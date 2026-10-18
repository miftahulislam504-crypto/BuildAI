package com.buildx.ai.wake

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.buildx.ai.LogStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads the small English Vosk model (~40 MB, Apache 2.0) once and unpacks it into app storage. */
object ModelManager {
    const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

    @Volatile
    var downloading = false
        private set

    fun modelDir(ctx: Context): File = File(File(ctx.filesDir, "models"), MODEL_NAME)

    fun isReady(ctx: Context): Boolean = File(modelDir(ctx), ".ok").exists()

    /** Runs on a background thread; [onDone] is called on the main thread. */
    fun download(ctx: Context, onDone: (Boolean) -> Unit) {
        if (downloading) return
        downloading = true
        Thread {
            var ok = false
            try {
                val zip = File(ctx.cacheDir, "$MODEL_NAME.zip")
                downloadTo(ctx, zip)

                val root = File(ctx.filesDir, "models")
                modelDir(ctx).deleteRecursively()
                root.mkdirs()
                LogStore.add(ctx, "মডেল: আনজিপ হচ্ছে...")
                unzip(zip, root)
                zip.delete()

                File(modelDir(ctx), ".ok").writeText("ok")
                LogStore.add(ctx, "মডেল: প্রস্তুত")
                ok = true
            } catch (e: Exception) {
                LogStore.add(ctx, "মডেল ERROR: ${e.javaClass.simpleName}: ${e.message}")
            }
            downloading = false
            Handler(Looper.getMainLooper()).post { onDone(ok) }
        }.start()
    }

    private fun downloadTo(ctx: Context, dest: File) {
        LogStore.add(ctx, "মডেল: ডাউনলোড শুরু ($MODEL_URL)")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var nextLog = 10
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct >= nextLog) {
                                LogStore.add(ctx, "মডেল: $pct%")
                                nextLog = pct - pct % 10 + 10
                            }
                        }
                    }
                }
            }
            LogStore.add(ctx, "মডেল: ডাউনলোড শেষ (${done / 1024 / 1024} MB)")
        } finally {
            conn.disconnect()
        }
    }

    private fun unzip(zip: File, destDir: File) {
        val destPath = destDir.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                // Refuse entries that would escape the target folder.
                if (!out.canonicalPath.startsWith(destPath)) throw SecurityException("bad zip entry: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
