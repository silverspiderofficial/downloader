package com.ali.downloader.ipc

import com.ali.downloader.core.Job
import com.ali.downloader.core.QueueSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * پروتکل Messenger بین پروسهٔ اصلی (رابط کاربری) و پروسهٔ «:worker».
 * داده‌ها به‌صورت رشتهٔ JSON داخل Bundle جابه‌جا می‌شوند؛ خروجی بزرگ تحلیل لینک در فایل نوشته می‌شود
 * تا از سقف حجم تراکنش Binder عبور نکند.
 */
object Ipc {
    // اصلی → worker
    const val MSG_REGISTER = 1
    const val MSG_UNREGISTER = 2
    const val MSG_ANALYZE = 3
    const val MSG_ENQUEUE = 4
    const val MSG_JOB_ACTION = 5
    const val MSG_SETTINGS = 6
    const val MSG_ENGINE_INFO = 7
    const val MSG_ENGINE_UPDATE = 8
    const val MSG_ENGINE_ROLLBACK = 9
    const val MSG_LIST_EXTRACTORS = 10
    const val MSG_REQUEST_SNAPSHOT = 11

    // worker → اصلی
    const val MSG_SNAPSHOT = 101
    const val MSG_REPLY = 102

    const val KEY_REQ = "req"
    const val KEY_JSON = "json"

    fun jobToJson(j: Job): JSONObject = JSONObject().apply {
        put("id", j.id)
        put("url", j.url)
        put("title", j.title)
        put("thumbnail", j.thumbnail)
        put("formatLabel", j.formatLabel)
        put("stage", j.stage.name)
        put("state", j.state.name)
        put("phase", j.phase.name)
        put("waitReason", j.waitReason.name)
        put("progress", j.progress)
        put("downloadedBytes", j.downloadedBytes)
        put("totalBytes", j.totalBytes)
        put("speedBps", j.speedBps)
        put("etaSec", j.etaSec)
        put("attempts", j.attempts)
        put("errorCode", j.errorCode)
        put("errorDetail", j.errorDetail)
        put("notBefore", j.notBefore)
        put("createdAt", j.createdAt)
        put("finishedAt", j.finishedAt)
        put("outputUri", j.outputUri)
        put("outputName", j.outputName)
        put("sizeBytes", j.sizeBytes)
        put("expectedBytes", j.expectedBytes)
    }

    fun jobsToJson(jobs: List<Job>): String {
        val arr = JSONArray()
        for (j in jobs) arr.put(jobToJson(j))
        return arr.toString()
    }

    fun settingsToJson(s: QueueSettings): String = JSONObject().apply {
        put("maxConcurrentDownloads", s.maxConcurrentDownloads)
        put("wifiOnly", s.wifiOnly)
        put("speedLimitKBps", s.speedLimitKBps)
        put("useAria2c", s.useAria2c)
        put("embedSubs", s.embedSubs)
        put("mergeFormat", s.mergeFormat)
        put("scheduleEnabled", s.scheduleEnabled)
        put("scheduleMinuteOfDay", s.scheduleMinuteOfDay)
        put("autoEngineCheck", s.autoEngineCheck)
        put("customFolderUri", s.customFolderUri)
    }.toString()

    fun settingsFromJson(json: String?): QueueSettings {
        val d = QueueSettings()
        if (json.isNullOrBlank()) return d
        return try {
            val o = JSONObject(json)
            QueueSettings(
                maxConcurrentDownloads = o.optInt("maxConcurrentDownloads", d.maxConcurrentDownloads).coerceIn(1, 10),
                wifiOnly = o.optBoolean("wifiOnly", d.wifiOnly),
                speedLimitKBps = o.optInt("speedLimitKBps", d.speedLimitKBps).coerceAtLeast(0),
                useAria2c = o.optBoolean("useAria2c", d.useAria2c),
                embedSubs = o.optBoolean("embedSubs", d.embedSubs),
                mergeFormat = o.optString("mergeFormat", d.mergeFormat).let { if (it == "mkv") "mkv" else "mp4/mkv" },
                scheduleEnabled = o.optBoolean("scheduleEnabled", d.scheduleEnabled),
                scheduleMinuteOfDay = o.optInt("scheduleMinuteOfDay", d.scheduleMinuteOfDay).coerceIn(0, 1439),
                autoEngineCheck = o.optBoolean("autoEngineCheck", d.autoEngineCheck),
                customFolderUri = o.optString("customFolderUri", ""),
            )
        } catch (_: Exception) {
            d
        }
    }
}
