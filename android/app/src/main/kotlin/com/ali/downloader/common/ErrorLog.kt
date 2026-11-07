package com.ali.downloader.common

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * لاگ خطا فقط روی خود گوشی. هر پروسه فایل جدای خودش را دارد تا نوشتن هم‌زمان دو پروسه فایل را خراب نکند.
 * هیچ چیزی به هیچ سروری ارسال نمی‌شود.
 */
object ErrorLog {
    private const val MAX_BYTES = 256 * 1024L
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun dir(ctx: Context) = File(ctx.filesDir, "logs").apply { mkdirs() }

    private fun fileFor(ctx: Context, process: String) = File(dir(ctx), "$process.log")

    @Synchronized
    fun write(ctx: Context, process: String, tag: String, message: String, error: Throwable? = null) {
        try {
            val f = fileFor(ctx, process)
            if (f.length() > MAX_BYTES) {
                val old = File(f.path + ".1")
                old.delete()
                f.renameTo(old)
            }
            val sb = StringBuilder()
            sb.append(fmt.format(Date())).append(" [").append(process).append("] ").append(tag).append(": ")
                .append(message.replace('\n', ' ').take(2000))
            if (error != null) {
                sb.append(" | ").append(error.javaClass.simpleName).append(": ").append(error.message ?: "")
                error.stackTrace.take(6).forEach { sb.append(" @ ").append(it.toString()) }
            }
            sb.append('\n')
            f.appendText(sb.toString())
        } catch (_: Throwable) {
            // لاگ نباید هرگز خودش باعث خطا شود
        }
    }

    /** همهٔ خطاها از هر دو پروسه، به ترتیب زمان. */
    @Synchronized
    fun readAll(ctx: Context): String {
        val lines = ArrayList<String>()
        dir(ctx).listFiles()?.filter { it.name.contains(".log") }?.forEach { f ->
            try {
                lines.addAll(f.readLines().filter { it.isNotBlank() })
            } catch (_: Throwable) {
            }
        }
        lines.sort()
        return lines.joinToString("\n")
    }

    @Synchronized
    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}
