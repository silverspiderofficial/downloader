package com.ali.downloader.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/** مخزن ماندگار صف. پیاده‌سازی اصلی SQLite است و در تست‌ها نسخهٔ حافظه‌ای استفاده می‌شود. */
interface JobRepository {
    fun insert(job: Job): Long
    fun get(id: Long): Job?
    fun all(): List<Job>
    fun delete(id: Long)

    /**
     * تغییر وضعیت اتمی (در یک تراکنش): فقط اگر وضعیت فعلی در [expected] باشد، [mutate] اعمال می‌شود.
     * نتیجهٔ ذخیره‌شده یا null برمی‌گردد.
     */
    fun transition(id: Long, expected: Set<JobState>?, mutate: (Job) -> Job): Job?

    /** بازیابی بعد از کرش: هر کاری که RUNNING مانده بود دوباره در صف قرار می‌گیرد. */
    fun recoverInterrupted(now: Long): Int
}

/** تلاش دوباره با فاصلهٔ افزایشی: ۵، ۱۰، ۲۰، ۴۰ ثانیه ... تا سقف ۵ بار. */
object RetryPolicy {
    const val MAX_ATTEMPTS = 5
    private const val BASE_DELAY_MS = 5_000L
    private const val MAX_DELAY_MS = 5 * 60_000L

    fun delayMs(attempt: Int): Long {
        val a = max(1, attempt)
        val shift = min(a - 1, 20)
        return min(MAX_DELAY_MS, BASE_DELAY_MS shl shift)
    }

    fun shouldRetry(attemptsSoFar: Int): Boolean = attemptsSoFar < MAX_ATTEMPTS
}

/** وضعیت لحظه‌ای دستگاه که سیاست هم‌زمانی بر اساس آن تصمیم می‌گیرد. */
interface DeviceEnvironment {
    fun now(): Long
    fun availableRamBytes(): Long
    fun totalRamBytes(): Long
    fun isLowMemory(): Boolean
    fun cpuCores(): Int
    /** دمای باتری به درجهٔ سانتی‌گراد، یا NaN اگر نامعلوم. */
    fun batteryTempC(): Double
    fun isNetworkAvailable(): Boolean
    /** اتصال وای‌فای یا اتصال بدون محدودیت حجمی. */
    fun isUnmeteredNetwork(): Boolean
}

data class QueueSettings(
    /** سقفی که کاربر برای دانلود هم‌زمان گذاشته (۱ تا ۱۰). */
    val maxConcurrentDownloads: Int = 3,
    val wifiOnly: Boolean = false,
    /** محدودیت سرعت هر دانلود به کیلوبایت بر ثانیه؛ ۰ یعنی بدون محدودیت. */
    val speedLimitKBps: Int = 0,
    val useAria2c: Boolean = true,
    val embedSubs: Boolean = true,
    val mergeFormat: String = "mp4/mkv",
    val scheduleEnabled: Boolean = false,
    /** دقیقه از نیمه‌شب، مثلاً ۰ یعنی ۰۰:۰۰. */
    val scheduleMinuteOfDay: Int = 0,
    val autoEngineCheck: Boolean = true,
    /** آدرس درخت SAF پوشهٔ دلخواه کاربر؛ خالی یعنی Download/دانلودر. */
    val customFolderUri: String = "",
)

/**
 * تعیین تعداد کار هم‌زمان بر اساس رم آزاد، تعداد هسته، دمای باتری و سقف کاربر.
 * اگر رم آزاد کم باشد کار سنگین جدید شروع نمی‌شود (به‌جای اینکه اپ با OutOfMemory بسته شود).
 */
object ConcurrencyPolicy {
    /** حافظهٔ تقریبی یک پروسهٔ پایتون/yt-dlp همراه با FFmpeg. */
    const val RAM_PER_DOWNLOAD = 90L * 1024 * 1024
    /** حافظه‌ای که همیشه برای سیستم و رابط کاربری آزاد می‌ماند. */
    const val RAM_RESERVE = 200L * 1024 * 1024
    const val HOT_TEMP_C = 42.0
    const val CRITICAL_TEMP_C = 46.0
    const val MAX_USER_CAP = 10

    /**
     * [runningNow]: کارهای در حال اجرای همین مرحله. [recentStarts]: کارهایی که در چند ثانیهٔ اخیر شروع شده‌اند
     * و هنوز رمشان در «رم آزاد» دیده نمی‌شود؛ از ظرفیت تازه کم می‌شوند تا شروع پشت‌سرهم رم را تمام نکند.
     */
    fun limit(stage: JobStage, env: DeviceEnvironment, settings: QueueSettings, runningNow: Int, recentStarts: Int = 0): Int {
        if (env.isLowMemory()) return 0
        val temp = env.batteryTempC()
        val free = env.availableRamBytes()
        val newSlots = max(0, ((free - RAM_RESERVE) / RAM_PER_DOWNLOAD).toInt() - recentStarts)
        return when (stage) {
            JobStage.DOWNLOAD -> {
                val user = settings.maxConcurrentDownloads.coerceIn(1, MAX_USER_CAP)
                val cpu = max(2, env.cpuCores() * 2)
                // سقف کلی بر اساس کل رم دستگاه (حداکثر نیمی از رم برای موتورها)
                val totalCap = max(1, ((env.totalRamBytes() / 2 - RAM_RESERVE) / RAM_PER_DOWNLOAD).toInt())
                var l = min(min(user, cpu), totalCap)
                // دروازهٔ رم: کار تازه فقط اگر رم آزاد کافی باشد
                l = min(l, runningNow + newSlots)
                if (!temp.isNaN()) {
                    if (temp >= CRITICAL_TEMP_C) l = min(l, 1)
                    else if (temp >= HOT_TEMP_C) l = min(l, max(1, user / 2))
                }
                // روی دستگاه خیلی ضعیف حداقل یک کار اجرا شود، مگر اینکه سیستم «کمبود حافظه» اعلام کرده باشد.
                if (l <= 0 && runningNow == 0) l = 1
                l
            }
            JobStage.PUBLISH -> {
                var l = if (env.cpuCores() >= 4) 2 else 1
                if (!temp.isNaN() && temp >= CRITICAL_TEMP_C) l = 1
                l
            }
        }
    }
}

/** محاسبهٔ زمان بعدی شروع زمان‌بندی‌شده (مثلاً نیمه‌شب). */
object ScheduleTime {
    fun nextOccurrence(nowMs: Long, minuteOfDay: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, (minuteOfDay / 60).coerceIn(0, 23))
        cal.set(Calendar.MINUTE, (minuteOfDay % 60).coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}

object PersianDigits {
    private const val FA = "۰۱۲۳۴۵۶۷۸۹"
    fun of(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') FA[c - '0'] else c)
        return sb.toString()
    }

    fun bytes(b: Long): String {
        if (b <= 0) return of("0") + " بایت"
        val units = arrayOf("بایت", "کیلوبایت", "مگابایت", "گیگابایت", "ترابایت")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        val txt = if (i == 0 || v >= 100) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)
        return of(txt).replace('.', '٫') + " " + units[i]
    }

    fun duration(sec: Long): String {
        if (sec < 0) return "—"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        val txt = if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
        return of(txt)
    }
}
