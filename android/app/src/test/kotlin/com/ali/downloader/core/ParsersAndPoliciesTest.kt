package com.ali.downloader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class ParsersAndPoliciesTest {

    @Test
    fun parsesOwnProgressTemplate() {
        val e = ProgressParser.parse("[DLP] 1048576 4194304 NA 524288.5 6") as ProgressParser.Event.Progress
        assertEquals(1048576L, e.downloaded)
        assertEquals(4194304L, e.total)
        assertEquals(524289L, e.speedBps)
        assertEquals(6L, e.etaSec)
        assertEquals(25.0, e.percent, 0.001)
    }

    @Test
    fun templateFallsBackToEstimateWhenTotalUnknown() {
        val e = ProgressParser.parse("[DLP] 500 NA 1000.0 NA NA") as ProgressParser.Event.Progress
        assertEquals(1000L, e.total)
        assertEquals(50.0, e.percent, 0.001)
        assertEquals(-1L, e.etaSec)
        assertEquals(0L, e.speedBps)
    }

    @Test
    fun parsesAria2cReadout() {
        val e = ProgressParser.parse("[#2089b0 1.4MiB/23MiB(6%) CN:16 DL:1.1MiB ETA:1m19s]") as ProgressParser.Event.Progress
        assertTrue(kotlin.math.abs((1.4 * 1024 * 1024).toLong() - e.downloaded) <= 2)
        assertEquals(23L * 1024 * 1024, e.total)
        assertEquals(6.0, e.percent, 0.0)
        assertTrue(kotlin.math.abs((1.1 * 1024 * 1024).toLong() - e.speedBps) <= 2)
        assertEquals(79L, e.etaSec)
    }

    @Test
    fun parsesAria2cWithoutEta() {
        val e = ProgressParser.parse("[#abcdef 0B/0B(0%) CN:1 DL:0B]") as ProgressParser.Event.Progress
        assertEquals(0L, e.total)
        assertEquals(-1L, e.etaSec)
    }

    @Test
    fun detectsPartsAndPhases() {
        assertTrue(ProgressParser.parse("[download] Destination: /x/a.f137.mp4") is ProgressParser.Event.PartStarted)
        assertEquals(
            ProgressParser.Event.Phase(JobPhase.MERGING),
            ProgressParser.parse("[Merger] Merging formats into \"/x/a.mp4\""),
        )
        assertEquals(
            ProgressParser.Event.Phase(JobPhase.EMBEDDING),
            ProgressParser.parse("[EmbedSubtitle] Embedding subtitles in \"/x/a.mp4\""),
        )
        assertEquals(ProgressParser.Event.None, ProgressParser.parse("[youtube] abc: Downloading webpage"))
    }

    @Test
    fun parsesSizesAndDurations() {
        assertEquals(1536L, ProgressParser.parseSize("1.5KiB"))
        assertEquals(2_000_000L, ProgressParser.parseSize("2MB"))
        assertEquals(3723L, ProgressParser.parseHms("1h2m3s"))
        assertEquals(19L, ProgressParser.parseHms("19s"))
    }

    @Test
    fun classifiesErrors() {
        assertEquals(ErrorClassifier.DRM, ErrorClassifier.classify("ERROR: [Netflix] 123: This video is DRM protected").code)
        assertFalse(ErrorClassifier.classify("ERROR: This video is DRM protected").retryable)
        assertEquals(ErrorClassifier.NETWORK, ErrorClassifier.classify("ERROR: Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>").code)
        assertTrue(ErrorClassifier.classify("ERROR: unable to download video data: HTTP Error 403: Forbidden").retryable)
        assertEquals(ErrorClassifier.RATE_LIMITED, ErrorClassifier.classify("HTTP Error 429: Too Many Requests").code)
        assertEquals(ErrorClassifier.UNSUPPORTED_URL, ErrorClassifier.classify("ERROR: Unsupported URL: https://example.com").code)
        assertEquals(ErrorClassifier.LOGIN_REQUIRED, ErrorClassifier.classify("ERROR: [youtube] x: Sign in to confirm your age").code)
        assertEquals(ErrorClassifier.GEO_BLOCKED, ErrorClassifier.classify("The uploader has not made this video available in your country").code)
        assertEquals(ErrorClassifier.NO_SPACE, ErrorClassifier.classify("OSError: [Errno 28] No space left on device").code)
        assertEquals(ErrorClassifier.ENGINE_OUTDATED, ErrorClassifier.classify("ERROR: Unable to extract uploader id; please report this issue").code)
        assertEquals(ErrorClassifier.SUBTITLES, ErrorClassifier.classify("ERROR: Unable to download video subtitles for 'en': HTTP Error 429").code)
        assertEquals(ErrorClassifier.FORMAT_UNAVAILABLE, ErrorClassifier.classify("ERROR: [youtube] x: Requested format is not available").code)
        assertEquals(ErrorClassifier.UNKNOWN, ErrorClassifier.classify("something odd").code)
    }

    @Test
    fun lastErrorLineIsShown() {
        val text = "WARNING: a\nERROR: first\nsome trace\nERROR: final reason\n"
        assertEquals("ERROR: final reason", ErrorClassifier.lastErrorLine(text))
    }

    @Test
    fun sha256OfFileAndSumsLookup() {
        val f = File.createTempFile("sha", ".bin")
        f.writeText("abc")
        val h = Sha256.ofFile(f)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h)
        f.delete()
        val sums = "$h  yt-dlp\n${"0".repeat(64)}  yt-dlp.exe\n"
        assertEquals(h, Sha256.findInSums(sums, "yt-dlp"))
        assertNull(Sha256.findInSums(sums, "yt-dlp_linux"))
        assertEquals(h, Sha256.findInSums("$h *yt-dlp", "yt-dlp"))
    }

    @Test
    fun backoffDelays() {
        assertEquals(5_000L, RetryPolicy.delayMs(1))
        assertEquals(80_000L, RetryPolicy.delayMs(5))
        assertEquals(300_000L, RetryPolicy.delayMs(30))
        assertTrue(RetryPolicy.shouldRetry(4))
        assertFalse(RetryPolicy.shouldRetry(5))
    }

    @Test
    fun concurrencyAdaptsToRamTemperatureAndCap() {
        val env = FakeEnv()
        val s = QueueSettings(maxConcurrentDownloads = 10)
        assertEquals(10, ConcurrencyPolicy.limit(JobStage.DOWNLOAD, env, s, 0))
        env.cores = 2
        assertEquals(4, ConcurrencyPolicy.limit(JobStage.DOWNLOAD, env, s, 0))
        env.cores = 8
        env.freeRam = 400L * 1024 * 1024
        assertEquals(2, ConcurrencyPolicy.limit(JobStage.DOWNLOAD, env, s, 0))
        assertEquals(5, ConcurrencyPolicy.limit(JobStage.DOWNLOAD, env, s, 3))
        env.freeRam = 100L * 1024 * 1024
        assertEquals(1, ConcurrencyPolicy.limit(JobStage.DOWNLOAD, env, s, 0))
        assertEquals(2, ConcurrencyPolicy.limit(JobStage.DOWNLOAD, env, s, 2))
        env.freeRam = 4L * 1024 * 1024 * 1024
        env.temp = 43.0
        assertEquals(5, ConcurrencyPolicy.limit(JobStage.DOWNLOAD, env, s, 0))
        env.temp = 47.0
        assertEquals(1, ConcurrencyPolicy.limit(JobStage.DOWNLOAD, env, s, 0))
        env.lowMem = true
        assertEquals(0, ConcurrencyPolicy.limit(JobStage.DOWNLOAD, env, s, 0))
    }

    @Test
    fun nextScheduledOccurrence() {
        val tz = TimeZone.getTimeZone("Asia/Tehran")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 7, 22, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        val next = ScheduleTime.nextOccurrence(now, 0, tz)
        val c2 = Calendar.getInstance(tz).apply { timeInMillis = next }
        assertEquals(8, c2.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, c2.get(Calendar.HOUR_OF_DAY))
        val sameDay = ScheduleTime.nextOccurrence(now, 23 * 60, tz)
        assertEquals(30L * 60 * 1000, sameDay - now)
    }

    @Test
    fun persianDigitsAndSizes() {
        assertEquals("۱۲۳", PersianDigits.of("123"))
        assertEquals("۱٫۵ مگابایت".replace('٫', '.'), PersianDigits.bytes(1572864).replace('٫', '.'))
        assertEquals("۱:۰۱:۰۵", PersianDigits.duration(3665))
    }
}
