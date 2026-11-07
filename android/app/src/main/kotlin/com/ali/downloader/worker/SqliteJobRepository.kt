package com.ali.downloader.worker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ali.downloader.core.Job
import com.ali.downloader.core.JobPhase
import com.ali.downloader.core.JobRepository
import com.ali.downloader.core.JobStage
import com.ali.downloader.core.JobState
import com.ali.downloader.core.WaitReason

/**
 * صف ماندگار در SQLite بومی. فقط پروسهٔ worker این پایگاه داده را باز می‌کند، پس یک نویسنده داریم؛
 * همهٔ تغییرها در تراکنش انجام می‌شوند و WAL فعال است تا داده‌ای خراب نشود.
 */
class SqliteJobRepository(context: Context) : JobRepository {

    private val helper = object : SQLiteOpenHelper(context, "jobs.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    url TEXT NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    thumbnail TEXT NOT NULL DEFAULT '',
                    format_selector TEXT NOT NULL,
                    format_label TEXT NOT NULL DEFAULT '',
                    merge_format TEXT NOT NULL DEFAULT 'mp4/mkv',
                    embed_subs INTEGER NOT NULL DEFAULT 1,
                    stage TEXT NOT NULL,
                    state TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    wait_reason TEXT NOT NULL DEFAULT 'NONE',
                    progress REAL NOT NULL DEFAULT -1,
                    downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                    total_bytes INTEGER NOT NULL DEFAULT 0,
                    speed_bps INTEGER NOT NULL DEFAULT 0,
                    eta_sec INTEGER NOT NULL DEFAULT -1,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    error_code TEXT NOT NULL DEFAULT '',
                    error_detail TEXT NOT NULL DEFAULT '',
                    not_before INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    finished_at INTEGER NOT NULL DEFAULT 0,
                    private_path TEXT NOT NULL DEFAULT '',
                    output_uri TEXT NOT NULL DEFAULT '',
                    output_name TEXT NOT NULL DEFAULT '',
                    size_bytes INTEGER NOT NULL DEFAULT 0,
                    use_aria2c INTEGER NOT NULL DEFAULT 1,
                    subs_disabled INTEGER NOT NULL DEFAULT 0,
                    publish_temp_ref TEXT NOT NULL DEFAULT '',
                    expected_bytes INTEGER NOT NULL DEFAULT 0
                )"""
            )
            db.execSQL("CREATE INDEX idx_jobs_state ON jobs(state)")
            db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private fun toValues(j: Job): ContentValues = ContentValues().apply {
        put("url", j.url)
        put("title", j.title)
        put("thumbnail", j.thumbnail)
        put("format_selector", j.formatSelector)
        put("format_label", j.formatLabel)
        put("merge_format", j.mergeFormat)
        put("embed_subs", if (j.embedSubs) 1 else 0)
        put("stage", j.stage.name)
        put("state", j.state.name)
        put("phase", j.phase.name)
        put("wait_reason", j.waitReason.name)
        put("progress", j.progress)
        put("downloaded_bytes", j.downloadedBytes)
        put("total_bytes", j.totalBytes)
        put("speed_bps", j.speedBps)
        put("eta_sec", j.etaSec)
        put("attempts", j.attempts)
        put("error_code", j.errorCode)
        put("error_detail", j.errorDetail)
        put("not_before", j.notBefore)
        put("created_at", j.createdAt)
        put("updated_at", j.updatedAt)
        put("finished_at", j.finishedAt)
        put("private_path", j.privatePath)
        put("output_uri", j.outputUri)
        put("output_name", j.outputName)
        put("size_bytes", j.sizeBytes)
        put("use_aria2c", if (j.useAria2c) 1 else 0)
        put("subs_disabled", if (j.subsDisabled) 1 else 0)
        put("publish_temp_ref", j.publishTempRef)
        put("expected_bytes", j.expectedBytes)
    }

    private fun Cursor.str(c: String) = getString(getColumnIndexOrThrow(c)) ?: ""
    private fun Cursor.lng(c: String) = getLong(getColumnIndexOrThrow(c))
    private fun Cursor.int(c: String) = getInt(getColumnIndexOrThrow(c))
    private fun Cursor.dbl(c: String) = getDouble(getColumnIndexOrThrow(c))

    private inline fun <reified T : Enum<T>> parseEnum(v: String, def: T): T =
        enumValues<T>().firstOrNull { it.name == v } ?: def

    private fun fromCursor(c: Cursor): Job = Job(
        id = c.lng("id"),
        url = c.str("url"),
        title = c.str("title"),
        thumbnail = c.str("thumbnail"),
        formatSelector = c.str("format_selector"),
        formatLabel = c.str("format_label"),
        mergeFormat = c.str("merge_format"),
        embedSubs = c.int("embed_subs") != 0,
        stage = parseEnum(c.str("stage"), JobStage.DOWNLOAD),
        state = parseEnum(c.str("state"), JobState.QUEUED),
        phase = parseEnum(c.str("phase"), JobPhase.PREPARING),
        waitReason = parseEnum(c.str("wait_reason"), WaitReason.NONE),
        progress = c.dbl("progress"),
        downloadedBytes = c.lng("downloaded_bytes"),
        totalBytes = c.lng("total_bytes"),
        speedBps = c.lng("speed_bps"),
        etaSec = c.lng("eta_sec"),
        attempts = c.int("attempts"),
        errorCode = c.str("error_code"),
        errorDetail = c.str("error_detail"),
        notBefore = c.lng("not_before"),
        createdAt = c.lng("created_at"),
        updatedAt = c.lng("updated_at"),
        finishedAt = c.lng("finished_at"),
        privatePath = c.str("private_path"),
        outputUri = c.str("output_uri"),
        outputName = c.str("output_name"),
        sizeBytes = c.lng("size_bytes"),
        useAria2c = c.int("use_aria2c") != 0,
        subsDisabled = c.int("subs_disabled") != 0,
        publishTempRef = c.str("publish_temp_ref"),
        expectedBytes = c.lng("expected_bytes"),
    )

    @Synchronized
    override fun insert(job: Job): Long = db.insertOrThrow("jobs", null, toValues(job))

    @Synchronized
    override fun get(id: Long): Job? =
        db.query("jobs", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) fromCursor(c) else null
        }

    @Synchronized
    override fun all(): List<Job> =
        db.query("jobs", null, null, null, null, null, "created_at ASC, id ASC").use { c ->
            val out = ArrayList<Job>(c.count)
            while (c.moveToNext()) out.add(fromCursor(c))
            out
        }

    @Synchronized
    override fun delete(id: Long) {
        db.delete("jobs", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    override fun transition(id: Long, expected: Set<JobState>?, mutate: (Job) -> Job): Job? {
        val d = db
        d.beginTransactionNonExclusive()
        try {
            val cur = get(id) ?: return null
            if (expected != null && cur.state !in expected) return null
            val next = mutate(cur).copy(id = id)
            d.update("jobs", toValues(next), "id=?", arrayOf(id.toString()))
            d.setTransactionSuccessful()
            return next
        } finally {
            d.endTransaction()
        }
    }

    @Synchronized
    override fun recoverInterrupted(now: Long): Int {
        val cv = ContentValues().apply {
            put("state", JobState.QUEUED.name)
            put("speed_bps", 0)
            put("eta_sec", -1)
            put("updated_at", now)
        }
        return db.update("jobs", cv, "state=?", arrayOf(JobState.RUNNING.name))
    }

    /** پاک کردن کارهای تمام‌شدهٔ قدیمی تا فهرست بی‌اندازه بزرگ نشود (۳۰۰ مورد آخر نگه داشته می‌شود). */
    @Synchronized
    fun trimFinished(keep: Int = 300) {
        db.execSQL(
            "DELETE FROM jobs WHERE state IN ('COMPLETED','CANCELED') AND id NOT IN " +
                "(SELECT id FROM jobs WHERE state IN ('COMPLETED','CANCELED') ORDER BY finished_at DESC LIMIT $keep)"
        )
    }

    // ---- کلید/مقدار برای تنظیمات و وضعیت موتور در پروسهٔ worker ----

    @Synchronized
    fun getKv(key: String): String? =
        db.query("kv", arrayOf("v"), "k=?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    @Synchronized
    fun putKv(key: String, value: String) {
        db.insertWithOnConflict("kv", null, ContentValues().apply {
            put("k", key)
            put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
