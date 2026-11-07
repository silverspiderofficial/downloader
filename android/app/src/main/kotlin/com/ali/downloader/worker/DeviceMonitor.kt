package com.ali.downloader.worker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.ali.downloader.core.DeviceEnvironment

/**
 * وضعیت واقعی دستگاه: رم آزاد، هسته‌ها، دمای باتری و شبکه. تغییر شبکه به زمان‌بند خبر داده می‌شود
 * تا کارهای منتظر بعد از برگشت اینترنت خودکار ادامه پیدا کنند.
 */
class DeviceMonitor(private val ctx: Context, private val onNetworkChanged: () -> Unit) : DeviceEnvironment {
    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var lastMemCheck = 0L
    @Volatile private var memInfo = ActivityManager.MemoryInfo()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onNetworkChanged()
    }

    fun register() {
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    fun unregister() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun mem(): ActivityManager.MemoryInfo {
        val now = System.currentTimeMillis()
        if (now - lastMemCheck > 2_000) {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            memInfo = mi
            lastMemCheck = now
        }
        return memInfo
    }

    override fun now(): Long = System.currentTimeMillis()
    override fun availableRamBytes(): Long = mem().availMem
    override fun totalRamBytes(): Long = mem().totalMem
    override fun isLowMemory(): Boolean = mem().lowMemory
    override fun cpuCores(): Int = Runtime.getRuntime().availableProcessors()

    override fun batteryTempC(): Double {
        return try {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (t == Int.MIN_VALUE) Double.NaN else t / 10.0
        } catch (_: Exception) {
            Double.NaN
        }
    }

    private fun caps(): NetworkCapabilities? = try {
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    } catch (_: Exception) {
        null
    }

    override fun isNetworkAvailable(): Boolean {
        val c = caps() ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun isUnmeteredNetwork(): Boolean {
        val c = caps() ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
