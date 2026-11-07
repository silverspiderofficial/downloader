package com.ali.downloader.core

import kotlin.math.roundToLong

/**
 * تحلیل خروجی خط‌به‌خط yt-dlp و aria2c برای درصد، سرعت و زمان باقی‌مانده.
 * قالب پیشرفت yt-dlp با --progress-template خودمان تعیین می‌شود تا تحلیل آن پایدار باشد.
 */
object ProgressParser {
    const val TEMPLATE_PREFIX = "[DLP]"

    /** قالبی که به yt-dlp داده می‌شود. مقادیر نامعلوم «NA» چاپ می‌شوند. */
    const val PROGRESS_TEMPLATE =
        "download:$TEMPLATE_PREFIX %(progress.downloaded_bytes)s %(progress.total_bytes)s " +
            "%(progress.total_bytes_estimate)s %(progress.speed)s %(progress.eta)s"

    sealed class Event {
        data class Progress(
            val downloaded: Long,
            val total: Long,
            val speedBps: Long,
            val etaSec: Long,
            val percent: Double,
        ) : Event()

        /** شروع دانلود یک بخش تازه (مثلاً اول تصویر، بعد صدا). */
        data class PartStarted(val path: String) : Event()
        data class Phase(val phase: JobPhase) : Event()
        object None : Event()
    }

    private val ariaRegex = Regex(
        """\[#[0-9a-fA-F]+\s+([0-9.]+[KMGT]?i?B)/([0-9.]+[KMGT]?i?B)\((\d+)%\)(?:\s+CN:\d+)?(?:\s+DL:([0-9.]+[KMGT]?i?B))?(?:\s+ETA:((?:\d+h)?(?:\d+m)?(?:\d+s)?))?"""
    )

    fun parse(rawLine: String): Event {
        val line = rawLine.trim()
        if (line.isEmpty()) return Event.None
        if (line.startsWith(TEMPLATE_PREFIX)) return parseTemplate(line)
        if (line.startsWith("[#")) {
            val m = ariaRegex.find(line) ?: return Event.None
            val done = parseSize(m.groupValues[1])
            val total = parseSize(m.groupValues[2])
            val pct = m.groupValues[3].toDoubleOrNull() ?: -1.0
            val speed = if (m.groupValues[4].isNotEmpty()) parseSize(m.groupValues[4]) else 0L
            val eta = if (m.groupValues[5].isNotEmpty()) parseHms(m.groupValues[5]) else -1L
            return Event.Progress(done, total, speed, eta, pct)
        }
        if (line.startsWith("[download] Destination:")) {
            return Event.PartStarted(line.removePrefix("[download] Destination:").trim())
        }
        if (line.startsWith("[Merger]") || line.startsWith("[VideoConvertor]") ||
            line.startsWith("[VideoRemuxer]") || line.startsWith("[Fixup")
        ) return Event.Phase(JobPhase.MERGING)
        if (line.startsWith("[EmbedSubtitle]") || line.startsWith("[Metadata]") ||
            line.startsWith("[EmbedThumbnail]")
        ) return Event.Phase(JobPhase.EMBEDDING)
        return Event.None
    }

    private fun parseTemplate(line: String): Event {
        val parts = line.removePrefix(TEMPLATE_PREFIX).trim().split(Regex("\\s+"))
        if (parts.size < 5) return Event.None
        val downloaded = num(parts[0])
        val total = num(parts[1]).takeIf { it > 0 } ?: num(parts[2])
        val speed = num(parts[3])
        val eta = parts[4].toDoubleOrNull()?.roundToLong() ?: -1L
        val pct = if (total > 0) (downloaded.toDouble() * 100.0 / total).coerceIn(0.0, 100.0) else -1.0
        return Event.Progress(downloaded, total, speed, eta, pct)
    }

    private fun num(s: String): Long = s.toDoubleOrNull()?.roundToLong() ?: 0L

    /** «1.5MiB» یا «200KiB» یا «12B» را به بایت تبدیل می‌کند. */
    fun parseSize(s: String): Long {
        val m = Regex("""([0-9.]+)\s*([KMGT]?)(i?)B""").find(s.trim()) ?: return 0
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0
        val base = if (m.groupValues[3] == "i") 1024.0 else 1000.0
        val mult = when (m.groupValues[2]) {
            "K" -> base
            "M" -> base * base
            "G" -> base * base * base
            "T" -> base * base * base * base
            else -> 1.0
        }
        return (v * mult).roundToLong()
    }

    /** «1h2m3s» یا «5m» یا «19s» را به ثانیه تبدیل می‌کند. */
    fun parseHms(s: String): Long {
        var total = 0L
        Regex("""(\d+)([hms])""").findAll(s).forEach {
            val n = it.groupValues[1].toLong()
            total += when (it.groupValues[2]) {
                "h" -> n * 3600
                "m" -> n * 60
                else -> n
            }
        }
        return total
    }
}
