package com.ali.downloader.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.ali.downloader.common.ErrorLog
import com.ali.downloader.ipc.Ipc
import com.ali.downloader.worker.DownloadService
import org.json.JSONObject

/**
 * ناظر پروسهٔ worker در پروسهٔ اصلی: اتصال با bindService و Messenger، صف پیام‌ها تا وصل شدن،
 * و اتصال دوباره (با فاصلهٔ افزایشی) اگر پروسهٔ worker کرش کرد. کارهای نیمه‌تمام را خود worker از صف ادامه می‌دهد.
 */
class WorkerClient(private val ctx: Context) {

    var onSnapshot: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var service: Messenger? = null
    private var bound = false
    private var nextReq = 1
    private val pending = HashMap<Int, (JSONObject) -> Unit>()
    private val outbox = ArrayList<Message>()
    private var lastSettings: String? = null
    private var retryDelay = 1_000L
    private var hadActiveWork = false

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val json = msg.data?.getString(Ipc.KEY_JSON) ?: ""
            when (msg.what) {
                Ipc.MSG_SNAPSHOT -> {
                    hadActiveWork = json.contains("\"RUNNING\"") || json.contains("\"QUEUED\"")
                    onSnapshot?.invoke(json)
                }
                Ipc.MSG_REPLY -> {
                    val req = msg.data?.getInt(Ipc.KEY_REQ) ?: 0
                    val cb = pending.remove(req) ?: return
                    val obj = try {
                        JSONObject(json)
                    } catch (_: Exception) {
                        JSONObject().put("ok", false).put("code", "UNKNOWN")
                    }
                    cb(obj)
                }
            }
        }
    })

    private val deathRecipient = IBinder.DeathRecipient {
        handler.post { onWorkerLost("binderDied") }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) return
            service = Messenger(binder)
            retryDelay = 1_000L
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (_: RemoteException) {
            }
            sendRaw(Message.obtain(null, Ipc.MSG_REGISTER).apply { replyTo = incoming })
            lastSettings?.let { sendRaw(build(Ipc.MSG_SETTINGS, it, 0)) }
            val queued = ArrayList(outbox)
            outbox.clear()
            queued.forEach { sendRaw(it) }
            onConnectionChanged?.invoke(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // سیستم بعد از کرش خودش دوباره وصل می‌کند (BIND_AUTO_CREATE)
            onWorkerLost("disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            onWorkerLost("bindingDied")
            unbindQuietly()
            scheduleRebind()
        }

        override fun onNullBinding(name: ComponentName?) {
            onWorkerLost("nullBinding")
        }
    }

    private fun onWorkerLost(why: String) {
        if (service == null && pending.isEmpty()) return
        ErrorLog.write(ctx, "main", "worker-supervisor", "worker connection lost: $why")
        service = null
        onConnectionChanged?.invoke(false)
        // درخواست‌های در جریان با پیام روشن تمام می‌شوند
        val cbs = ArrayList(pending.values)
        pending.clear()
        cbs.forEach { it(JSONObject().put("ok", false).put("code", "WORKER_RESTARTED").put("status", "failed")) }
        // اگر کار فعالی بود، سرویس پیش‌زمینه دوباره شروع می‌شود تا صف ادامه یابد
        if (hadActiveWork) DownloadService.startForegroundCompat(ctx)
    }

    private fun scheduleRebind() {
        handler.postDelayed({ bind() }, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
    }

    fun bind() {
        if (bound) return
        bound = try {
            ctx.bindService(Intent(ctx, DownloadService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            ErrorLog.write(ctx, "main", "bind", t.message ?: "", t)
            false
        }
        if (!bound) scheduleRebind()
    }

    private fun unbindQuietly() {
        if (!bound) return
        try {
            ctx.unbindService(conn)
        } catch (_: Throwable) {
        }
        bound = false
        service = null
    }

    fun unbind() {
        service?.let {
            try {
                it.send(Message.obtain(null, Ipc.MSG_UNREGISTER).apply { replyTo = incoming })
            } catch (_: RemoteException) {
            }
        }
        handler.removeCallbacksAndMessages(null)
        unbindQuietly()
    }

    private fun build(what: Int, json: String, req: Int): Message =
        Message.obtain(null, what).apply {
            replyTo = incoming
            data = Bundle().apply {
                putString(Ipc.KEY_JSON, json)
                putInt(Ipc.KEY_REQ, req)
            }
        }

    private fun sendRaw(m: Message) {
        val s = service
        if (s == null) {
            outbox.add(m)
            bind()
            return
        }
        try {
            s.send(m)
        } catch (_: RemoteException) {
            outbox.add(m)
            onWorkerLost("sendFailed")
        }
    }

    /** ارسال درخواست و دریافت پاسخ؛ اگر تا [timeoutMs] پاسخی نیامد با خطای TIMEOUT تمام می‌شود. */
    fun request(what: Int, json: JSONObject, timeoutMs: Long, cb: (JSONObject) -> Unit) {
        val req = nextReq++
        pending[req] = cb
        handler.postDelayed({
            pending.remove(req)?.invoke(JSONObject().put("ok", false).put("code", "TIMEOUT").put("status", "failed"))
        }, timeoutMs)
        sendRaw(build(what, json.toString(), req))
    }

    fun send(what: Int, json: JSONObject) = sendRaw(build(what, json.toString(), 0))

    fun pushSettings(json: String) {
        lastSettings = json
        sendRaw(build(Ipc.MSG_SETTINGS, json, 0))
    }
}
