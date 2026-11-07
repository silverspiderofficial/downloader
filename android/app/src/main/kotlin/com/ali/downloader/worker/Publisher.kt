package com.ali.downloader.worker

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.ali.downloader.common.ErrorLog
import com.ali.downloader.core.ErrorClassifier
import com.ali.downloader.core.Job
import com.ali.downloader.core.JobRunner
import com.ali.downloader.core.RunControl
import com.ali.downloader.core.RunResult
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * مرحلهٔ انتشار: انتقال فایل نهایی از پوشهٔ خصوصی اپ به پوشهٔ عمومی.
 * اندروید ۱۰+: MediaStore در Download/دانلودر (با IS_PENDING به‌عنوان فایل موقت).
 * اندروید ۷ تا ۹: پوشهٔ Download/دانلودر با مجوز WRITE_EXTERNAL_STORAGE (نوشتن با ‎.part و تغییر نام).
 * پوشهٔ دلخواه: Storage Access Framework (نوشتن با ‎.part و تغییر نام).
 */
class Publisher(
    private val ctx: Context,
    private val customFolder: () -> String,
) : JobRunner {

    companion object {
        const val FOLDER_NAME = "دانلودر"
        private const val PENDING_FILE = ".pending-uri"

        fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "flv" -> "video/x-flv"
            "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus", "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private val stopFlags = ConcurrentHashMap<Long, Boolean>()

    override fun stop(jobId: Long) {
        stopFlags[jobId] = true
    }

    private class StoppedException : Exception()

    override fun run(job: Job, control: RunControl): RunResult {
        stopFlags.remove(job.id)
        val src = File(job.privatePath)
        val dir = YtDlpRunner.jobDir(ctx, job.id)
        // اگر انتشار قبلی وسط کار قطع شده بود، آیتم نیمه‌کاره پاک می‌شود
        cleanupPending(dir)
        if (!src.exists()) {
            return if (job.outputUri.isNotEmpty()) RunResult.Success { it }
            else RunResult.Failure(ErrorClassifier.PUBLISH_FAILED, false, "فایل دانلودشده پیدا نشد؛ دوباره دانلود کنید")
        }
        val size = src.length()
        val name = src.name
        val mime = mimeOf(name)
        return try {
            val folder = customFolder()
            val (uri, finalName) = when {
                folder.isNotEmpty() -> publishSaf(Uri.parse(folder), src, name, mime, job, control, dir)
                Build.VERSION.SDK_INT >= 29 -> publishMediaStore(src, name, mime, job, control, dir)
                else -> publishLegacy(src, name, job, control)
            }
            File(dir, PENDING_FILE).delete()
            dir.deleteRecursively()
            RunResult.Success { it.copy(outputUri = uri, outputName = finalName, sizeBytes = size, privatePath = "") }
        } catch (e: StoppedException) {
            cleanupPending(dir)
            RunResult.Failure("STOPPED", false, "")
        } catch (e: SecurityException) {
            ErrorLog.write(ctx, "worker", "publish#${job.id}", e.message ?: "", e)
            cleanupPending(dir)
            RunResult.Failure(ErrorClassifier.STORAGE_PERMISSION, false, e.message ?: "")
        } catch (e: NoSpaceException) {
            cleanupPending(dir)
            RunResult.Failure(ErrorClassifier.NO_SPACE, false, e.message ?: "")
        } catch (t: Throwable) {
            ErrorLog.write(ctx, "worker", "publish#${job.id}", t.message ?: "", t)
            cleanupPending(dir)
            val c = ErrorClassifier.classify(t.toString())
            if (c.code == ErrorClassifier.NO_SPACE) RunResult.Failure(c.code, false, c.detail)
            else RunResult.Failure(ErrorClassifier.PUBLISH_FAILED, true, t.message ?: t.toString())
        } finally {
            stopFlags.remove(job.id)
        }
    }

    private class NoSpaceException(msg: String) : Exception(msg)

    private fun ensureSpace(needed: Long, path: File) {
        try {
            val st = StatFs(path.absolutePath)
            if (st.availableBytes < needed + 20L * 1024 * 1024) throw NoSpaceException("فضای آزاد کافی نیست")
        } catch (e: NoSpaceException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, job: Job, control: RunControl) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastReport = 0L
        val start = System.currentTimeMillis()
        while (true) {
            if (stopFlags[job.id] == true) throw StoppedException()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            if (done - lastReport >= 4L * 1024 * 1024 || done == total) {
                lastReport = done
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1)
                val speed = done * 1000 / elapsed
                control.update(true) {
                    it.copy(
                        downloadedBytes = done, totalBytes = total, speedBps = speed,
                        etaSec = if (speed > 0) (total - done) / speed else -1,
                        progress = if (total > 0) done * 100.0 / total else -1.0,
                    )
                }
            }
        }
        output.flush()
    }

    private fun publishMediaStore(src: File, name: String, mime: String, job: Job, control: RunControl, dir: File): Pair<String, String> {
        ensureSpace(src.length(), Environment.getExternalStorageDirectory())
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore insert failed")
        File(dir, PENDING_FILE).writeText(uri.toString())
        resolver.openOutputStream(uri, "w").use { out ->
            requireNotNull(out) { "openOutputStream returned null" }
            FileInputStream(src).use { input -> copyWithProgress(input, out, src.length(), job, control) }
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        var finalName = name
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) finalName = c.getString(0) ?: name
        }
        return uri.toString() to finalName
    }

    private fun publishLegacy(src: File, name: String, job: Job, control: RunControl): Pair<String, String> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("WRITE_EXTERNAL_STORAGE not granted")
        }
        @Suppress("DEPRECATION")
        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)
        base.mkdirs()
        ensureSpace(src.length(), base)
        val target = uniqueFile(base, name)
        val temp = File(base, target.name + ".part")
        try {
            FileInputStream(src).use { input ->
                temp.outputStream().use { out -> copyWithProgress(input, out, src.length(), job, control) }
            }
            if (!temp.renameTo(target)) throw IllegalStateException("rename failed")
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mimeOf(target.name)), null)
        return Uri.fromFile(target).toString() to target.name
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    private fun publishSaf(tree: Uri, src: File, name: String, mime: String, job: Job, control: RunControl, dir: File): Pair<String, String> {
        val resolver = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val tempDoc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "$name.part")
            ?: throw SecurityException("cannot create document in selected folder")
        File(dir, PENDING_FILE).writeText(tempDoc.toString())
        resolver.openOutputStream(tempDoc, "w").use { out ->
            requireNotNull(out) { "openOutputStream returned null" }
            FileInputStream(src).use { input -> copyWithProgress(input, out, src.length(), job, control) }
        }
        // تغییر نام به نام نهایی؛ اگر نام تکراری بود، شماره اضافه می‌شود
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var renamed: Uri? = null
        var finalName = name
        for (i in 0..50) {
            finalName = if (i == 0) name else if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            renamed = try {
                DocumentsContract.renameDocument(resolver, tempDoc, finalName)
            } catch (_: Exception) {
                null
            }
            if (renamed != null) break
        }
        val result = renamed ?: tempDoc
        return result.toString() to finalName
    }

    private fun cleanupPending(dir: File) {
        val f = File(dir, PENDING_FILE)
        if (!f.exists()) return
        try {
            val uri = Uri.parse(f.readText().trim())
            if (uri.authority == MediaStore.AUTHORITY) ctx.contentResolver.delete(uri, null, null)
            else DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        } catch (_: Exception) {
        }
        f.delete()
    }
}
