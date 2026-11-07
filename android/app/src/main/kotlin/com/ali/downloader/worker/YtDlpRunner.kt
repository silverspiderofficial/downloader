package com.ali.downloader.worker

import android.content.Context
import com.ali.downloader.common.ErrorLog
import com.ali.downloader.core.ErrorClassifier
import com.ali.downloader.core.Job
import com.ali.downloader.core.JobPhase
import com.ali.downloader.core.JobRunner
import com.ali.downloader.core.ProgressParser
import com.ali.downloader.core.QueueSettings
import com.ali.downloader.core.RunControl
import com.ali.downloader.core.RunResult
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * مرحلهٔ دانلود: اجرای yt-dlp در پوشهٔ خصوصی همان کار. yt-dlp بهترین صدا را با FFmpeg به تصویر وصل می‌کند
 * (MP4، یا MKV اگر کدک‌ها در MP4 جا نشوند) و زیرنویس سایت را داخل فایل جاسازی می‌کند.
 * فایل‌ها تا پایان با پسوند ‎.part نوشته می‌شوند و Resume با ‎--continue انجام می‌شود.
 */
class YtDlpRunner(
    private val ctx: Context,
    private val engine: EngineManager,
    private val settings: () -> QueueSettings,
) : JobRunner {

    companion object {
        fun jobDir(ctx: Context, id: Long) = File(File(ctx.filesDir, "downloads"), id.toString())
        private const val TITLE_FILE = ".title"
        private const val THUMB_FILE = ".thumb"
        private const val RESULT_FILE = ".result"
    }

    private fun processId(id: Long) = "job-$id"

    override fun stop(jobId: Long) {
        YoutubeDL.destroyProcessById(processId(jobId))
    }

    override fun run(job: Job, control: RunControl): RunResult {
        if (!engine.awaitReady()) {
            return RunResult.Failure(ErrorClassifier.UNKNOWN, true, "موتور آماده نشد: ${engine.initError ?: ""}")
        }
        val dir = jobDir(ctx, job.id).apply { mkdirs() }
        File(dir, RESULT_FILE).delete()
        val s = settings()
        val req = YoutubeDLRequest(job.url)
            .addOption("-f", job.formatSelector)
            .addOption("--merge-output-format", job.mergeFormat)
            .addOption("--remux-video", if (job.mergeFormat == "mkv") "mkv" else "mp4>mp4/mkv")
            .addOption("-P", dir.absolutePath)
            .addOption("-o", "%(title).120B [%(id)s].%(ext)s")
            .addOption("--windows-filenames")
            .addOption("--no-playlist")
            .addOption("--newline")
            .addOption("--progress")
            .addOption("--progress-template", ProgressParser.PROGRESS_TEMPLATE)
            .addOption("--continue")
            .addOption("--no-mtime")
            .addOption("--retries", "10")
            .addOption("--fragment-retries", "10")
            .addOption("--socket-timeout", "30")
            .addOption("--concurrent-fragments", "4")
            .addOption("--embed-metadata")
            .addOption("--cache-dir", File(ctx.cacheDir, "yt-dlp-cache").absolutePath)
            .addCommands(listOf("--print-to-file", "before_dl:%(title)s", File(dir, TITLE_FILE).absolutePath))
            .addCommands(listOf("--print-to-file", "before_dl:%(thumbnail)s", File(dir, THUMB_FILE).absolutePath))
            .addCommands(listOf("--print-to-file", "after_move:%(filepath)s", File(dir, RESULT_FILE).absolutePath))
        if (job.embedSubs && s.embedSubs && !job.subsDisabled) {
            req.addOption("--write-subs").addOption("--sub-langs", "all,-live_chat").addOption("--embed-subs")
        }
        if (s.speedLimitKBps > 0) req.addOption("--limit-rate", "${s.speedLimitKBps}K")
        if (s.useAria2c && job.useAria2c) req.addOption("--downloader", "libaria2c.so")

        // وضعیت پیشرفت بین بخش‌ها (اول تصویر، بعد صدا)
        var completedBytes = 0L
        var lastPartTotal = 0L
        var lastDownloaded = -1L
        var metaRead = false

        if (control.stopRequested) return RunResult.Failure("STOPPED", false, "")
        engine.engineLock.readLock().lock()
        try {
            YoutubeDL.execute(req, processId(job.id), false) { _, _, line ->
                if (!metaRead) {
                    val t = File(dir, TITLE_FILE)
                    if (t.exists()) {
                        metaRead = true
                        val title = t.readLines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
                        val thumb = File(dir, THUMB_FILE).takeIf { it.exists() }?.readLines()
                            ?.lastOrNull { it.isNotBlank() && it != "NA" }?.trim().orEmpty()
                        control.update(false) {
                            it.copy(
                                title = if (it.title.isBlank() && title.isNotBlank()) title else it.title,
                                thumbnail = if (it.thumbnail.isBlank()) thumb else it.thumbnail,
                            )
                        }
                    }
                }
                when (val e = ProgressParser.parse(line)) {
                    is ProgressParser.Event.Progress -> {
                        if (e.total > 0) lastPartTotal = e.total
                        val activity = e.downloaded != lastDownloaded
                        lastDownloaded = e.downloaded
                        control.update(activity) {
                            val downloaded = completedBytes + e.downloaded
                            val knownTotal = completedBytes + (if (e.total > 0) e.total else 0)
                            val total = maxOf(knownTotal, it.expectedBytes)
                            it.copy(
                                phase = JobPhase.DOWNLOADING,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                speedBps = e.speedBps,
                                etaSec = e.etaSec,
                                progress = if (total > 0) (downloaded * 100.0 / total).coerceIn(0.0, 100.0) else e.percent,
                            )
                        }
                    }
                    is ProgressParser.Event.PartStarted -> {
                        if (lastDownloaded > 0) completedBytes += maxOf(lastPartTotal, lastDownloaded)
                        lastDownloaded = -1
                        lastPartTotal = 0
                        control.update(true) { it.copy(phase = JobPhase.DOWNLOADING) }
                    }
                    is ProgressParser.Event.Phase -> control.update(true) {
                        it.copy(phase = e.phase, speedBps = 0, etaSec = -1)
                    }
                    ProgressParser.Event.None -> Unit
                }
            }
        } catch (e: YoutubeDL.CanceledException) {
            return RunResult.Failure("STOPPED", false, "")
        } catch (e: InterruptedException) {
            return RunResult.Failure("STOPPED", false, "")
        } catch (e: YoutubeDLException) {
            val c = ErrorClassifier.classify(e.message)
            ErrorLog.write(ctx, "worker", "download#${job.id}", "${c.code}: ${c.detail}")
            return RunResult.Failure(c.code, c.retryable, c.detail)
        } finally {
            engine.engineLock.readLock().unlock()
        }

        val output = findOutput(dir)
            ?: return RunResult.Failure(ErrorClassifier.UNKNOWN, true, "فایل خروجی پیدا نشد")
        val titleFromFile = File(dir, TITLE_FILE).takeIf { it.exists() }?.readLines()
            ?.lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        return RunResult.Success { j ->
            j.copy(
                privatePath = output.absolutePath,
                outputName = output.name,
                sizeBytes = output.length(),
                title = if (j.title.isBlank()) titleFromFile.ifBlank { output.nameWithoutExtension } else j.title,
                progress = 100.0,
                downloadedBytes = output.length(),
                totalBytes = output.length(),
            )
        }
    }

    /** مسیر نهایی از after_move؛ اگر نبود بزرگ‌ترین فایل کامل پوشه. */
    private fun findOutput(dir: File): File? {
        val r = File(dir, RESULT_FILE)
        if (r.exists()) {
            val p = r.readLines().lastOrNull { it.isNotBlank() }?.trim()
            if (p != null) {
                val f = File(p)
                if (f.exists() && f.length() > 0) return f
            }
        }
        return dir.listFiles()
            ?.filter {
                it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") &&
                    !it.name.endsWith(".ytdl") && !it.name.endsWith(".aria2") && !it.name.contains(".part-Frag") &&
                    !Regex("""\.f\d+[a-z0-9-]*\.\w+$""").containsMatchIn(it.name) && it.length() > 0
            }
            ?.maxByOrNull { it.length() }
    }
}
