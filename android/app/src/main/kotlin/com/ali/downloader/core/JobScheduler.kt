package com.ali.downloader.core

import java.util.concurrent.Executor

/** اجراکنندهٔ یک مرحله از کار. اجرای آن مسدودکننده است و روی Thread جداگانهٔ Pool صدا زده می‌شود. */
interface JobRunner {
    fun run(job: Job, control: RunControl): RunResult

    /** توقف اجرای در حال انجام (کشتن پروسهٔ yt-dlp یا قطع کپی). باید چندبار صدا زدنش بی‌خطر باشد. */
    fun stop(jobId: Long)
}

interface RunControl {
    /** به‌روزرسانی وضعیت لحظه‌ای کار. اگر [activity] درست باشد Watchdog آن را پیشرفت حساب می‌کند. */
    fun update(activity: Boolean, mutate: (Job) -> Job)
    val stopRequested: Boolean
}

sealed class RunResult {
    class Success(val patch: (Job) -> Job) : RunResult()
    data class Failure(val code: String, val retryable: Boolean, val detail: String) : RunResult()
}

enum class StopReason { PAUSE, CANCEL, REMOVE, STALL, NETWORK, SHUTDOWN }

interface SchedulerListener {
    /** فهرست کارها یا وضعیت یکی از آن‌ها تغییر کرد. */
    fun onJobsChanged()
    /** کار به وضعیت نهایی (موفق، ناموفق یا لغوشده) رسید. */
    fun onJobFinished(job: Job)
    /** کار لغو یا حذف شد و فایل‌های موقتش باید پاک شوند. */
    fun onJobDiscarded(job: Job, removed: Boolean)
    /** تعداد کارهای در حال اجرا تغییر کرد (برای WakeLock و اعلان خلاصه). */
    fun onRunningCountChanged(running: Int)
}

/**
 * زمان‌بند صف: برای هر مرحله یک Worker Pool جدا، هم‌زمانی تطبیقی، تلاش دوباره با فاصلهٔ افزایشی،
 * Watchdog برای کارهای گیرکرده، انتظار برای برگشت اینترنت و بازیابی بعد از کرش.
 * همهٔ تصمیم‌ها زیر یک قفل گرفته می‌شوند؛ اجرای هر کار در try/catch جدا است تا خطای یک کار به بقیه نرسد.
 */
class JobScheduler(
    private val repo: JobRepository,
    private val env: DeviceEnvironment,
    private val runners: Map<JobStage, JobRunner>,
    private val executor: Executor,
    private val listener: SchedulerListener,
    initialSettings: QueueSettings = QueueSettings(),
) {
    companion object {
        const val STALL_DOWNLOAD_MS = 3 * 60_000L
        const val STALL_POSTPROCESS_MS = 30 * 60_000L
        const val STALL_PUBLISH_MS = 10 * 60_000L
        private const val PERSIST_INTERVAL_MS = 3_000L
    }

    private class Handle(
        @Volatile var job: Job,
        @Volatile var lastActivity: Long,
        @Volatile var stopReason: StopReason? = null,
        @Volatile var lastPersist: Long = 0,
    )

    private val lock = Any()
    private val running = LinkedHashMap<Long, Handle>()
    /** زمان شروع دانلودهای اخیر (برای دروازهٔ رم). */
    private val recentDownloadStarts = ArrayDeque<Long>()
    private val recentWindowMs = 10_000L

    @Volatile
    var settings: QueueSettings = initialSettings
        private set

    /** در زمان به‌روزرسانی موتور، کار تازه شروع نمی‌شود. */
    @Volatile
    var holdNewStarts: Boolean = false
        set(value) {
            field = value
            if (!value) tick()
        }

    /** شروع: کارهای نیمه‌تمام قبل از کرش یا ریستارت دوباره در صف قرار می‌گیرند. */
    fun start(): Int {
        val recovered = synchronized(lock) { repo.recoverInterrupted(env.now()) }
        tick()
        return recovered
    }

    fun updateSettings(s: QueueSettings) {
        settings = s
        tick()
    }

    fun enqueue(job: Job): Long {
        val now = env.now()
        val s = settings
        val scheduled = s.scheduleEnabled
        val toInsert = job.copy(
            id = 0,
            stage = JobStage.DOWNLOAD,
            state = JobState.QUEUED,
            phase = JobPhase.PREPARING,
            waitReason = if (scheduled) WaitReason.SCHEDULED else WaitReason.NONE,
            notBefore = if (scheduled) ScheduleTime.nextOccurrence(now, s.scheduleMinuteOfDay) else 0,
            createdAt = now,
            updatedAt = now,
            attempts = 0,
        )
        val id = synchronized(lock) { repo.insert(toInsert) }
        listener.onJobsChanged()
        tick()
        return id
    }

    fun runningCount(): Int = synchronized(lock) { running.size }

    fun runningIds(): Set<Long> = synchronized(lock) { running.keys.toSet() }

    /** فهرست کارها با تازه‌ترین وضعیت لحظه‌ای کارهای در حال اجرا. */
    fun snapshot(): List<Job> = synchronized(lock) {
        repo.all().map { j -> running[j.id]?.job ?: j }
    }

    /** نزدیک‌ترین زمانی که کاری منتظر آن است (برای تنظیم آلارم). */
    fun nextWakeAt(): Long? = synchronized(lock) {
        val now = env.now()
        repo.all().filter { it.state == JobState.QUEUED && it.notBefore > now }.minOfOrNull { it.notBefore }
    }

    /** آیا کاری برای انجام (الان یا بعداً) باقی مانده است؟ */
    fun hasPendingWork(): Boolean = synchronized(lock) {
        running.isNotEmpty() || repo.all().any {
            it.state == JobState.QUEUED || it.state == JobState.WAITING_NETWORK
        }
    }

    fun hasRunnableNow(): Boolean = synchronized(lock) {
        val now = env.now()
        running.isNotEmpty() || repo.all().any {
            (it.state == JobState.QUEUED && it.notBefore <= now) || it.state == JobState.WAITING_NETWORK
        }
    }

    // ---------------- فرمان‌های کاربر ----------------

    fun pause(id: Long) {
        synchronized(lock) {
            val h = running[id]
            if (h != null) {
                requestStop(id, h, StopReason.PAUSE)
                return
            }
            repo.transition(id, setOf(JobState.QUEUED, JobState.WAITING_NETWORK)) {
                it.copy(state = JobState.PAUSED, speedBps = 0, etaSec = -1, updatedAt = env.now())
            }
        }
        listener.onJobsChanged()
    }

    fun resume(id: Long) {
        synchronized(lock) {
            repo.transition(id, setOf(JobState.PAUSED)) {
                it.copy(state = JobState.QUEUED, waitReason = WaitReason.NONE, notBefore = 0, updatedAt = env.now())
            }
        }
        listener.onJobsChanged()
        tick()
    }

    /** تلاش دوباره برای کار ناموفق یا لغوشده، از همان مرحله‌ای که مانده بود. */
    fun retry(id: Long) {
        synchronized(lock) {
            repo.transition(id, setOf(JobState.FAILED, JobState.CANCELED)) {
                it.copy(
                    state = JobState.QUEUED, attempts = 0, errorCode = "", errorDetail = "",
                    waitReason = WaitReason.NONE, notBefore = 0, finishedAt = 0, updatedAt = env.now(),
                    phase = if (it.stage == JobStage.DOWNLOAD) JobPhase.PREPARING else JobPhase.PUBLISHING,
                )
            }
        }
        listener.onJobsChanged()
        tick()
    }

    fun cancel(id: Long) {
        var discarded: Job? = null
        synchronized(lock) {
            val h = running[id]
            if (h != null) {
                requestStop(id, h, StopReason.CANCEL)
                return
            }
            discarded = repo.transition(
                id, setOf(JobState.QUEUED, JobState.WAITING_NETWORK, JobState.PAUSED, JobState.FAILED)
            ) {
                it.copy(state = JobState.CANCELED, speedBps = 0, etaSec = -1, finishedAt = env.now(), updatedAt = env.now())
            }
        }
        discarded?.let { listener.onJobDiscarded(it, removed = false) }
        listener.onJobsChanged()
    }

    /** حذف کار از فهرست (فایل منتشرشده پاک نمی‌شود، فقط فایل‌های موقت). */
    fun remove(id: Long) {
        var removed: Job? = null
        synchronized(lock) {
            val h = running[id]
            if (h != null) {
                requestStop(id, h, StopReason.REMOVE)
                return
            }
            removed = repo.get(id)
            if (removed != null) repo.delete(id)
        }
        removed?.let { listener.onJobDiscarded(it, removed = true) }
        listener.onJobsChanged()
    }

    fun pauseAll() {
        synchronized(lock) {
            for ((id, h) in running) if (h.job.stage == JobStage.DOWNLOAD) requestStop(id, h, StopReason.PAUSE)
            for (j in repo.all()) {
                if (j.state == JobState.QUEUED || j.state == JobState.WAITING_NETWORK) {
                    repo.transition(j.id, setOf(JobState.QUEUED, JobState.WAITING_NETWORK)) {
                        it.copy(state = JobState.PAUSED, updatedAt = env.now())
                    }
                }
            }
        }
        listener.onJobsChanged()
    }

    fun resumeAll() {
        synchronized(lock) {
            for (j in repo.all()) if (j.state == JobState.PAUSED) {
                repo.transition(j.id, setOf(JobState.PAUSED)) {
                    it.copy(state = JobState.QUEUED, waitReason = WaitReason.NONE, notBefore = 0, updatedAt = env.now())
                }
            }
        }
        listener.onJobsChanged()
        tick()
    }

    /** توقف همهٔ کارها (مثلاً پایان مهلت سرویس پیش‌زمینه در اندروید ۱۵)؛ کارها بعداً از همان نقطه ادامه می‌یابند. */
    fun shutdown() {
        holdNewStarts = true
        synchronized(lock) {
            for ((id, h) in running) requestStop(id, h, StopReason.SHUTDOWN)
        }
    }

    private fun requestStop(id: Long, h: Handle, reason: StopReason) {
        // لغو و حذف بر توقف موقت اولویت دارند
        val cur = h.stopReason
        if (cur == null || reason == StopReason.CANCEL || reason == StopReason.REMOVE) h.stopReason = reason
        runners[h.job.stage]?.let { r ->
            try {
                r.stop(id)
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------- حلقهٔ زمان‌بندی ----------------

    private fun networkOk(): Boolean =
        env.isNetworkAvailable() && (!settings.wifiOnly || env.isUnmeteredNetwork())

    /** یک دور تصمیم‌گیری: Watchdog، شبکه، و شروع کارهای تازه تا سقف هر Pool. */
    fun tick() {
        var changed = false
        var runningCountChanged = false
        synchronized(lock) {
            val now = env.now()
            val before = running.size

            // Watchdog: کاری که مدتی هیچ پیشرفتی نداشته متوقف و دوباره شروع می‌شود.
            for ((id, h) in running) {
                if (h.stopReason != null) {
                    // توقف قبلاً درخواست شده؛ اگر پروسه هنوز زنده است دوباره تلاش می‌کنیم.
                    runners[h.job.stage]?.let { try { it.stop(id) } catch (_: Throwable) {} }
                    continue
                }
                val timeout = when (h.job.phase) {
                    JobPhase.MERGING, JobPhase.EMBEDDING -> STALL_POSTPROCESS_MS
                    JobPhase.PUBLISHING -> STALL_PUBLISH_MS
                    else -> STALL_DOWNLOAD_MS
                }
                if (now - h.lastActivity > timeout) requestStop(id, h, StopReason.STALL)
            }

            // شبکه: بدون اینترنت (یا بدون وای‌فای در حالت «فقط وای‌فای») دانلودها منتظر می‌مانند.
            val netOk = networkOk()
            val netReason = if (!env.isNetworkAvailable()) WaitReason.NO_NETWORK else WaitReason.WIFI_REQUIRED
            if (!netOk && settings.wifiOnly && env.isNetworkAvailable()) {
                for ((id, h) in running) if (h.job.stage == JobStage.DOWNLOAD) requestStop(id, h, StopReason.NETWORK)
            }
            val all = repo.all()
            for (j in all) {
                if (j.stage != JobStage.DOWNLOAD) continue
                if (!netOk && j.state == JobState.QUEUED && j.notBefore <= now) {
                    repo.transition(j.id, setOf(JobState.QUEUED)) {
                        it.copy(state = JobState.WAITING_NETWORK, waitReason = netReason, updatedAt = now)
                    }
                    changed = true
                } else if (!netOk && j.state == JobState.WAITING_NETWORK && j.waitReason != netReason) {
                    repo.transition(j.id, setOf(JobState.WAITING_NETWORK)) { it.copy(waitReason = netReason) }
                    changed = true
                } else if (netOk && j.state == JobState.WAITING_NETWORK) {
                    repo.transition(j.id, setOf(JobState.WAITING_NETWORK)) {
                        it.copy(state = JobState.QUEUED, waitReason = WaitReason.NONE, notBefore = 0, updatedAt = now)
                    }
                    changed = true
                }
            }

            if (!holdNewStarts) {
                val fresh = repo.all()
                for (stage in JobStage.values()) {
                    if (stage == JobStage.DOWNLOAD && !netOk) continue
                    while (recentDownloadStarts.isNotEmpty() && now - recentDownloadStarts.first() > recentWindowMs) {
                        recentDownloadStarts.removeFirst()
                    }
                    val runningInStage = running.values.count { it.job.stage == stage }
                    val recent = if (stage == JobStage.DOWNLOAD) recentDownloadStarts.size else 0
                    val limit = ConcurrencyPolicy.limit(stage, env, settings, runningInStage, recent)
                    var free = limit - runningInStage
                    val candidates = fresh
                        .filter { it.stage == stage && it.state == JobState.QUEUED && it.notBefore <= now }
                        .sortedWith(compareBy({ it.createdAt }, { it.id }))
                    for (c in candidates) {
                        if (free <= 0) {
                            // سقف پر است؛ اگر دلیلش کمبود منابع است، برای کاربر نمایش داده شود.
                            val lowRes = stage == JobStage.DOWNLOAD && limit < settings.maxConcurrentDownloads
                            val wanted = if (lowRes) WaitReason.LOW_RESOURCES else WaitReason.NONE
                            if (c.waitReason != wanted && (c.waitReason == WaitReason.NONE || c.waitReason == WaitReason.LOW_RESOURCES ||
                                    c.waitReason == WaitReason.SCHEDULED || c.waitReason == WaitReason.RETRY_BACKOFF)
                            ) {
                                repo.transition(c.id, setOf(JobState.QUEUED)) { it.copy(waitReason = wanted) }
                                changed = true
                            }
                            continue
                        }
                        if (startLocked(c, now)) {
                            free--
                            changed = true
                            if (stage == JobStage.DOWNLOAD) recentDownloadStarts.addLast(now)
                        }
                    }
                }
            }
            runningCountChanged = before != running.size
        }
        if (changed) listener.onJobsChanged()
        if (runningCountChanged) listener.onRunningCountChanged(runningCount())
    }

    private fun startLocked(candidate: Job, now: Long): Boolean {
        val runner = runners[candidate.stage] ?: return false
        val started = repo.transition(candidate.id, setOf(JobState.QUEUED)) {
            it.copy(
                state = JobState.RUNNING,
                waitReason = WaitReason.NONE,
                phase = if (it.stage == JobStage.DOWNLOAD) JobPhase.PREPARING else JobPhase.PUBLISHING,
                speedBps = 0,
                etaSec = -1,
                updatedAt = now,
            )
        } ?: return false
        val h = Handle(started, now)
        running[started.id] = h
        val control = object : RunControl {
            override fun update(activity: Boolean, mutate: (Job) -> Job) = onUpdate(started.id, h, activity, mutate)
            override val stopRequested: Boolean get() = h.stopReason != null
        }
        try {
            executor.execute {
                var result: RunResult
                try {
                    result = runner.run(h.job, control)
                } catch (t: Throwable) {
                    // هر کار جدا در try/catch است: خطای غیرمنتظره فقط همین کار را ناموفق می‌کند.
                    result = RunResult.Failure(ErrorClassifier.UNKNOWN, true, t.toString())
                }
                finish(started.id, result)
            }
        } catch (t: Throwable) {
            running.remove(started.id)
            repo.transition(started.id, setOf(JobState.RUNNING)) { it.copy(state = JobState.QUEUED) }
            return false
        }
        return true
    }

    private fun onUpdate(id: Long, h: Handle, activity: Boolean, mutate: (Job) -> Job) {
        synchronized(lock) {
            if (running[id] !== h) return
            val now = env.now()
            h.job = mutate(h.job).copy(updatedAt = now)
            if (activity) h.lastActivity = now
            if (now - h.lastPersist >= PERSIST_INTERVAL_MS) {
                h.lastPersist = now
                val snapshot = h.job
                repo.transition(id, setOf(JobState.RUNNING)) { snapshot }
            }
        }
        listener.onJobsChanged()
    }

    private fun finish(id: Long, result: RunResult) {
        var finished: Job? = null
        var discarded: Job? = null
        var removed = false
        synchronized(lock) {
            val h = running.remove(id) ?: return
            val now = env.now()
            val cur = h.job
            val reason = h.stopReason
            val next: Job? = when {
                reason == StopReason.REMOVE -> {
                    repo.delete(id)
                    discarded = cur
                    removed = true
                    null
                }
                reason == StopReason.CANCEL -> {
                    discarded = cur
                    cur.copy(state = JobState.CANCELED, speedBps = 0, etaSec = -1, finishedAt = now)
                }
                result is RunResult.Success -> {
                    val patched = result.patch(cur)
                    if (cur.stage == JobStage.DOWNLOAD) {
                        patched.copy(
                            stage = JobStage.PUBLISH, state = JobState.QUEUED, phase = JobPhase.PUBLISHING,
                            progress = -1.0, speedBps = 0, etaSec = -1, attempts = 0, errorCode = "", errorDetail = "",
                            waitReason = WaitReason.NONE, notBefore = 0,
                        )
                    } else {
                        patched.copy(
                            state = JobState.COMPLETED, phase = JobPhase.DONE, progress = 100.0,
                            speedBps = 0, etaSec = -1, finishedAt = now, errorCode = "", errorDetail = "",
                        ).also { finished = it }
                    }
                }
                reason == StopReason.PAUSE -> cur.copy(state = JobState.PAUSED, speedBps = 0, etaSec = -1)
                reason == StopReason.SHUTDOWN -> cur.copy(state = JobState.QUEUED, speedBps = 0, etaSec = -1)
                reason == StopReason.NETWORK -> cur.copy(
                    state = JobState.WAITING_NETWORK, waitReason = WaitReason.WIFI_REQUIRED, speedBps = 0, etaSec = -1,
                )
                reason == StopReason.STALL -> failureOf(cur, ErrorClassifier.STALLED, true, "", now)
                result is RunResult.Failure -> failureOf(cur, result.code, result.retryable, result.detail, now)
                else -> cur.copy(state = JobState.QUEUED)
            }
            if (next != null) {
                val saved = repo.transition(id, null) { next.copy(updatedAt = now) }
                if (saved != null && (saved.state == JobState.FAILED || saved.state == JobState.CANCELED)) finished = saved
            }
        }
        discarded?.let { listener.onJobDiscarded(it, removed) }
        finished?.let { listener.onJobFinished(it) }
        listener.onJobsChanged()
        listener.onRunningCountChanged(runningCount())
        tick()
    }

    private fun failureOf(cur: Job, code: String, retryable: Boolean, detail: String, now: Long): Job {
        val base = cur.copy(speedBps = 0, etaSec = -1, errorCode = code, errorDetail = detail)
        // قطع اینترنت تلاش حساب نمی‌شود؛ کار منتظر برگشت اینترنت می‌ماند.
        if (retryable && ErrorClassifier.isNetworkRelated(code) && !env.isNetworkAvailable() && cur.stage == JobStage.DOWNLOAD) {
            return base.copy(state = JobState.WAITING_NETWORK, waitReason = WaitReason.NO_NETWORK)
        }
        val attempts = cur.attempts + 1
        val flagged = base.copy(
            attempts = attempts,
            useAria2c = if (code == ErrorClassifier.ARIA2C) false else base.useAria2c,
            subsDisabled = if (code == ErrorClassifier.SUBTITLES) true else base.subsDisabled,
        )
        return if (retryable && RetryPolicy.shouldRetry(attempts)) {
            flagged.copy(
                state = JobState.QUEUED,
                waitReason = WaitReason.RETRY_BACKOFF,
                notBefore = now + RetryPolicy.delayMs(attempts),
            )
        } else {
            flagged.copy(state = JobState.FAILED, finishedAt = now)
        }
    }
}
