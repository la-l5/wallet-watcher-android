package com.walletwatcher.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.walletwatcher.app.core.BackupManager
import com.walletwatcher.app.core.LedgerDatabase
import com.walletwatcher.app.core.LedgerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WalletApp : Application() {

    lateinit var engine: LedgerEngine
        private set

    lateinit var backupManager: BackupManager
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isListenerConnected: Boolean = false

    @Volatile
    var isAccessibilityConnected: Boolean = false

    @Volatile
    var lastObservedEventAtMs: Long = 0L

    @Volatile
    var lastScreenCaptureAtMs: Long = 0L

    private val serviceStateListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addServiceStateListener(listener: () -> Unit) {
        serviceStateListeners.add(listener)
    }

    fun removeServiceStateListener(listener: () -> Unit) {
        serviceStateListeners.remove(listener)
    }

    fun notifyServiceStateChanged() {
        for (l in serviceStateListeners) {
            runCatching { l() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val db = LedgerDatabase(this)
        engine = LedgerEngine(
            store = db,
            isNetworkAvailable = { checkNetworkAvailable() },
            dedupWindowMs = 10_000L,
            mirrorWindowMs = 180_000L
        )
        backupManager = BackupManager(engine)
        startBufferFlusher()
        registerNetworkRecoveryCallback()
    }

    fun checkNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isPowerSaveMode(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isPowerSaveMode
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    fun getLastReadableExitReason(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "未知（Android 10 及以下不提供）"
        }
        return runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "未知"
            val list = am.getHistoricalProcessExitReasons(packageName, 0, 1)
            val info = list.firstOrNull() ?: return "无历史退出记录"
            when (info.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> "系统低内存回收"
                ApplicationExitInfo.REASON_CRASH -> "应用崩溃"
                ApplicationExitInfo.REASON_ANR -> "应用无响应"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "用户或系统任务关闭"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源限制回收"
                ApplicationExitInfo.REASON_FREEZER -> "冻结态被系统终止"
                else -> "其他原因(${info.reason})"
            }
        }.getOrDefault("未知")
    }

    private fun startBufferFlusher() {
        appScope.launch {
            var ticks = 0
            while (isActive) {
                runCatching {
                    engine.flushExpired(forceAll = false)
                }
                ticks++
                if (ticks >= 30) {
                    ticks = 0
                    runCatching {
                        if (engine.store.listOfflineQueue().isNotEmpty()) {
                            engine.replayOfflineQueue()
                        }
                    }
                }
                delay(1000L)
            }
        }
    }

    private fun registerNetworkRecoveryCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    appScope.launch {
                        runCatching { engine.replayOfflineQueue() }
                    }
                }
            })
        }
    }
}
