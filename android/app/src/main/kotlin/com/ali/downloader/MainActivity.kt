package com.ali.downloader

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ali.downloader.bridge.LibraryOps
import com.ali.downloader.bridge.WorkerClient
import com.ali.downloader.common.ErrorLog
import com.ali.downloader.ipc.Ipc
import com.ali.downloader.worker.DownloadService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/**
 * پروسهٔ اصلی: فقط رابط کاربری Flutter و پل ارتباطی. هیچ موتور سنگینی اینجا اجرا نمی‌شود؛
 * همه‌چیز از طریق WorkerClient به پروسهٔ «:worker» فرستاده می‌شود.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val REQ_STORAGE = 501
        private const val REQ_NOTIF = 502
        private const val REQ_TREE = 503
        private const val REQ_DELETE = 504
        private const val REQ_BATTERY = 505
    }

    private lateinit var worker: WorkerClient
    private var events: EventChannel.EventSink? = null
    private var initialShare: String? = null
    private var lastSnapshot: String? = null
    private val pendingResults = HashMap<Int, MethodChannel.Result>()
    private var pendingDeleteUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            ErrorLog.write(this, "main", "crash", "thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
        if (savedInstanceState == null) initialShare = extractShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val text = extractShare(intent) ?: return
        val sink = events
        if (sink != null) sink.success(mapOf("type" to "share", "text" to text)) else initialShare = text
    }

    private fun extractShare(i: Intent?): String? {
        if (i == null) return null
        return when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                i.getStringExtra(Intent.EXTRA_TEXT),
                i.getStringExtra(Intent.EXTRA_SUBJECT),
            ).joinToString(" ").ifBlank { null }
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        worker = WorkerClient(applicationContext).apply {
            onSnapshot = { json ->
                lastSnapshot = json
                events?.success(mapOf("type" to "jobs", "json" to json))
            }
            onConnectionChanged = { c -> events?.success(mapOf("type" to "worker", "connected" to c)) }
            bind()
        }
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        EventChannel(messenger, "ali.downloader/events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                events = sink
                lastSnapshot?.let { sink.success(mapOf("type" to "jobs", "json" to it)) }
                worker.send(Ipc.MSG_REQUEST_SNAPSHOT, JSONObject())
            }

            override fun onCancel(arguments: Any?) {
                events = null
            }
        })
        MethodChannel(messenger, "ali.downloader/native").setMethodCallHandler { call, result ->
            try {
                handle(call, result)
            } catch (t: Throwable) {
                ErrorLog.write(this, "main", "channel:${call.method}", t.message ?: "", t)
                result.error("NATIVE", t.message, null)
            }
        }
    }

    override fun onDestroy() {
        if (::worker.isInitialized) worker.unbind()
        super.onDestroy()
    }

    private fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        for (k in o.keys()) {
            val v = o.get(k)
            m[k] = if (v == JSONObject.NULL) null else v
        }
        return m
    }

    @Suppress("UNCHECKED_CAST")
    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getInitialShare" -> {
                result.success(initialShare)
                initialShare = null
            }
            "analyze" -> worker.request(
                Ipc.MSG_ANALYZE, JSONObject().put("url", call.argument<String>("url") ?: ""), 4 * 60_000L,
            ) { result.success(jsonToMap(it)) }
            "enqueue" -> {
                val spec = JSONObject(call.arguments as Map<String, Any?>)
                // کاربر در اپ است، پس شروع سرویس پیش‌زمینه مجاز است
                DownloadService.startForegroundCompat(applicationContext)
                worker.request(Ipc.MSG_ENQUEUE, spec, 60_000L) { result.success(jsonToMap(it)) }
            }
            "jobAction" -> {
                val action = call.argument<String>("action") ?: ""
                if (action == "resume" || action == "retry" || action == "resumeAll") {
                    DownloadService.startForegroundCompat(applicationContext)
                }
                val o = JSONObject().put("action", action).put("id", (call.argument<Number>("id") ?: 0).toLong())
                worker.request(Ipc.MSG_JOB_ACTION, o, 30_000L) { result.success(jsonToMap(it)) }
            }
            "updateSettings" -> {
                worker.pushSettings(call.argument<String>("json") ?: "{}")
                result.success(true)
            }
            "requestSnapshot" -> {
                worker.send(Ipc.MSG_REQUEST_SNAPSHOT, JSONObject())
                result.success(true)
            }
            "engineInfo" -> worker.request(Ipc.MSG_ENGINE_INFO, JSONObject(), 2 * 60_000L) { result.success(jsonToMap(it)) }
            "engineUpdate" -> {
                DownloadService.startForegroundCompat(applicationContext)
                worker.request(Ipc.MSG_ENGINE_UPDATE, JSONObject(), 6 * 60_000L) { result.success(jsonToMap(it)) }
            }
            "engineRollback" -> worker.request(Ipc.MSG_ENGINE_ROLLBACK, JSONObject(), 3 * 60_000L) { result.success(jsonToMap(it)) }
            "listExtractors" -> worker.request(Ipc.MSG_LIST_EXTRACTORS, JSONObject(), 3 * 60_000L) { result.success(jsonToMap(it)) }
            "storageInfo" -> result.success(LibraryOps.storageInfo())
            "pickFolder" -> {
                pendingResults[REQ_TREE] = result
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, REQ_TREE)
                } catch (e: ActivityNotFoundException) {
                    pendingResults.remove(REQ_TREE)
                    result.success(null)
                }
            }
            "releaseFolder" -> {
                val u = call.argument<String>("uri") ?: ""
                if (u.isNotEmpty()) try {
                    contentResolver.releasePersistableUriPermission(
                        Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                }
                result.success(true)
            }
            "library" -> {
                val tree = call.argument<String>("customTree") ?: ""
                Thread {
                    val list = try {
                        LibraryOps.list(applicationContext, tree)
                    } catch (t: Throwable) {
                        ErrorLog.write(applicationContext, "main", "library", t.message ?: "", t)
                        emptyList()
                    }
                    runOnUiThread { result.success(list) }
                }.start()
            }
            "libraryOpen" -> {
                try {
                    startActivity(LibraryOps.openIntent(this, call.argument<String>("uri")!!, call.argument<String>("mime") ?: ""))
                    result.success(true)
                } catch (e: ActivityNotFoundException) {
                    result.success(false)
                }
            }
            "libraryShare" -> {
                startActivity(LibraryOps.shareIntent(this, call.argument<String>("uri")!!, call.argument<String>("mime") ?: ""))
                result.success(true)
            }
            "libraryDelete" -> deleteItem(call.argument<String>("uri")!!, result)
            "permissionStatus" -> result.success(permissionStatus())
            "requestStorage" -> requestStorage(result)
            "requestNotifications" -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    pendingResults[REQ_NOTIF] = result
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                } else result.success(true)
            }
            "requestBatteryExemption" -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    result.success(true)
                } else {
                    pendingResults[REQ_BATTERY] = result
                    try {
                        @Suppress("BatteryLife", "DEPRECATION")
                        startActivityForResult(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                            REQ_BATTERY,
                        )
                    } catch (e: ActivityNotFoundException) {
                        pendingResults.remove(REQ_BATTERY)
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {
                        }
                        result.success(false)
                    }
                }
            }
            "readLogs" -> result.success(ErrorLog.readAll(this))
            "clearLogs" -> {
                ErrorLog.clear(this)
                result.success(true)
            }
            "logError" -> {
                ErrorLog.write(this, "main", call.argument<String>("tag") ?: "flutter", call.argument<String>("message") ?: "")
                result.success(true)
            }
            "deviceInfo" -> {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                result.success(
                    mapOf(
                        "sdk" to Build.VERSION.SDK_INT,
                        "abis" to Build.SUPPORTED_ABIS.toList(),
                        "totalRam" to mi.totalMem,
                        "cores" to Runtime.getRuntime().availableProcessors(),
                        "model" to (Build.MANUFACTURER + " " + Build.MODEL),
                    )
                )
            }
            else -> result.notImplemented()
        }
    }

    private fun storagePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT <= 28 -> arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> emptyArray()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun permissionStatus(): Map<String, Any> {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val storage = storagePermissions()
        return mapOf(
            "sdk" to Build.VERSION.SDK_INT,
            "storageNeeded" to storage.isNotEmpty(),
            "storageGranted" to storage.all { granted(it) },
            "storageRequired" to (Build.VERSION.SDK_INT <= 28),
            "notificationsNeeded" to (Build.VERSION.SDK_INT >= 33),
            "notificationsGranted" to NotificationManagerCompat.from(this).areNotificationsEnabled(),
            "batteryExempt" to pm.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun requestStorage(result: MethodChannel.Result) {
        val perms = storagePermissions()
        if (perms.isEmpty() || perms.all { granted(it) }) {
            result.success(true)
            return
        }
        pendingResults[REQ_STORAGE] = result
        ActivityCompat.requestPermissions(this, perms, REQ_STORAGE)
    }

    private fun deleteItem(uri: String, result: MethodChannel.Result) {
        try {
            result.success(LibraryOps.deleteDirect(this, uri))
        } catch (e: SecurityException) {
            // فایل‌هایی که اپ قبلاً (مثلاً پیش از نصب دوباره) ساخته، تأیید کاربر را لازم دارند
            try {
                val sender = when {
                    Build.VERSION.SDK_INT >= 30 ->
                        MediaStore.createDeleteRequest(contentResolver, listOf(Uri.parse(uri))).intentSender
                    Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                    else -> null
                }
                if (sender == null) {
                    result.success(false)
                    return
                }
                pendingResults[REQ_DELETE] = result
                pendingDeleteUri = uri
                @Suppress("DEPRECATION")
                startIntentSenderForResult(sender, REQ_DELETE, null, 0, 0, 0)
            } catch (t: Throwable) {
                ErrorLog.write(this, "main", "delete", t.message ?: "", t)
                result.success(false)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val r = pendingResults.remove(requestCode) ?: return
        r.success(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val r = pendingResults.remove(requestCode) ?: return
        when (requestCode) {
            REQ_TREE -> {
                val uri = data?.data
                if (resultCode != Activity.RESULT_OK || uri == null) {
                    r.success(null)
                    return
                }
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (t: Throwable) {
                    ErrorLog.write(this, "main", "tree", t.message ?: "", t)
                }
                val docId = try {
                    DocumentsContract.getTreeDocumentId(uri)
                } catch (_: Exception) {
                    ""
                }
                val label = docId.substringAfter(':', docId).ifEmpty { "حافظهٔ اصلی" }
                r.success(mapOf("uri" to uri.toString(), "label" to label))
            }
            REQ_DELETE -> {
                val ok = resultCode == Activity.RESULT_OK
                val u = pendingDeleteUri
                pendingDeleteUri = null
                if (ok && Build.VERSION.SDK_INT == 29 && u != null) {
                    // اندروید ۱۰: بعد از تأیید، حذف دوباره انجام می‌شود
                    r.success(try { LibraryOps.deleteDirect(this, u) } catch (_: Exception) { false })
                } else r.success(ok)
            }
            REQ_BATTERY -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                r.success(pm.isIgnoringBatteryOptimizations(packageName))
            }
            else -> r.success(null)
        }
    }
}
