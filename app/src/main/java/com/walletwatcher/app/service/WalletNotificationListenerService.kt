package com.walletwatcher.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.walletwatcher.app.R
import com.walletwatcher.app.WalletApp
import com.walletwatcher.app.core.RawNotification
import com.walletwatcher.app.ui.MainActivity
import kotlinx.coroutines.launch

class WalletNotificationListenerService : NotificationListenerService() {

    private val app: WalletApp
        get() = application as WalletApp

    private val summaryLinesLock = Any()
    private val summaryLines = LinkedHashMap<String, List<String>>()
    private val appLabelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        app.isListenerConnected = true
        app.engine.store.addLog("SERVICE", "通知监听服务已连接，开始后台守护")
        startGuardForegroundNotification()
        app.notifyServiceStateChanged()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        app.isListenerConnected = false
        app.engine.store.addLog("SERVICE", "通知监听服务已断开（若在荣耀手机上频繁出现，请检查电池与启动管理白名单）")
        app.notifyServiceStateChanged()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        synchronized(summaryLinesLock) {
            summaryLines.remove(key)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // Ignore our own guard notification and core Android system UI
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return

        if (!isCandidateFinancePackage(pkg)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val regularText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val content = if (bigText.length >= regularText.length && bigText.isNotEmpty()) bigText else regularText

        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        // Skip empty group summary shell when child notifications arrive individually
        if (isGroupSummary && textLines.isEmpty() && content.isEmpty()) return
        if (title.isEmpty() && content.isEmpty() && textLines.isEmpty()) return

        val newLines = if (isGroupSummary && textLines.isNotEmpty()) {
            synchronized(summaryLinesLock) {
                val previous = summaryLines.put(sbn.key, textLines).orEmpty().toMutableList()
                if (summaryLines.size > 128) {
                    val oldestKey = summaryLines.keys.firstOrNull()
                    if (oldestKey != null) summaryLines.remove(oldestKey)
                }
                textLines.filter { !previous.remove(it) }
            }
        } else {
            textLines
        }
        if (isGroupSummary && (newLines.isEmpty() || textLines.isEmpty())) return

        val appName = resolveAppName(pkg)
        val sourceType = if (isSmsPackage(pkg)) "sms" else "notification"

        val raw = RawNotification(
            appPackage = pkg,
            appName = appName,
            title = title.ifEmpty { appName },
            content = content.ifEmpty { textLines.joinToString("；") },
            timestampMs = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            sourceType = sourceType,
            isGroupSummary = isGroupSummary && textLines.isNotEmpty(),
            notificationId = sbn.key,
            subMessages = if (isGroupSummary) newLines else emptyList()
        )

        app.lastObservedEventAtMs = System.currentTimeMillis()
        app.appScope.launch {
            runCatching {
                app.engine.ingestNotification(raw)
            }
        }
    }

    private fun isSmsPackage(pkg: String): Boolean {
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        val lower = pkg.lowercase()
        return pkg == defaultSms ||
            lower.contains("mms") ||
            lower.contains("messaging") ||
            lower.contains(".sms")
    }

    private fun isCandidateFinancePackage(pkg: String): Boolean {
        if (pkg in KNOWN_APPS || isSmsPackage(pkg)) return true
        val lower = pkg.lowercase()
        if (FINANCE_PKG_HINTS.any { lower.contains(it) }) return true
        val label = resolveAppName(pkg)
        return listOf("银行", "钱包", "支付", "证券", "理财", "信用", "短信").any { label.contains(it) }
    }

    private fun resolveAppName(pkg: String): String {
        KNOWN_APPS[pkg]?.let { return it }
        appLabelCache[pkg]?.let { return it }
        val resolved = runCatching {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
        if (appLabelCache.size > 200) appLabelCache.clear()
        appLabelCache[pkg] = resolved
        return resolved
    }

    private fun startGuardForegroundNotification() {
        runCatching {
            ensureChannel()
            val notif = buildGuardNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    GUARD_NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(GUARD_NOTIF_ID, notif)
            }
        }.onFailure {
            app.engine.store.addLog("SERVICE", "前台状态通知启动失败：${it.javaClass.simpleName}")
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动记账守护状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持通知监听服务在后台稳定运行，低优先级不弹窗不响铃"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildGuardNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wallet_guard)
            .setContentTitle("钱包监测守护中")
            .setContentText("正在监听本机支付与动账通知")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wallet_watcher_guard"
        private const val GUARD_NOTIF_ID = 1001

        private val FINANCE_PKG_HINTS = listOf(
            "bank", "pay", "wallet", "cmb", "icbc", "ccb", "boc", "abc",
            "psbc", "spdb", "cib", "ceb", "cgb", "cmbc", "pingan", "ecitic", "hxb", "dcep"
        )

        private val KNOWN_APPS = mapOf(
            "com.tencent.mm" to "微信",
            "com.eg.android.AlipayGphone" to "支付宝",
            "com.unionpay" to "云闪付",
            "cn.gov.pbc.dcep" to "数字人民币",
            // 国有与股份制主流银行及信用卡 App
            "cmb.pb" to "招商银行",
            "com.cmbchina.ccd.pluto.cmbActivity" to "掌上生活",
            "com.icbc" to "工商银行",
            "com.chinamworld.main" to "建设银行",
            "com.android.bankabc" to "农业银行",
            "com.chinamworld.bocmbci" to "中国银行",
            "com.bankcomm.Bankcomm" to "交通银行",
            "com.yitong.mbank.psbc" to "邮储银行",
            "com.pingan.paces.ccms" to "平安口袋银行",
            "com.ecitic.bank.mobile" to "中信银行",
            "cn.com.spdb.mobilebank.per" to "浦发银行",
            "com.cebbank.mobile.cemb" to "光大银行",
            "com.cib.cibmb" to "兴业银行",
            "cn.com.cmbc.newmbank" to "民生银行",
            "com.cgbchina.xpt" to "广发银行",
            "com.hxb.mobile" to "华夏银行",
            // 主流消费/出行平台
            "com.sankuai.meituan" to "美团",
            "com.sankuai.meituan.takeoutnew" to "美团外卖",
            "com.taobao.taobao" to "淘宝",
            "com.xunmeng.pinduoduo" to "拼多多",
            "com.jingdong.app.mall" to "京东",
            "com.ss.android.ugc.aweme" to "抖音",
            "com.sdu.didi.psnger" to "滴滴出行",
            // 荣耀/华为/通用短信
            "com.hihonor.mms" to "短信",
            "com.huawei.mms" to "短信",
            "com.android.mms" to "短信"
        )
    }
}
