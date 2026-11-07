package com.ali.downloader.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ali.downloader.MainActivity
import com.ali.downloader.R
import com.ali.downloader.core.Job
import com.ali.downloader.core.JobPhase
import com.ali.downloader.core.JobStage
import com.ali.downloader.core.JobState
import com.ali.downloader.core.PersianDigits
import java.util.concurrent.ConcurrentHashMap

/** اعلان‌ها: یک اعلان خلاصهٔ دائمی (سرویس پیش‌زمینه) و یک اعلان برای هر کار با دکمه‌های توقف، ادامه و لغو. */
class Notifier(private val ctx: Context) {

    companion object {
        const val CHANNEL_QUEUE = "queue"
        const val CHANNEL_RESULTS = "results"
        const val SUMMARY_ID = 1
        const val RESUME_HINT_ID = 2
        private const val JOB_BASE = 10_000

        fun shortError(code: String): String = when (code) {
            "DRM" -> "محتوای دارای DRM پشتیبانی نمی‌شود"
            "NETWORK" -> "اتصال اینترنت قطع یا ناپایدار است"
            "RATE_LIMITED" -> "سایت موقتاً درخواست‌ها را محدود کرده"
            "FORBIDDEN" -> "سایت دسترسی را رد کرد (۴۰۳)"
            "UNSUPPORTED_URL" -> "این لینک پشتیبانی نمی‌شود"
            "LOGIN_REQUIRED" -> "این ویدیو به ورود به حساب نیاز دارد"
            "GEO_BLOCKED" -> "این ویدیو در منطقهٔ شما در دسترس نیست"
            "UNAVAILABLE" -> "ویدیو حذف شده یا در دسترس نیست"
            "NO_SPACE" -> "فضای خالی حافظه کافی نیست"
            "FORMAT_UNAVAILABLE" -> "کیفیت انتخاب‌شده دیگر موجود نیست"
            "ENGINE_OUTDATED" -> "سایت تغییر کرده؛ موتور را به‌روز کنید"
            "SUBTITLES" -> "دریافت زیرنویس ناموفق بود"
            "ARIA2C" -> "خطای دانلود چنداتصالی"
            "MERGE_FAILED" -> "ادغام صدا و تصویر ناموفق بود"
            "UPCOMING_LIVE" -> "پخش زنده هنوز شروع نشده"
            "STALLED" -> "دانلود مدتی پیشرفت نداشت"
            "STORAGE_PERMISSION" -> "اجازهٔ ذخیره در حافظه داده نشده"
            "PUBLISH_FAILED" -> "انتقال فایل به پوشهٔ دانلود ناموفق بود"
            else -> "خطای ناشناخته"
        }
    }

    private val nm = NotificationManagerCompat.from(ctx)
    private val lastJobUpdate = ConcurrentHashMap<Long, Long>()

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val m = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            m.createNotificationChannel(
                NotificationChannel(CHANNEL_QUEUE, "صف دانلود", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "پیشرفت دانلودها و وضعیت صف"
                    setShowBadge(false)
                }
            )
            m.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULTS, "نتیجهٔ دانلود", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "پایان یا خطای هر دانلود"
                }
            )
        }
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(action: String, jobId: Long): PendingIntent {
        val i = Intent(ctx, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_JOB_ID, jobId)
        }
        val req = (action.hashCode() * 31 + jobId.toInt())
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun summary(running: Int, queued: Int, waitingNet: Int): Notification {
        val text = buildString {
            if (running > 0) append(PersianDigits.of(running.toString())).append(" کار در حال اجرا")
            if (queued > 0) {
                if (isNotEmpty()) append(" · ")
                append(PersianDigits.of(queued.toString())).append(" در صف")
            }
            if (waitingNet > 0) {
                if (isNotEmpty()) append(" · ")
                append(PersianDigits.of(waitingNet.toString())).append(" منتظر اینترنت")
            }
            if (isEmpty()) append("صف دانلود آماده است")
        }
        return NotificationCompat.Builder(ctx, CHANNEL_QUEUE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("دانلودر همه‌کاره")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "توقف همه", actionIntent(NotificationActionReceiver.ACTION_PAUSE_ALL, 0))
            .addAction(0, "ادامهٔ همه", actionIntent(NotificationActionReceiver.ACTION_RESUME_ALL, 0))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateSummary(running: Int, queued: Int, waitingNet: Int) {
        try {
            nm.notify(SUMMARY_ID, summary(running, queued, waitingNet))
        } catch (_: SecurityException) {
        }
    }

    private fun jobTitle(j: Job) = j.title.ifBlank { j.url }

    /** اعلان هر کار؛ حداکثر یک‌بار در ثانیه به‌روز می‌شود تا محدودیت نرخ اندروید رد نشود. */
    fun job(j: Job, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - (lastJobUpdate[j.id] ?: 0L) < 1000) return
        lastJobUpdate[j.id] = now
        val id = JOB_BASE + (j.id % 1_000_000).toInt()
        try {
            when (j.state) {
                JobState.RUNNING -> {
                    val pct = j.progress
                    val label = when (j.phase) {
                        JobPhase.MERGING -> "در حال ادغام صدا و تصویر…"
                        JobPhase.EMBEDDING -> "در حال جاسازی زیرنویس و اطلاعات…"
                        JobPhase.PUBLISHING -> "انتقال به پوشهٔ دانلود…"
                        JobPhase.PREPARING -> "در حال آماده‌سازی…"
                        else -> ""
                    }
                    val text = if (label.isNotEmpty() && j.phase != JobPhase.PUBLISHING) label else buildString {
                        if (label.isNotEmpty()) append(label).append(" ")
                        if (pct >= 0) append(PersianDigits.of(pct.toInt().toString())).append("٪")
                        if (j.speedBps > 0) append(" · ").append(PersianDigits.bytes(j.speedBps)).append("/ث")
                        if (j.etaSec >= 0) append(" · ").append(PersianDigits.duration(j.etaSec)).append(" مانده")
                    }
                    val b = NotificationCompat.Builder(ctx, CHANNEL_QUEUE)
                        .setSmallIcon(R.drawable.ic_stat_download)
                        .setContentTitle(jobTitle(j))
                        .setContentText(text)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setSilent(true)
                        .setContentIntent(openAppIntent())
                        .setProgress(100, pct.coerceIn(0.0, 100.0).toInt(), pct < 0 || j.phase == JobPhase.MERGING || j.phase == JobPhase.EMBEDDING)
                    if (j.stage == JobStage.DOWNLOAD) {
                        b.addAction(0, "توقف", actionIntent(NotificationActionReceiver.ACTION_PAUSE, j.id))
                    }
                    b.addAction(0, "لغو", actionIntent(NotificationActionReceiver.ACTION_CANCEL, j.id))
                    nm.notify(id, b.build())
                }
                JobState.PAUSED -> {
                    val b = NotificationCompat.Builder(ctx, CHANNEL_QUEUE)
                        .setSmallIcon(R.drawable.ic_stat_download)
                        .setContentTitle(jobTitle(j))
                        .setContentText("متوقف شده" + if (j.progress >= 0) " · " + PersianDigits.of(j.progress.toInt().toString()) + "٪" else "")
                        .setOnlyAlertOnce(true)
                        .setSilent(true)
                        .setContentIntent(openAppIntent())
                        .addAction(0, "ادامه", actionIntent(NotificationActionReceiver.ACTION_RESUME, j.id))
                        .addAction(0, "لغو", actionIntent(NotificationActionReceiver.ACTION_CANCEL, j.id))
                    nm.notify(id, b.build())
                }
                JobState.COMPLETED -> {
                    val b = NotificationCompat.Builder(ctx, CHANNEL_RESULTS)
                        .setSmallIcon(R.drawable.ic_stat_download)
                        .setContentTitle("دانلود تمام شد")
                        .setContentText(jobTitle(j))
                        .setAutoCancel(true)
                        .setContentIntent(openAppIntent())
                    nm.notify(id, b.build())
                }
                JobState.FAILED -> {
                    val b = NotificationCompat.Builder(ctx, CHANNEL_RESULTS)
                        .setSmallIcon(R.drawable.ic_stat_download)
                        .setContentTitle("دانلود ناموفق: " + shortError(j.errorCode))
                        .setContentText(jobTitle(j))
                        .setAutoCancel(true)
                        .setContentIntent(openAppIntent())
                        .addAction(0, "تلاش دوباره", actionIntent(NotificationActionReceiver.ACTION_RETRY, j.id))
                    nm.notify(id, b.build())
                }
                else -> nm.cancel(id)
            }
        } catch (_: SecurityException) {
            // اجازهٔ اعلان (اندروید ۱۳+) داده نشده؛ دانلود بدون اعلان ادامه می‌یابد
        }
    }

    fun cancelJob(jobId: Long) {
        nm.cancel(JOB_BASE + (jobId % 1_000_000).toInt())
        lastJobUpdate.remove(jobId)
    }

    fun resumeHint(text: String) {
        try {
            nm.notify(
                RESUME_HINT_ID,
                NotificationCompat.Builder(ctx, CHANNEL_RESULTS)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle("دانلودر همه‌کاره")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .build()
            )
        } catch (_: SecurityException) {
        }
    }
}
