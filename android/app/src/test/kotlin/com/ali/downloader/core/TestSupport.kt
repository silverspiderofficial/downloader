package com.ali.downloader.core

import java.util.concurrent.Executor

/** مخزن حافظه‌ای با همان قرارداد تراکنشی مخزن SQLite. */
class InMemoryJobRepository : JobRepository {
    private val jobs = LinkedHashMap<Long, Job>()
    private var nextId = 1L

    @Synchronized override fun insert(job: Job): Long {
        val id = nextId++
        jobs[id] = job.copy(id = id)
        return id
    }

    @Synchronized override fun get(id: Long): Job? = jobs[id]
    @Synchronized override fun all(): List<Job> = jobs.values.sortedWith(compareBy({ it.createdAt }, { it.id }))
    @Synchronized override fun delete(id: Long) {
        jobs.remove(id)
    }

    @Synchronized override fun transition(id: Long, expected: Set<JobState>?, mutate: (Job) -> Job): Job? {
        val cur = jobs[id] ?: return null
        if (expected != null && cur.state !in expected) return null
        val next = mutate(cur).copy(id = id)
        jobs[id] = next
        return next
    }

    @Synchronized override fun recoverInterrupted(now: Long): Int {
        var n = 0
        for ((id, j) in jobs.entries.toList()) {
            if (j.state == JobState.RUNNING) {
                jobs[id] = j.copy(state = JobState.QUEUED, speedBps = 0, etaSec = -1, updatedAt = now)
                n++
            }
        }
        return n
    }

    /** شبیه‌سازی کرش: کاری که در پایگاه داده RUNNING مانده. */
    @Synchronized fun forceState(id: Long, state: JobState) {
        jobs[id] = jobs[id]!!.copy(state = state)
    }
}

class FakeEnv : DeviceEnvironment {
    var time = 1_000_000L
    var freeRam = 4L * 1024 * 1024 * 1024
    var totalRam = 6L * 1024 * 1024 * 1024
    var lowMem = false
    var cores = 8
    var temp = 30.0
    var network = true
    var unmetered = true

    override fun now() = time
    override fun availableRamBytes() = freeRam
    override fun totalRamBytes() = totalRam
    override fun isLowMemory() = lowMem
    override fun cpuCores() = cores
    override fun batteryTempC() = temp
    override fun isNetworkAvailable() = network
    override fun isUnmeteredNetwork() = unmetered
}

/** اجراکننده‌ای که کارها را نگه می‌دارد تا تست ترتیب اجرا را دقیق کنترل کند. */
class ManualExecutor : Executor {
    val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runNext(): Boolean {
        val t = tasks.removeFirstOrNull() ?: return false
        t.run()
        return true
    }

    fun runAll(max: Int = 1000) {
        var n = 0
        while (n++ < max && runNext()) {
            // اجرای کار بعدی
        }
    }
}

/** اجراکنندهٔ قابل برنامه‌ریزی: برای هر کار نتیجهٔ از پیش تعیین‌شده برمی‌گرداند. */
class ScriptedRunner(var defaultResult: (Job) -> RunResult = { RunResult.Success { it } }) : JobRunner {
    val results = HashMap<Long, ArrayDeque<RunResult>>()
    val runs = ArrayList<Long>()
    val stopped = ArrayList<Long>()
    var onRun: ((Job, RunControl) -> Unit)? = null

    fun enqueueResult(id: Long, r: RunResult) {
        results.getOrPut(id) { ArrayDeque() }.addLast(r)
    }

    override fun run(job: Job, control: RunControl): RunResult {
        runs.add(job.id)
        onRun?.invoke(job, control)
        if (control.stopRequested) return RunResult.Failure("STOPPED", false, "")
        return results[job.id]?.removeFirstOrNull() ?: defaultResult(job)
    }

    override fun stop(jobId: Long) {
        stopped.add(jobId)
    }
}

class RecordingListener : SchedulerListener {
    val finished = ArrayList<Job>()
    val discarded = ArrayList<Pair<Job, Boolean>>()
    var lastRunning = 0
    override fun onJobsChanged() {}
    override fun onJobFinished(job: Job) {
        finished.add(job)
    }
    override fun onJobDiscarded(job: Job, removed: Boolean) {
        discarded.add(job to removed)
    }
    override fun onRunningCountChanged(running: Int) {
        lastRunning = running
    }
}

fun newJob(url: String = "https://example.com/v") = Job(url = url, formatSelector = "137+bestaudio/137")
