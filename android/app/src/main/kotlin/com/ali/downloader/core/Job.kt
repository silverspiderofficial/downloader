package com.ali.downloader.core

/**
 * مدل یک کار در صف. این لایه هیچ وابستگی‌ای به اندروید ندارد تا در تست‌های JVM قابل آزمایش باشد.
 */
enum class JobState {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_NETWORK,
    COMPLETED,
    FAILED,
    CANCELED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELED
}

/**
 * مرحلهٔ کار؛ هر مرحله Worker Pool جدای خودش را دارد.
 * DOWNLOAD: اجرای yt-dlp (دانلود + ادغام صدا و تصویر با FFmpeg + جاسازی زیرنویس)
 * PUBLISH: انتقال فایل نهایی از پوشهٔ خصوصی اپ به پوشهٔ عمومی (MediaStore / SAF / حافظهٔ قدیمی)
 */
enum class JobStage { DOWNLOAD, PUBLISH }

/** فاز جزئی‌تر برای نمایش و برای Watchdog (ادغام زمان بیشتری بدون پیشرفت بایت لازم دارد). */
enum class JobPhase { PREPARING, DOWNLOADING, MERGING, EMBEDDING, PUBLISHING, DONE }

/** دلیل انتظار یک کار صف‌شده (برای نمایش). */
enum class WaitReason { NONE, RETRY_BACKOFF, SCHEDULED, NO_NETWORK, WIFI_REQUIRED, LOW_RESOURCES }

data class Job(
    val id: Long = 0,
    val url: String,
    val title: String = "",
    val thumbnail: String = "",
    /** رشتهٔ انتخاب فرمت yt-dlp، مثل «137+bestaudio/137». */
    val formatSelector: String,
    /** برچسب قابل نمایش کیفیت، مثل «1080p60 HDR». */
    val formatLabel: String = "",
    /** «mp4/mkv» یا «mkv». */
    val mergeFormat: String = "mp4/mkv",
    val embedSubs: Boolean = true,
    val stage: JobStage = JobStage.DOWNLOAD,
    val state: JobState = JobState.QUEUED,
    val phase: JobPhase = JobPhase.PREPARING,
    val waitReason: WaitReason = WaitReason.NONE,
    /** درصد ۰ تا ۱۰۰، یا ۱- اگر نامعلوم. */
    val progress: Double = -1.0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBps: Long = 0,
    val etaSec: Long = -1,
    /** تعداد تلاش‌های ناموفق تا الان. */
    val attempts: Int = 0,
    val errorCode: String = "",
    val errorDetail: String = "",
    /** زمان (میلی‌ثانیه) که قبل از آن کار شروع نمی‌شود: برای backoff و زمان‌بندی. */
    val notBefore: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val finishedAt: Long = 0,
    /** مسیر فایل نهایی داخل پوشهٔ خصوصی اپ، بعد از پایان مرحلهٔ دانلود. */
    val privatePath: String = "",
    /** آدرس فایل منتشرشده (content:// یا مسیر فایل). */
    val outputUri: String = "",
    val outputName: String = "",
    val sizeBytes: Long = 0,
    /** اگر aria2c برای این کار خطا داد، تلاش بعدی با دانلودر داخلی yt-dlp انجام می‌شود. */
    val useAria2c: Boolean = true,
    /** اگر دانلود زیرنویس باعث خطا شد، تلاش بعدی بدون زیرنویس انجام می‌شود. */
    val subsDisabled: Boolean = false,
    /** مرجع موقت انتشار (مثلاً آیتم IS_PENDING در MediaStore) برای پاک‌سازی بعد از کرش. */
    val publishTempRef: String = "",
    val expectedBytes: Long = 0,
)
