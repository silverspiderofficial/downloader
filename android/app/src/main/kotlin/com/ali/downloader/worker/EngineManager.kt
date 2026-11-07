package com.ali.downloader.worker

import android.content.Context
import com.ali.downloader.common.ErrorLog
import com.ali.downloader.core.ErrorClassifier
import com.ali.downloader.core.Sha256
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * موتورهای داخلی: پایتون + yt-dlp، FFmpeg و aria2c (از کتابخانهٔ youtubedl-android).
 * به‌روزرسانی yt-dlp از مخزن رسمی آن در گیت‌هاب، با بررسی SHA-256، نسخهٔ پشتیبان و بازگشت خودکار در صورت خرابی.
 */
class EngineManager(private val ctx: Context, private val repo: SqliteJobRepository) {

    companion object {
        const val RELEASE_API = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
        private const val KV_VERSION = "engine.version"
        private const val KV_BACKUP_VERSION = "engine.backupVersion"
        private const val KV_LAST_CHECK = "engine.lastCheck"
        const val WEEK_MS = 7L * 24 * 3600 * 1000
    }

    /** اجرای yt-dlp قفل خواندن می‌گیرد؛ جایگزینی فایل موتور قفل نوشتن. */
    val engineLock = ReentrantReadWriteLock()
    private val ready = CountDownLatch(1)

    @Volatile var initError: String? = null
        private set

    private val ytdlpFile: File
        get() = File(File(File(ctx.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin)
    private val backupFile: File get() = File(File(ctx.noBackupFilesDir, "engine-backup"), "yt-dlp")

    /** آماده‌سازی موتورها (بار اول فایل‌های پایتون از APK باز می‌شوند و چند ثانیه طول می‌کشد). */
    fun init() {
        try {
            YoutubeDL.init(ctx)
            FFmpeg.init(ctx)
            Aria2c.init(ctx)
            if (repo.getKv(KV_VERSION).isNullOrBlank()) {
                readVersion()?.let { repo.putKv(KV_VERSION, it) }
            }
        } catch (t: Throwable) {
            initError = t.message ?: t.toString()
            ErrorLog.write(ctx, "worker", "engine-init", initError ?: "", t)
        } finally {
            ready.countDown()
        }
    }

    fun awaitReady(timeoutSec: Long = 120): Boolean = ready.await(timeoutSec, TimeUnit.SECONDS) && initError == null

    private fun readVersion(): String? = try {
        val r = YoutubeDL.execute(YoutubeDLRequest(emptyList<String>()).addOption("--version"), null, null)
        r.out.trim().lines().lastOrNull { it.isNotBlank() }?.trim()
    } catch (t: Throwable) {
        ErrorLog.write(ctx, "worker", "engine-version", t.message ?: "", t)
        null
    }

    fun info(): JSONObject = JSONObject().apply {
        put("version", repo.getKv(KV_VERSION) ?: "")
        put("backupVersion", if (backupFile.exists()) repo.getKv(KV_BACKUP_VERSION) ?: "" else "")
        put("lastCheck", repo.getKv(KV_LAST_CHECK)?.toLongOrNull() ?: 0L)
        put("initError", initError ?: "")
    }

    fun isAutoCheckDue(now: Long): Boolean {
        val last = repo.getKv(KV_LAST_CHECK)?.toLongOrNull() ?: 0L
        return now - last >= WEEK_MS
    }

    // ---------------- تحلیل لینک ----------------

    /** تحلیل لینک با yt-dlp ‎-J و نوشتن خروجی JSON در فایل (به‌جای عبور از Binder). */
    fun analyze(url: String, requestId: Int): JSONObject {
        if (!awaitReady()) return error(ErrorClassifier.UNKNOWN, "موتور آماده نشد: ${initError ?: ""}")
        val dir = File(ctx.cacheDir, "analysis").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000) it.delete() }
        val req = YoutubeDLRequest(url)
            .addOption("-J")
            .addOption("--no-playlist")
            .addOption("--flat-playlist")
            .addOption("--socket-timeout", "30")
            .addOption("--cache-dir", File(ctx.cacheDir, "yt-dlp-cache").absolutePath)
        engineLock.readLock().lock()
        try {
            val resp = YoutubeDL.execute(req, "analyze-$requestId-${System.nanoTime()}", null)
            val out = File(dir, "a-$requestId-${System.currentTimeMillis()}.json")
            out.writeText(resp.out)
            return JSONObject().put("ok", true).put("path", out.absolutePath)
        } catch (e: YoutubeDL.CanceledException) {
            return error("CANCELED", "")
        } catch (e: YoutubeDLException) {
            val c = ErrorClassifier.classify(e.message)
            ErrorLog.write(ctx, "worker", "analyze", "${c.code}: ${c.detail}")
            return error(c.code, c.detail)
        } catch (t: Throwable) {
            ErrorLog.write(ctx, "worker", "analyze", t.message ?: "", t)
            return error(ErrorClassifier.UNKNOWN, t.message ?: t.toString())
        } finally {
            engineLock.readLock().unlock()
        }
    }

    /** فهرست سایت‌هایی که موتور فعلی پشتیبانی می‌کند (yt-dlp --list-extractors)، با کش برای هر نسخه. */
    fun listExtractors(): JSONObject {
        if (!awaitReady()) return error(ErrorClassifier.UNKNOWN, initError ?: "")
        val ver = repo.getKv(KV_VERSION) ?: "unknown"
        val cache = File(ctx.cacheDir, "extractors-$ver.txt")
        try {
            if (!cache.exists()) {
                engineLock.readLock().lock()
                try {
                    val r = YoutubeDL.execute(YoutubeDLRequest(emptyList<String>()).addOption("--list-extractors"), null, null)
                    ctx.cacheDir.listFiles()?.filter { it.name.startsWith("extractors-") }?.forEach { it.delete() }
                    cache.writeText(r.out)
                } finally {
                    engineLock.readLock().unlock()
                }
            }
            return JSONObject().put("ok", true).put("path", cache.absolutePath)
        } catch (t: Throwable) {
            ErrorLog.write(ctx, "worker", "extractors", t.message ?: "", t)
            return error(ErrorClassifier.classify(t.message).code, t.message ?: "")
        }
    }

    // ---------------- به‌روزرسانی و بازگشت ----------------

    private fun httpGet(url: String, accept: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "downloader-android")
        if (accept != null) c.setRequestProperty("Accept", accept)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw java.io.IOException("HTTP $code for $url")
        }
        return c
    }

    private fun downloadTo(url: String, dest: File) {
        val c = httpGet(url)
        try {
            c.inputStream.use { input -> FileOutputStream(dest).use { input.copyTo(it, 64 * 1024) } }
        } finally {
            c.disconnect()
        }
    }

    /**
     * به‌روزرسانی yt-dlp. فراخواننده باید مطمئن شود هیچ دانلودی در حال اجرا نیست.
     * نتیجه: status = updated | upToDate | failed | rolledBack
     */
    fun update(): JSONObject {
        if (!awaitReady()) return JSONObject().put("status", "failed").put("detail", initError ?: "")
        val now = System.currentTimeMillis()
        val tmp = File(ctx.cacheDir, "yt-dlp.download")
        try {
            val releaseJson = httpGet(RELEASE_API, "application/vnd.github+json").let { c ->
                try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
            }
            val rel = JSONObject(releaseJson)
            val tag = rel.getString("tag_name")
            repo.putKv(KV_LAST_CHECK, now.toString())
            val current = repo.getKv(KV_VERSION) ?: ""
            if (tag == current) return JSONObject().put("status", "upToDate").put("version", current)

            val assets = rel.getJSONArray("assets")
            var binUrl = ""
            var sumsUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.getString("name")) {
                    "yt-dlp" -> binUrl = a.getString("browser_download_url")
                    "SHA2-256SUMS" -> sumsUrl = a.getString("browser_download_url")
                }
            }
            if (binUrl.isEmpty() || sumsUrl.isEmpty()) {
                return JSONObject().put("status", "failed").put("detail", "فایل موتور یا فایل هش در انتشار پیدا نشد")
            }
            val sums = httpGet(sumsUrl).let { c -> try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() } }
            val expected = Sha256.findInSums(sums, "yt-dlp")
                ?: return JSONObject().put("status", "failed").put("detail", "هش SHA-256 موتور در فایل SHA2-256SUMS نبود")
            downloadTo(binUrl, tmp)
            val actual = Sha256.ofFile(tmp)
            if (!actual.equals(expected, ignoreCase = true)) {
                tmp.delete()
                return JSONObject().put("status", "failed").put("detail", "هش SHA-256 فایل دانلودشده مطابقت نداشت؛ فایل کنار گذاشته شد")
            }
            return installVerified(tmp, tag, current)
        } catch (t: Throwable) {
            ErrorLog.write(ctx, "worker", "engine-update", t.message ?: "", t)
            tmp.delete()
            return JSONObject().put("status", "failed").put("detail", ErrorClassifier.classify(t.toString()).code)
                .put("message", t.message ?: "")
        }
    }

    private fun installVerified(newFile: File, tag: String, currentVersion: String): JSONObject {
        val lock = engineLock.writeLock()
        if (!lock.tryLock(30, TimeUnit.SECONDS)) {
            newFile.delete()
            return JSONObject().put("status", "busy")
        }
        try {
            // نسخهٔ فعلی پشتیبان گرفته می‌شود
            backupFile.parentFile?.mkdirs()
            if (ytdlpFile.exists()) {
                ytdlpFile.copyTo(backupFile, overwrite = true)
                repo.putKv(KV_BACKUP_VERSION, currentVersion)
            }
            // نوشتن با نام موقت و سپس تغییر نام اتمی
            val staged = File(ytdlpFile.parentFile, "yt-dlp.new")
            newFile.copyTo(staged, overwrite = true)
            newFile.delete()
            if (!staged.renameTo(ytdlpFile)) {
                staged.delete()
                return JSONObject().put("status", "failed").put("detail", "جایگزینی فایل موتور ممکن نشد")
            }
            val v = readVersion()
            if (v == null || v != tag) {
                // نسخهٔ تازه کار نمی‌کند: بازگشت خودکار به نسخهٔ قبلی
                restoreFromBackup()
                return JSONObject().put("status", "rolledBack").put("version", currentVersion)
                    .put("detail", "نسخهٔ تازه ($tag) اجرا نشد و نسخهٔ قبلی برگردانده شد")
            }
            repo.putKv(KV_VERSION, tag)
            return JSONObject().put("status", "updated").put("version", tag).put("previous", currentVersion)
        } finally {
            lock.unlock()
        }
    }

    private fun restoreFromBackup(): Boolean {
        if (!backupFile.exists()) return false
        val staged = File(ytdlpFile.parentFile, "yt-dlp.restore")
        backupFile.copyTo(staged, overwrite = true)
        return staged.renameTo(ytdlpFile)
    }

    /** بازگشت دستی به نسخهٔ قبلی موتور؛ نسخهٔ فعلی جای پشتیبان را می‌گیرد. */
    fun rollback(): JSONObject {
        if (!awaitReady()) return JSONObject().put("status", "failed").put("detail", initError ?: "")
        if (!backupFile.exists()) return JSONObject().put("status", "noBackup")
        val lock = engineLock.writeLock()
        if (!lock.tryLock(30, TimeUnit.SECONDS)) return JSONObject().put("status", "busy")
        try {
            val current = repo.getKv(KV_VERSION) ?: ""
            val backupVer = repo.getKv(KV_BACKUP_VERSION) ?: ""
            val swap = File(ctx.cacheDir, "yt-dlp.swap")
            ytdlpFile.copyTo(swap, overwrite = true)
            if (!restoreFromBackup()) return JSONObject().put("status", "failed").put("detail", "بازگردانی فایل ممکن نشد")
            val v = readVersion()
            if (v == null) {
                // پشتیبان هم سالم نبود: نسخهٔ فعلی برمی‌گردد
                val back = File(ytdlpFile.parentFile, "yt-dlp.back")
                swap.copyTo(back, overwrite = true)
                back.renameTo(ytdlpFile)
                swap.delete()
                return JSONObject().put("status", "failed").put("detail", "نسخهٔ پشتیبان اجرا نشد؛ نسخهٔ فعلی حفظ شد")
            }
            swap.copyTo(backupFile, overwrite = true)
            swap.delete()
            repo.putKv(KV_VERSION, v)
            repo.putKv(KV_BACKUP_VERSION, current)
            return JSONObject().put("status", "rolledBack").put("version", v).put("previous", current)
                .put("detail", if (backupVer.isNotEmpty() && backupVer != v) "نسخهٔ اجراشده: $v" else "")
        } finally {
            lock.unlock()
        }
    }

    private fun error(code: String, detail: String) = JSONObject().put("ok", false).put("code", code).put("detail", detail)
}
