package com.ali.downloader.core

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** بررسی هش SHA-256 فایل‌های دانلودی (مثل موتور yt-dlp). */
object Sha256 {
    fun ofFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun ofBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * خواندن فایل SHA2-256SUMS (هر خط: «هش  نام‌فایل» یا «هش *نام‌فایل») و برگرداندن هش [fileName].
     */
    fun findInSums(sums: String, fileName: String): String? {
        for (raw in sums.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) continue
            val name = parts[1].trim().removePrefix("*")
            if (name == fileName && parts[0].matches(Regex("[0-9a-fA-F]{64}"))) return parts[0].lowercase()
        }
        return null
    }
}
