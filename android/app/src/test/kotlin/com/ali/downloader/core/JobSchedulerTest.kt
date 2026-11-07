package com.ali.downloader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JobSchedulerTest {
    private lateinit var repo: InMemoryJobRepository
    private lateinit var env: FakeEnv
    private lateinit var exec: ManualExecutor
    private lateinit var download: ScriptedRunner
    private lateinit var publish: ScriptedRunner
    private lateinit var listener: RecordingListener
    private lateinit var scheduler: JobScheduler

    private fun build(settings: QueueSettings = QueueSettings(maxConcurrentDownloads = 3)) {
        scheduler = JobScheduler(
            repo, env, mapOf(JobStage.DOWNLOAD to download, JobStage.PUBLISH to publish), exec, listener, settings,
        )
    }

    @Before
    fun setUp() {
        repo = InMemoryJobRepository()
        env = FakeEnv()
        exec = ManualExecutor()
        download = ScriptedRunner()
        publish = ScriptedRunner()
        listener = RecordingListener()
        build()
    }

    private fun states() = repo.all().map { it.state }

    @Test
    fun respectsUserCapForParallelDownloads() {
        repeat(10) { scheduler.enqueue(newJob("https://e.com/$it")) }
        assertEquals(3, states().count { it == JobState.RUNNING })
        assertEquals(7, states().count { it == JobState.QUEUED })
        assertEquals(3, exec.tasks.size)
    }

    @Test
    fun allJobsCompleteThroughBothPools() {
        repeat(10) { scheduler.enqueue(newJob("https://e.com/$it")) }
        exec.runAll()
        assertTrue(states().all { it == JobState.COMPLETED })
        assertEquals(10, download.runs.size)
        assertEquals(10, publish.runs.size)
        assertEquals(10, listener.finished.size)
    }

    @Test
    fun publishPoolIsSeparateFromDownloadPool() {
        repeat(4) { scheduler.enqueue(newJob("https://e.com/$it")) }
        // اولین دانلود تمام می‌شود و به مرحلهٔ انتشار می‌رود، در حالی که سه دانلود دیگر هنوز اجرا می‌شوند
        exec.runNext()
        val running = repo.all().filter { it.state == JobState.RUNNING }
        assertEquals(3, running.count { it.stage == JobStage.DOWNLOAD })
        assertEquals(1, running.count { it.stage == JobStage.PUBLISH })
    }

    @Test
    fun failureOfOneJobDoesNotAffectOthers() {
        val bad = scheduler.enqueue(newJob("https://e.com/bad"))
        val good = scheduler.enqueue(newJob("https://e.com/good"))
        download.enqueueResult(bad, RunResult.Failure(ErrorClassifier.DRM, false, "DRM protected"))
        exec.runAll()
        assertEquals(JobState.FAILED, repo.get(bad)!!.state)
        assertEquals(ErrorClassifier.DRM, repo.get(bad)!!.errorCode)
        assertEquals(JobState.COMPLETED, repo.get(good)!!.state)
    }

    @Test
    fun unexpectedExceptionIsIsolated() {
        val boom = scheduler.enqueue(newJob("https://e.com/boom"))
        val ok = scheduler.enqueue(newJob("https://e.com/ok"))
        download.onRun = { job, _ -> if (job.id == boom) throw IllegalStateException("crash in runner") }
        exec.runAll()
        val b = repo.get(boom)!!
        assertEquals(JobState.QUEUED, b.state) // دوباره در صف با تأخیر
        assertEquals(1, b.attempts)
        assertEquals(JobState.COMPLETED, repo.get(ok)!!.state)
    }

    @Test
    fun retriesWithExponentialBackoffUpToFiveTimes() {
        val id = scheduler.enqueue(newJob())
        download.defaultResult = { RunResult.Failure(ErrorClassifier.FORBIDDEN, true, "HTTP Error 403") }
        val delays = ArrayList<Long>()
        for (i in 1..5) {
            exec.runAll()
            val j = repo.get(id)!!
            if (i < 5) {
                assertEquals(JobState.QUEUED, j.state)
                assertEquals(WaitReason.RETRY_BACKOFF, j.waitReason)
                delays.add(j.notBefore - env.time)
                // قبل از رسیدن زمان، کار شروع نمی‌شود
                scheduler.tick()
                assertEquals(0, exec.tasks.size)
                env.time = j.notBefore
                scheduler.tick()
            } else {
                assertEquals(JobState.FAILED, j.state)
                assertEquals(5, j.attempts)
            }
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), delays)
        assertEquals(5, download.runs.size)
    }

    @Test
    fun nonRetryableErrorFailsImmediately() {
        val id = scheduler.enqueue(newJob())
        download.enqueueResult(id, RunResult.Failure(ErrorClassifier.UNSUPPORTED_URL, false, "Unsupported URL"))
        exec.runAll()
        assertEquals(JobState.FAILED, repo.get(id)!!.state)
        assertEquals(1, download.runs.size)
    }

    @Test
    fun recoversRunningJobsAfterWorkerCrash() {
        // وضعیت پایگاه داده بعد از کرش پروسهٔ worker: دو کار RUNNING مانده‌اند
        val a = repo.insert(newJob("https://e.com/a").copy(createdAt = 1))
        val b = repo.insert(newJob("https://e.com/b").copy(createdAt = 2))
        repo.forceState(a, JobState.RUNNING)
        repo.forceState(b, JobState.RUNNING)
        build()
        val recovered = scheduler.start()
        assertEquals(2, recovered)
        assertEquals(2, exec.tasks.size)
        exec.runAll()
        assertEquals(JobState.COMPLETED, repo.get(a)!!.state)
        assertEquals(JobState.COMPLETED, repo.get(b)!!.state)
    }

    @Test
    fun crashRecoveryKeepsStageSoPublishResumesWithoutRedownload() {
        val id = repo.insert(newJob().copy(stage = JobStage.PUBLISH, privatePath = "/data/x.mp4", createdAt = 1))
        repo.forceState(id, JobState.RUNNING)
        build()
        scheduler.start()
        exec.runAll()
        assertEquals(0, download.runs.size)
        assertEquals(listOf(id), publish.runs)
        assertEquals(JobState.COMPLETED, repo.get(id)!!.state)
    }

    @Test
    fun waitsForNetworkWithoutConsumingAttemptsAndResumes() {
        val id = scheduler.enqueue(newJob())
        // اینترنت وسط دانلود قطع می‌شود
        env.network = false
        download.enqueueResult(id, RunResult.Failure(ErrorClassifier.NETWORK, true, "Unable to download webpage"))
        exec.runNext()
        val waiting = repo.get(id)!!
        assertEquals(JobState.WAITING_NETWORK, waiting.state)
        assertEquals(0, waiting.attempts)
        scheduler.tick()
        assertEquals(0, exec.tasks.size)
        // اینترنت برمی‌گردد
        env.network = true
        scheduler.tick()
        assertEquals(JobState.RUNNING, repo.get(id)!!.state)
        exec.runAll()
        assertEquals(JobState.COMPLETED, repo.get(id)!!.state)
        assertEquals(2, download.runs.size)
    }

    @Test
    fun queuedJobsWaitWhileOffline() {
        env.network = false
        val id = scheduler.enqueue(newJob())
        assertEquals(JobState.WAITING_NETWORK, repo.get(id)!!.state)
        assertEquals(WaitReason.NO_NETWORK, repo.get(id)!!.waitReason)
        env.network = true
        scheduler.tick()
        assertEquals(JobState.RUNNING, repo.get(id)!!.state)
    }

    @Test
    fun wifiOnlyBlocksMeteredAndStopsRunningDownloads() {
        build(QueueSettings(maxConcurrentDownloads = 3, wifiOnly = true))
        val id = scheduler.enqueue(newJob())
        assertEquals(JobState.RUNNING, repo.get(id)!!.state)
        env.unmetered = false
        scheduler.tick()
        assertTrue(download.stopped.contains(id))
        exec.runAll()
        assertEquals(JobState.WAITING_NETWORK, repo.get(id)!!.state)
        assertEquals(WaitReason.WIFI_REQUIRED, repo.get(id)!!.waitReason)
        env.unmetered = true
        scheduler.tick()
        exec.runAll()
        assertEquals(JobState.COMPLETED, repo.get(id)!!.state)
    }

    @Test
    fun watchdogRestartsStalledJob() {
        val id = scheduler.enqueue(newJob())
        env.time += JobScheduler.STALL_DOWNLOAD_MS + 1
        scheduler.tick()
        assertTrue(download.stopped.contains(id))
        exec.runAll()
        val j = repo.get(id)!!
        assertEquals(JobState.QUEUED, j.state)
        assertEquals(ErrorClassifier.STALLED, j.errorCode)
        assertEquals(1, j.attempts)
    }

    @Test
    fun progressKeepsWatchdogQuiet() {
        val id = scheduler.enqueue(newJob())
        download.onRun = { _, control ->
            env.time += JobScheduler.STALL_DOWNLOAD_MS - 1000
            control.update(true) { it.copy(downloadedBytes = it.downloadedBytes + 100) }
            env.time += JobScheduler.STALL_DOWNLOAD_MS - 1000
            scheduler.tick()
        }
        exec.runAll()
        assertFalse(download.stopped.contains(id))
        assertEquals(JobState.COMPLETED, repo.get(id)!!.state)
    }

    @Test
    fun pauseResumeAndCancel() {
        val a = scheduler.enqueue(newJob("https://e.com/a"))
        scheduler.pause(a)
        exec.runAll()
        assertEquals(JobState.PAUSED, repo.get(a)!!.state)
        scheduler.resume(a)
        assertEquals(JobState.RUNNING, repo.get(a)!!.state)
        scheduler.cancel(a)
        exec.runAll()
        assertEquals(JobState.CANCELED, repo.get(a)!!.state)
        assertEquals(1, listener.discarded.size)
        scheduler.retry(a)
        exec.runAll()
        assertEquals(JobState.COMPLETED, repo.get(a)!!.state)
    }

    @Test
    fun pausingQueuedJobLeavesSlotForOthers() {
        build(QueueSettings(maxConcurrentDownloads = 1))
        val a = scheduler.enqueue(newJob("https://e.com/a"))
        val b = scheduler.enqueue(newJob("https://e.com/b"))
        scheduler.pause(b)
        exec.runAll()
        assertEquals(JobState.COMPLETED, repo.get(a)!!.state)
        assertEquals(JobState.PAUSED, repo.get(b)!!.state)
    }

    @Test
    fun removeDeletesJob() {
        val a = scheduler.enqueue(newJob())
        scheduler.remove(a)
        exec.runAll()
        assertEquals(null, repo.get(a))
        assertTrue(listener.discarded.single().second)
    }

    @Test
    fun aria2cFailureFallsBackToNativeDownloader() {
        val id = scheduler.enqueue(newJob())
        download.enqueueResult(id, RunResult.Failure(ErrorClassifier.ARIA2C, true, "aria2c exited with code 1"))
        exec.runAll()
        val j = repo.get(id)!!
        assertFalse(j.useAria2c)
        env.time = j.notBefore
        scheduler.tick()
        exec.runAll()
        assertEquals(JobState.COMPLETED, repo.get(id)!!.state)
    }

    @Test
    fun scheduledJobsWaitUntilStartTime() {
        build(QueueSettings(scheduleEnabled = true, scheduleMinuteOfDay = 0))
        val id = scheduler.enqueue(newJob())
        val j = repo.get(id)!!
        assertEquals(JobState.QUEUED, j.state)
        assertEquals(WaitReason.SCHEDULED, j.waitReason)
        assertTrue(j.notBefore > env.time)
        assertEquals(j.notBefore, scheduler.nextWakeAt())
        env.time = j.notBefore
        scheduler.tick()
        assertEquals(JobState.RUNNING, repo.get(id)!!.state)
    }

    @Test
    fun holdNewStartsDuringEngineUpdate() {
        scheduler.holdNewStarts = true
        val id = scheduler.enqueue(newJob())
        assertEquals(JobState.QUEUED, repo.get(id)!!.state)
        scheduler.holdNewStarts = false
        assertEquals(JobState.RUNNING, repo.get(id)!!.state)
    }

    @Test
    fun shutdownRequeuesRunningJobs() {
        val id = scheduler.enqueue(newJob())
        scheduler.shutdown()
        exec.runNext()
        assertEquals(JobState.QUEUED, repo.get(id)!!.state)
    }

    @Test
    fun lowMemoryGateDelaysNewHeavyJobs() {
        env.lowMem = true
        val id = scheduler.enqueue(newJob())
        assertEquals(JobState.QUEUED, repo.get(id)!!.state)
        assertEquals(0, exec.tasks.size)
        env.lowMem = false
        scheduler.tick()
        assertEquals(JobState.RUNNING, repo.get(id)!!.state)
    }

    @Test
    fun stressTenDownloadsOnTwoGigDevice() {
        // گوشی ۲ گیگ با ۷۰۰ مگ رم آزاد: هم‌زمانی محدود می‌شود ولی همهٔ کارها بدون خطا تمام می‌شوند
        env.totalRam = 2L * 1024 * 1024 * 1024
        env.freeRam = 700L * 1024 * 1024
        env.cores = 4
        build(QueueSettings(maxConcurrentDownloads = 10))
        repeat(10) { scheduler.enqueue(newJob("https://e.com/$it")) }
        val running = states().count { it == JobState.RUNNING }
        assertTrue("running=$running", running in 1..5)
        exec.runAll()
        assertTrue(states().all { it == JobState.COMPLETED })
    }
}
