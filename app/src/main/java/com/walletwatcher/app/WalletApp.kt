package com.walletwatcher.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isListenerConnected: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val db = LedgerDatabase(this)
        engine = LedgerEngine(
            store = db,
            isNetworkAvailable = { checkNetworkAvailable() },
            dedupWindowMs = 10_000L,
            mirrorWindowMs = 180_000L
        )
        startBufferFlusher()
        registerNetworkRecoveryCallback()
    }

    fun checkNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startBufferFlusher() {
        appScope.launch {
            while (isActive) {
                runCatching {
                    engine.flushExpired(forceAll = false)
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
