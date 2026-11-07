package com.ali.downloader.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ali.downloader.common.ErrorLog
import com.ali.downloader.core.JobState

/** دکمه‌های اعلان (توقف، ادامه، لغو، تلاش دوباره). در پروسهٔ worker اجرا می‌شود. */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PAUSE = "com.ali.downloader.N_PAUSE"
        const val ACTION_RESUME = "com.ali.downloader.N_RESUME"
        const val ACTION_CANCEL = "com.ali.downloader.N_CANCEL"
        const val ACTION_RETRY = "com.ali.downloader.N_RETRY"
        const val ACTION_PAUSE_ALL = "com.ali.downloader.N_PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.ali.downloader.N_RESUME_ALL"
        const val EXTRA_JOB_ID = "jobId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_JOB_ID, 0)
        val action = when (intent.action) {
            ACTION_PAUSE -> "pause"
            ACTION_RESUME -> "resume"
            ACTION_CANCEL -> "cancel"
            ACTION_RETRY -> "retry"
            ACTION_PAUSE_ALL -> "pauseAll"
            ACTION_RESUME_ALL -> "resumeAll"
            else -> return
        }
        val svc = DownloadService.instance
        if (svc != null) {
            svc.handleJobAction(action, id)
            return
        }
        // سرویس در حال اجرا نیست: با فرمان شروع می‌شود (لمس اعلان اجازهٔ شروع سرویس پیش‌زمینه را می‌دهد)
        try {
            val i = Intent(context, DownloadService::class.java)
                .setAction(DownloadService.ACTION_JOB)
                .putExtra(DownloadService.EXTRA_JOB_ACTION, action)
                .putExtra(DownloadService.EXTRA_JOB_ID, id)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        } catch (t: Throwable) {
            ErrorLog.write(context, "worker", "notif-action", t.message ?: "", t)
        }
    }
}

/**
 * بعد از ریستارت گوشی: اگر کار نیمه‌تمامی در صف هست، سرویس دوباره شروع می‌شود.
 * اندروید ۱۵+ اجازهٔ شروع سرویس dataSync از BOOT_COMPLETED را نمی‌دهد؛ پس چند ثانیه بعد با آلارم تلاش می‌شود
 * و اگر باز هم اجازه نبود، اعلانی برای ادامه با یک لمس نمایش داده می‌شود.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED &&
            a != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val pending = try {
            SqliteJobRepository(context).all().any {
                it.state == JobState.QUEUED || it.state == JobState.RUNNING || it.state == JobState.WAITING_NETWORK
            }
        } catch (t: Throwable) {
            ErrorLog.write(context, "worker", "boot", t.message ?: "", t)
            false
        }
        if (!pending) return
        if (Build.VERSION.SDK_INT >= 35) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 8, Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 15_000, pi)
        } else if (!DownloadService.startForegroundCompat(context, DownloadService.ACTION_WAKE)) {
            Notifier(context).apply { createChannels() }
                .resumeHint("دانلودهای نیمه‌تمام در صف هستند. برای ادامه ضربه بزنید.")
        }
    }
}

/** آلارم زمان‌بندی (مثلاً شروع دانلود در نیمه‌شب) یا ادامهٔ بعد از ریستارت. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DownloadService.startForegroundCompat(context, DownloadService.ACTION_WAKE)) {
            Notifier(context).apply { createChannels() }
                .resumeHint("زمان شروع دانلودهای صف رسیده است. برای شروع ضربه بزنید.")
        }
    }
}
