package com.ali.downloader.worker

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ali.downloader.common.ErrorLog
import com.ali.downloader.core.Job
import com.ali.downloader.core.JobScheduler
import com.ali.downloader.core.JobStage
import com.ali.downloader.core.JobState
import com.ali.downloader.core.QueueSettings
import com.ali.downloader.core.SchedulerListener
import com.ali.downloader.ipc.Ipc
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * سرویس پیش‌زمینه (نوع dataSync) در پروسهٔ جدای «:worker». موتورهای سنگین فقط در این پروسه اجرا می‌شوند،
 * پس کرش آن‌ها هرگز رابط کاربری را نمی‌بندد. صف ماندگار، Worker Poolها، Watchdog، اعلان‌ها،
 * WakeLock/WifiLock و ارتباط Messenger با پروسهٔ اصلی اینجاست.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.ali.downloader.START"
        const val ACTION_WAKE = "com.ali.downloader.WAKE"
        const val ACTION_JOB = "com.ali.downloader.JOB_ACTION"
        const val EXTRA_JOB_ACTION = "jobAction"
        const val EXTRA_JOB_ID = "jobId"
        private const val TICK_MS = 5_000L
        private const val PROCESS = "worker"

        @Volatile
        var instance: DownloadService? = null
            private set

        /** شروع سرویس به‌صورت پیش‌زمینه؛ اگر اندروید اجازه ندهد false برمی‌گردد. */
        fun startForegroundCompat(ctx: Context, action: String = ACTION_START): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java).setAction(action))
            true
        } catch (t: Throwable) {
            ErrorLog.write(ctx, PROCESS, "fgs-start", t.message ?: "", t)
            false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repo: SqliteJobRepository
    private lateinit var monitor: DeviceMonitor
    private lateinit var engine: EngineManager
    private lateinit var notifier: Notifier
    private lateinit var scheduler: JobScheduler
    @Volatile private var settings = QueueSettings()

    private val clients = ArrayList<Messenger>()
    private var isForeground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var snapshotPosted = false
    private var engineBusy = false
    private var engineReady = false

    private fun namedFactory(prefix: String) = object : ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "$prefix-${n.incrementAndGet()}").apply { isDaemon = true }
    }

    private val jobExecutor = Executors.newCachedThreadPool(namedFactory("job"))
    private val analyzeExecutor = Executors.newFixedThreadPool(2, namedFactory("analyze"))
    private val engineExecutor = Executors.newSingleThreadExecutor(namedFactory("engine"))

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                scheduler.tick()
                manageForegroundState()
            } catch (t: Throwable) {
                ErrorLog.write(this@DownloadService, PROCESS, "tick", t.message ?: "", t)
            }
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    // ---------------- چرخهٔ عمر ----------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            ErrorLog.write(this, PROCESS, "crash", "thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
        notifier = Notifier(this).apply { createChannels() }
        repo = SqliteJobRepository(this)
        settings = Ipc.settingsFromJson(repo.getKv("settings"))
        engine = EngineManager(this, repo)
        monitor = DeviceMonitor(this) { mainHandler.post { safeTick() } }
        monitor.register()
        scheduler = JobScheduler(
            repo = repo,
            env = monitor,
            runners = mapOf(
                JobStage.DOWNLOAD to YtDlpRunner(this, engine) { settings },
                JobStage.PUBLISH to Publisher(this) { settings.customFolderUri },
            ),
            executor = jobExecutor,
            listener = listener,
            initialSettings = settings,
        )
        // تا آماده شدن موتور کاری شروع نمی‌شود؛ بعد کارهای نیمه‌تمام قبلی از صف ادامه می‌یابند.
        scheduler.holdNewStarts = true
        engineExecutor.execute {
            engine.init()
            val recovered = scheduler.start()
            if (recovered > 0) ErrorLog.write(this, PROCESS, "recovery", "$recovered کار نیمه‌تمام دوباره در صف قرار گرفت")
            repo.trimFinished()
            mainHandler.post {
                engineReady = true
                scheduler.holdNewStarts = false
                maybeAutoUpdateEngine()
            }
        }
        mainHandler.postDelayed(tickRunnable, TICK_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            // اندروید اجازهٔ سرویس پیش‌زمینه را نداد (مثلاً بلافاصله بعد از بوت در اندروید ۱۵)؛
            // سرویس متوقف می‌شود تا کرش نکند و کاربر با یک لمس ادامه می‌دهد.
            notifier.resumeHint("دانلودهای نیمه‌تمام در صف هستند. برای ادامه ضربه بزنید.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // اگر قبلاً (مثلاً با پایان مهلت سرویس) شروع کارها متوقف شده بود، دوباره آزاد می‌شود
        if (engineReady && !engineBusy && scheduler.holdNewStarts) scheduler.holdNewStarts = false
        when (intent?.action) {
            ACTION_JOB -> {
                val id = intent.getLongExtra(EXTRA_JOB_ID, 0)
                handleJobAction(intent.getStringExtra(EXTRA_JOB_ACTION) ?: "", id)
            }
        }
        mainHandler.post { manageForegroundState() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // بستن اپ از فهرست اخیر نباید دانلودها را متوقف کند
        super.onTaskRemoved(rootIntent)
    }

    /** اندروید ۱۵+: پایان سهمیهٔ زمانی سرویس dataSync. کارها متوقف و بعداً از همان نقطه ادامه می‌یابند. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ErrorLog.write(this, PROCESS, "fgs-timeout", "dataSync time limit reached")
        scheduler.shutdown()
        notifier.resumeHint("اندروید سهمیهٔ زمانی کار در پس‌زمینه را تمام کرد. دانلودها ذخیره شدند؛ برای ادامه، اپ را باز کنید.")
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            scheduler.shutdown()
        } catch (_: Throwable) {
        }
        monitor.unregister()
        releaseLocks()
        instance = null
        jobExecutor.shutdown()
        analyzeExecutor.shutdown()
        engineExecutor.shutdown()
        super.onDestroy()
    }

    private fun safeTick() {
        try {
            scheduler.tick()
        } catch (t: Throwable) {
            ErrorLog.write(this, PROCESS, "tick", t.message ?: "", t)
        }
    }

    // ---------------- پیش‌زمینه و قفل‌ها ----------------

    private fun goForeground(): Boolean {
        if (isForeground) return true
        return try {
            val n = notifier.summary(0, 0, 0)
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, Notifier.SUMMARY_ID, n, type)
            isForeground = true
            true
        } catch (t: Throwable) {
            ErrorLog.write(this, PROCESS, "startForeground", t.message ?: "", t)
            false
        }
    }

    private fun stopForegroundCompat() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        isForeground = false
    }

    /**
     * اگر کاری نمانده، سرویس از حالت پیش‌زمینه خارج و متوقف می‌شود (پروسه با اتصال رابط کاربری زنده می‌ماند).
     * اگر فقط کارهای زمان‌بندی‌شده مانده‌اند، آلارم تنظیم می‌شود تا سر وقت سرویس دوباره بالا بیاید.
     */
    private fun manageForegroundState() {
        if (!::scheduler.isInitialized) return
        val jobs = scheduler.snapshot()
        val running = jobs.count { it.state == JobState.RUNNING }
        val now = System.currentTimeMillis()
        val queuedNow = jobs.count { it.state == JobState.QUEUED && it.notBefore <= now + 60_000 }
        val queuedLater = jobs.count { it.state == JobState.QUEUED && it.notBefore > now + 60_000 }
        val waitingNet = jobs.count { it.state == JobState.WAITING_NETWORK }
        val activeNow = running + queuedNow + waitingNet > 0 || engineBusy
        if (activeNow) {
            goForeground()
            if (isForeground) notifier.updateSummary(running, queuedNow + queuedLater, waitingNet)
        } else {
            if (isForeground) {
                stopForegroundCompat()
                stopSelf()
            }
        }
        if (queuedLater > 0) scheduler.nextWakeAt()?.let { scheduleWake(it) }
    }

    private fun scheduleWake(at: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            this, 7, Intent(this, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (t: Throwable) {
            ErrorLog.write(this, PROCESS, "alarm", t.message ?: "", t)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "downloader:queue").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(6 * 3600_000L)
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "downloader:wifi").apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.acquire()
        } catch (t: Throwable) {
            ErrorLog.write(this, PROCESS, "locks", t.message ?: "", t)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
    }

    // ---------------- رویدادهای زمان‌بند ----------------

    private val listener = object : SchedulerListener {
        override fun onJobsChanged() {
            mainHandler.post { postSnapshot() }
        }

        override fun onJobFinished(job: Job) {
            mainHandler.post {
                notifier.job(job, force = true)
                if (job.state == JobState.FAILED) {
                    ErrorLog.write(this@DownloadService, PROCESS, "job#${job.id}", "${job.errorCode}: ${job.errorDetail}")
                }
            }
        }

        override fun onJobDiscarded(job: Job, removed: Boolean) {
            mainHandler.post { notifier.cancelJob(job.id) }
            // فایل‌های موقت کار لغوشده پاک می‌شوند
            jobExecutor.execute { YtDlpRunner.jobDir(this@DownloadService, job.id).deleteRecursively() }
        }

        override fun onRunningCountChanged(running: Int) {
            mainHandler.post {
                if (running > 0) acquireLocks() else releaseLocks()
                manageForegroundState()
            }
        }
    }

    /** ارسال فهرست کارها به رابط کاربری و به‌روزرسانی اعلان‌ها؛ چند تغییر پشت‌سرهم در یک ارسال جمع می‌شوند. */
    private fun postSnapshot() {
        if (snapshotPosted) return
        snapshotPosted = true
        mainHandler.postDelayed({
            snapshotPosted = false
            val jobs = scheduler.snapshot()
            for (j in jobs) {
                if (j.state == JobState.RUNNING || j.state == JobState.PAUSED) notifier.job(j)
                else if (!j.state.isTerminal) notifier.cancelJob(j.id)
            }
            if (clients.isEmpty()) return@postDelayed
            val visible = jobs.sortedByDescending { it.createdAt }.take(300)
            broadcast(Ipc.MSG_SNAPSHOT, Ipc.jobsToJson(visible), 0)
        }, 350)
    }

    private fun broadcast(what: Int, json: String, req: Int) {
        val dead = ArrayList<Messenger>()
        for (c in clients) {
            try {
                c.send(Message.obtain(null, what).apply {
                    data = Bundle().apply {
                        putString(Ipc.KEY_JSON, json)
                        putInt(Ipc.KEY_REQ, req)
                    }
                })
            } catch (_: RemoteException) {
                dead.add(c)
            }
        }
        clients.removeAll(dead)
    }

    private fun reply(to: Messenger?, req: Int, json: JSONObject) {
        if (to == null) return
        mainHandler.post {
            try {
                to.send(Message.obtain(null, Ipc.MSG_REPLY).apply {
                    data = Bundle().apply {
                        putInt(Ipc.KEY_REQ, req)
                        putString(Ipc.KEY_JSON, json.toString())
                    }
                })
            } catch (_: RemoteException) {
            }
        }
    }

    // ---------------- فرمان‌ها ----------------

    fun handleJobAction(action: String, id: Long) {
        when (action) {
            "pause" -> scheduler.pause(id)
            "resume" -> scheduler.resume(id)
            "cancel" -> scheduler.cancel(id)
            "retry" -> scheduler.retry(id)
            "remove" -> scheduler.remove(id)
            "pauseAll" -> scheduler.pauseAll()
            "resumeAll" -> scheduler.resumeAll()
        }
        mainHandler.post { manageForegroundState() }
    }

    private fun applySettings(json: String) {
        val s = Ipc.settingsFromJson(json)
        settings = s
        repo.putKv("settings", Ipc.settingsToJson(s))
        scheduler.updateSettings(s)
    }

    private fun maybeAutoUpdateEngine() {
        if (!settings.autoEngineCheck) return
        if (!engine.isAutoCheckDue(System.currentTimeMillis())) return
        if (scheduler.runningIds().isNotEmpty()) return
        runEngineUpdate(null, 0, auto = true)
    }

    private fun runEngineUpdate(replyTo: Messenger?, req: Int, auto: Boolean) {
        val runningDownloads = scheduler.snapshot().count { it.state == JobState.RUNNING && it.stage == JobStage.DOWNLOAD }
        if (runningDownloads > 0 || engineBusy) {
            reply(replyTo, req, JSONObject().put("status", "busy"))
            return
        }
        engineBusy = true
        scheduler.holdNewStarts = true
        engineExecutor.execute {
            val r = try {
                engine.update()
            } catch (t: Throwable) {
                JSONObject().put("status", "failed").put("detail", t.message ?: "")
            }
            if (auto) ErrorLog.write(this, PROCESS, "engine-auto-check", r.toString())
            mainHandler.post {
                engineBusy = false
                scheduler.holdNewStarts = false
                manageForegroundState()
            }
            reply(replyTo, req, r)
        }
    }

    private fun jobFromSpec(o: JSONObject): Job = Job(
        url = o.getString("url"),
        title = o.optString("title", ""),
        thumbnail = o.optString("thumbnail", ""),
        formatSelector = o.getString("formatSelector"),
        formatLabel = o.optString("formatLabel", ""),
        mergeFormat = if (o.optString("mergeFormat", settings.mergeFormat) == "mkv") "mkv" else "mp4/mkv",
        embedSubs = o.optBoolean("embedSubs", settings.embedSubs),
        expectedBytes = o.optLong("expectedBytes", 0),
    )

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val req = msg.data?.getInt(Ipc.KEY_REQ) ?: 0
            val json = msg.data?.getString(Ipc.KEY_JSON)
            val replyTo = msg.replyTo
            try {
                when (msg.what) {
                    Ipc.MSG_REGISTER -> {
                        if (replyTo != null && clients.none { it.binder == replyTo.binder }) clients.add(replyTo)
                        postSnapshot()
                    }
                    Ipc.MSG_UNREGISTER -> clients.removeAll { it.binder == replyTo?.binder }
                    Ipc.MSG_REQUEST_SNAPSHOT -> postSnapshot()
                    Ipc.MSG_SETTINGS -> if (json != null) applySettings(json)
                    Ipc.MSG_ANALYZE -> {
                        val url = JSONObject(json ?: "{}").optString("url")
                        analyzeExecutor.execute { reply(replyTo, req, engine.analyze(url, req)) }
                    }
                    Ipc.MSG_ENQUEUE -> {
                        goForeground()
                        val o = JSONObject(json ?: "{}")
                        jobExecutor.execute {
                            val r = try {
                                val id = scheduler.enqueue(jobFromSpec(o))
                                JSONObject().put("ok", true).put("id", id)
                            } catch (t: Throwable) {
                                ErrorLog.write(this@DownloadService, PROCESS, "enqueue", t.message ?: "", t)
                                JSONObject().put("ok", false).put("detail", t.message ?: "")
                            }
                            reply(replyTo, req, r)
                            mainHandler.post { manageForegroundState() }
                        }
                    }
                    Ipc.MSG_JOB_ACTION -> {
                        val o = JSONObject(json ?: "{}")
                        val action = o.optString("action")
                        val id = o.optLong("id")
                        jobExecutor.execute {
                            handleJobAction(action, id)
                            reply(replyTo, req, JSONObject().put("ok", true))
                        }
                    }
                    Ipc.MSG_ENGINE_INFO -> engineExecutor.execute {
                        reply(replyTo, req, engine.info().put("busy", engineBusy))
                    }
                    Ipc.MSG_ENGINE_UPDATE -> runEngineUpdate(replyTo, req, auto = false)
                    Ipc.MSG_ENGINE_ROLLBACK -> {
                        val runningDownloads = scheduler.snapshot().count { it.state == JobState.RUNNING && it.stage == JobStage.DOWNLOAD }
                        if (runningDownloads > 0 || engineBusy) {
                            reply(replyTo, req, JSONObject().put("status", "busy"))
                        } else {
                            engineBusy = true
                            scheduler.holdNewStarts = true
                            engineExecutor.execute {
                                val r = engine.rollback()
                                mainHandler.post {
                                    engineBusy = false
                                    scheduler.holdNewStarts = false
                                }
                                reply(replyTo, req, r)
                            }
                        }
                    }
                    Ipc.MSG_LIST_EXTRACTORS -> analyzeExecutor.execute { reply(replyTo, req, engine.listExtractors()) }
                    else -> super.handleMessage(msg)
                }
            } catch (t: Throwable) {
                ErrorLog.write(this@DownloadService, PROCESS, "ipc", "what=${msg.what}: ${t.message}", t)
                reply(replyTo, req, JSONObject().put("ok", false).put("status", "failed").put("detail", t.message ?: ""))
            }
        }
    })
}
