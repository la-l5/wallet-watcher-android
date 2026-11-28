package com.walletwatcher.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.walletwatcher.app.BuildConfig
import com.walletwatcher.app.R
import com.walletwatcher.app.WalletApp
import com.walletwatcher.app.core.AiConfig
import com.walletwatcher.app.core.ApkInstallPreflight
import com.walletwatcher.app.core.AppUpdater
import com.walletwatcher.app.core.MoneyMath
import com.walletwatcher.app.core.RawNotification
import com.walletwatcher.app.core.ReleaseUpdateInfo
import com.walletwatcher.app.core.ScreenBillParser
import com.walletwatcher.app.core.Transaction
import com.walletwatcher.app.core.TxDirection
import com.walletwatcher.app.core.TxStatus
import com.walletwatcher.app.core.UpdateCheckResult
import com.walletwatcher.app.core.UserProfile
import com.walletwatcher.app.core.VerifiedApkArchive
import com.walletwatcher.app.service.WalletNotificationListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val app: WalletApp
        get() = application as WalletApp

    private enum class Tab { LEDGER, IGNORED, LOGS }
    private var currentTab = Tab.LEDGER

    private lateinit var tvServiceStatus: TextView
    private lateinit var btnPermissionPipeline: Button
    private lateinit var btnScreenEnhance: Button
    private lateinit var bannerPermissionTodo: LinearLayout
    private lateinit var tvBannerTodo: TextView
    private lateinit var tvNetExpense: TextView
    private lateinit var tvIncome: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvSubStats: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tabLedger: Button
    private lateinit var tabIgnored: Button
    private lateinit var tabLogs: Button
    private lateinit var logToolbar: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TransactionAdapter

    private var activePipelineRefresher: (() -> Unit)? = null

    private val screenJob = kotlinx.coroutines.SupervisorJob()
    private val screenScope = kotlinx.coroutines.CoroutineScope(screenJob + Dispatchers.IO)

    private var pendingBackupPassword: CharArray? = null
    private var updateJob: kotlinx.coroutines.Job? = null
    private var pendingInstallArchive: VerifiedApkArchive? = null

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pwd = pendingBackupPassword
        pendingBackupPassword = null
        if (uri == null || pwd == null) {
            pwd?.fill('\u0000')
            return@registerForActivityResult
        }
        screenScope.launch {
            try {
                val encrypted = app.backupManager.exportEncryptedBackup(pwd)
                contentResolver.openOutputStream(uri)?.use { it.write(encrypted) }
                    ?: error("无法打开导出文件写入流")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "加密备份已导出（${encrypted.size / 1024}KB）",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                pwd.fill('\u0000')
            }
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        promptPasswordDialog(
            title = "输入备份密码以恢复账本",
            hint = "原账本在校验全部通过前不会被修改"
        ) { pwd ->
            screenScope.launch {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取备份文件")
                    val report = app.backupManager.restoreEncryptedBackup(bytes, pwd)
                    val summaryLine = report.currencyChecks.joinToString("，") { c ->
                        "${c.currency} ${c.txCount}笔/${MoneyMath.formatUnsignedMinor(c.totalAmountMinor, c.currency, c.scale)}"
                    }
                    withContext(Dispatchers.Main) {
                        refreshUi()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("账本恢复并校验成功")
                            .setMessage(
                                "已恢复 ${report.transactionCount} 笔账单、${report.sourceCount} 条来源证据、${report.pendingQueueCount} 条待处理队列。\n" +
                                    "分币种核对：$summaryLine"
                            )
                            .setPositiveButton("确定", null)
                            .show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "恢复未生效（原账本保持完整）：${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    pwd.fill('\u0000')
                }
            }
        }
    }

    private val engineListener: () -> Unit = {
        runOnUiThread { if (!isDestroyed && !isFinishing) refreshUi() }
    }

    private val serviceStateListener: () -> Unit = {
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                refreshUi()
                activePipelineRefresher?.invoke()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        requestPostNotificationIfNeeded()
        app.engine.addOnChangeListener(engineListener)
        app.addServiceStateListener(serviceStateListener)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (PermissionRouter.isNotificationListenerGranted(this) && !app.isListenerConnected) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(this, WalletNotificationListenerService::class.java)
                )
            }
        }
        refreshUi()
        activePipelineRefresher?.invoke()
        resumePendingApkInstallIfAuthorized()
    }

    override fun onDestroy() {
        app.engine.removeOnChangeListener(engineListener)
        app.removeServiceStateListener(serviceStateListener)
        activePipelineRefresher = null
        refreshJob?.cancel()
        updateJob?.cancel()
        pendingInstallArchive = null
        screenJob.cancel()
        pendingBackupPassword?.fill('\u0000')
        pendingBackupPassword = null
        super.onDestroy()
    }

    private fun bindViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnPermissionPipeline = findViewById(R.id.btnPermissionPipeline)
        btnScreenEnhance = findViewById(R.id.btnScreenEnhance)
        bannerPermissionTodo = findViewById(R.id.bannerPermissionTodo)
        tvBannerTodo = findViewById(R.id.tvBannerTodo)
        tvNetExpense = findViewById(R.id.tvNetExpense)
        tvIncome = findViewById(R.id.tvIncome)
        tvBalance = findViewById(R.id.tvBalance)
        tvSubStats = findViewById(R.id.tvSubStats)
        tvEmpty = findViewById(R.id.tvEmpty)
        tabLedger = findViewById(R.id.tabLedger)
        tabIgnored = findViewById(R.id.tabIgnored)
        tabLogs = findViewById(R.id.tabLogs)
        logToolbar = findViewById(R.id.logToolbar)
        recyclerView = findViewById(R.id.recyclerView)

        adapter = TransactionAdapter(
            onRescueClick = { tx -> showRescueDialog(tx) },
            onManageClick = { tx -> showTransactionDetailDialog(tx) },
            onDeepLinkClick = { tx -> openDeepLink(tx) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        btnPermissionPipeline.setOnClickListener {
            showPermissionPipelineDialog()
        }

        btnScreenEnhance.setOnClickListener {
            showPermissionPipelineDialog()
        }

        findViewById<Button>(R.id.btnBannerFix).setOnClickListener {
            showPermissionPipelineDialog()
        }

        findViewById<Button>(R.id.btnSelfTest).setOnClickListener {
            runBuiltInSelfTest()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            showSettingsDialog()
        }

        tabLedger.setOnClickListener { switchTab(Tab.LEDGER) }
        tabIgnored.setOnClickListener { switchTab(Tab.IGNORED) }
        tabLogs.setOnClickListener { switchTab(Tab.LOGS) }

        findViewById<Button>(R.id.btnCopySamples).setOnClickListener {
            screenScope.launch {
                val text = app.engine.exportSanitizedDebugSamples()
                withContext(Dispatchers.Main) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("wallet_samples", text))
                    Toast.makeText(this@MainActivity, "已复制交易诊断摘要", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空所有本地记录？")
                .setMessage("仅清空账单、来源证据、内存缓冲和抓包日志，不会清空个人档案和设置。")
                .setPositiveButton("清空") { _, _ ->
                    app.appScope.launch {
                        app.engine.clearAllData()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        logToolbar.visibility = if (tab == Tab.LOGS) View.VISIBLE else View.GONE
        refreshUi()
    }

    private var refreshJob: kotlinx.coroutines.Job? = null

    private fun refreshUi() {
        val notifGranted = PermissionRouter.isNotificationListenerGranted(this)
        val batteryExempt = PermissionRouter.isIgnoringBatteryOptimizations(this)
        val screenEnabled = PermissionRouter.isAccessibilityServiceEnabled(this)
        val powerSave = app.isPowerSaveMode()

        val connText = when {
            !notifGranted -> "通知未开"
            app.isListenerConnected -> "通知守护中"
            powerSave -> "通知已断(省电模式)"
            else -> "通知已授权待重连"
        }
        val screenText = when {
            screenEnabled && app.isAccessibilityConnected -> "屏幕只读中"
            screenEnabled -> "屏幕已开"
            else -> "屏幕关(可选)"
        }
        val bufCount = app.engine.activeBufferCount()
        tvServiceStatus.text = "$connText · $screenText | ${app.engine.aiConfig.displayLabel}" +
            if (bufCount > 0) " | 缓冲($bufCount)" else ""

        val coreReadyCount = (if (notifGranted) 1 else 0) + (if (batteryExempt) 1 else 0)
        btnPermissionPipeline.text = if (coreReadyCount == 2) "权限已就绪 ✓" else "配权限 ($coreReadyCount/2)"
        btnScreenEnhance.text = if (screenEnabled) "屏幕增强: 开" else "屏幕增强: 关"

        if (coreReadyCount < 2) {
            bannerPermissionTodo.visibility = View.VISIBLE
            val missing = buildList {
                if (!notifGranted) add("通知使用权")
                if (!batteryExempt) add("忽略电池优化")
            }.joinToString("、")
            tvBannerTodo.text = "待开启：$missing（配置完返回即自动变绿）"
        } else {
            bannerPermissionTodo.visibility = View.GONE
        }

        refreshJob?.cancel()
        val tab = currentTab
        refreshJob = screenScope.launch {
            val summary = app.engine.getSummary()
            val allTxs = app.engine.store.listTransactions().filter { it.status != TxStatus.REVERTED }
            val rows: List<ListRow> = when (tab) {
                Tab.LEDGER -> allTxs
                    .filter { it.status == TxStatus.RECORDED || it.status == TxStatus.NEEDS_REVIEW }
                    .map { ListRow.TxRow(it, isIgnoredTab = false) }
                Tab.IGNORED -> allTxs
                    .filter { it.status == TxStatus.IGNORED }
                    .map { ListRow.TxRow(it, isIgnoredTab = true) }
                Tab.LOGS -> app.engine.store.listLogs(120).map { ListRow.LogRow(it) }
            }

            withContext(Dispatchers.Main) {
                val cny = summary.byCurrency["CNY"]
                tvNetExpense.text = MoneyMath.formatSignedMinor(cny?.netPersonalExpenseMinor ?: 0L, "CNY", 2)
                tvIncome.text = MoneyMath.formatSignedMinor(cny?.totalIncomeMinor ?: 0L, "CNY", 2)
                tvBalance.text = MoneyMath.formatSignedMinor(cny?.netBalanceMinor ?: 0L, "CNY", 2)

                val dbKb = String.format(Locale.CHINA, "%.1f", summary.dbSizeBytes / 1024.0)
                val foreignNote = summary.byCurrency.values
                    .filter { it.currency != "CNY" && it.grossExpenseMinor > 0L }
                    .joinToString(" · ") { c ->
                        "${c.currency}支出 ${MoneyMath.formatUnsignedMinor(c.grossExpenseMinor, c.currency, c.scale)}"
                    }
                    .let { if (it.isBlank()) "" else " · $it" }

                tvSubStats.text = "总支出 ${MoneyMath.formatUnsignedMinor(cny?.grossExpenseMinor ?: 0L)} · " +
                    "退款 ${MoneyMath.formatUnsignedMinor(cny?.totalRefundMinor ?: 0L)} · " +
                    "AA/报销 ${MoneyMath.formatUnsignedMinor(cny?.totalOffsetMinor ?: 0L)} · " +
                    "待核对 ${summary.needsReviewCount} · 内部互转 ${summary.internalTransferCount} · " +
                    "广告/忽略 ${summary.otherIgnoredCount} · 队列 ${summary.offlineQueueCount} · ${dbKb}KB$foreignNote"

                tabLedger.text = "主账本(${summary.recordedCount})"
                tabIgnored.text = "内部/忽略(${summary.ignoredCount})"

                adapter.submitList(rows)
                tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun requestPostNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    private fun showPermissionPipelineDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_permission_pipeline, null)
        val btnNextPendingStep = view.findViewById<Button>(R.id.btnNextPendingStep)
        val tvStepNotifyStatus = view.findViewById<TextView>(R.id.tvStepNotifyStatus)
        val tvStepNotifyDesc = view.findViewById<TextView>(R.id.tvStepNotifyDesc)
        val btnStepNotify = view.findViewById<Button>(R.id.btnStepNotify)

        val tvStepBatteryStatus = view.findViewById<TextView>(R.id.tvStepBatteryStatus)
        val btnStepBattery = view.findViewById<Button>(R.id.btnStepBattery)

        val tvStepOemTitle = view.findViewById<TextView>(R.id.tvStepOemTitle)
        val tvStepOemStatus = view.findViewById<TextView>(R.id.tvStepOemStatus)
        val tvStepOemDesc = view.findViewById<TextView>(R.id.tvStepOemDesc)
        val btnStepOem = view.findViewById<Button>(R.id.btnStepOem)
        val btnStepOemSkip = view.findViewById<Button>(R.id.btnStepOemSkip)

        val tvStepScreenStatus = view.findViewById<TextView>(R.id.tvStepScreenStatus)
        val btnStepScreen = view.findViewById<Button>(R.id.btnStepScreen)
        val btnScreenRestrictedHelp = view.findViewById<Button>(R.id.btnScreenRestrictedHelp)

        val cardRestrictedHint = view.findViewById<LinearLayout>(R.id.cardRestrictedHint)
        val btnOpenAppDetails = view.findViewById<Button>(R.id.btnOpenAppDetails)
        val tvPipelineDiagnostics = view.findViewById<TextView>(R.id.tvPipelineDiagnostics)

        fun styleBadge(tv: TextView, text: String, bgColorRes: Int, textColorRes: Int) {
            tv.text = text
            tv.setBackgroundColor(ContextCompat.getColor(this, bgColorRes))
            tv.setTextColor(ContextCompat.getColor(this, textColorRes))
        }

        val refreshCards: () -> Unit = {
            val notifGranted = PermissionRouter.isNotificationListenerGranted(this)
            val batteryExempt = PermissionRouter.isIgnoringBatteryOptimizations(this)
            val screenEnabled = PermissionRouter.isAccessibilityServiceEnabled(this)
            val oemGuide = PermissionRouter.detectOemGuide()
            val hasOemCandidates = oemGuide.candidates.isNotEmpty()
            val oemVisited = PermissionRouter.isOemAutoStartVisited(this)
            val oemAcknowledged = PermissionRouter.isOemStepAcknowledged(this)

            // Top one-tap relay button pointing directly to the next incomplete step
            val totalRelaySteps = if (hasOemCandidates) 3 else 2
            when (
                PermissionRouter.resolveNextPendingStep(
                    notifGranted = notifGranted,
                    batteryExempt = batteryExempt,
                    oemVisited = oemVisited,
                    hasOemCandidates = hasOemCandidates,
                    oemAcknowledged = oemAcknowledged
                )
            ) {
                NextPermissionStep.NOTIFY -> {
                    btnNextPendingStep.visibility = View.VISIBLE
                    btnNextPendingStep.text = "下一步 (1/$totalRelaySteps)：去开启通知使用权 →"
                    btnNextPendingStep.setOnClickListener { PermissionRouter.openNotificationListenerSettings(this) }
                }
                NextPermissionStep.BATTERY -> {
                    btnNextPendingStep.visibility = View.VISIBLE
                    btnNextPendingStep.text = "下一步 (2/$totalRelaySteps)：弹窗允许忽略电池优化 →"
                    btnNextPendingStep.setOnClickListener { PermissionRouter.requestIgnoreBatteryOptimizations(this) }
                }
                NextPermissionStep.OEM_AUTOSTART -> {
                    btnNextPendingStep.visibility = View.VISIBLE
                    btnNextPendingStep.text = "下一步 (3/3)：去设置${oemGuide.displayName}防杀后台 →"
                    btnNextPendingStep.setOnClickListener { btnStepOem.performClick() }
                }
                NextPermissionStep.ALL_READY -> {
                    btnNextPendingStep.visibility = View.GONE
                }
            }

            // Step 1: Notification Listener
            when {
                notifGranted && app.isListenerConnected -> {
                    styleBadge(tvStepNotifyStatus, "✓ 守护中", R.color.status_ok_bg, R.color.status_ok_text)
                    tvStepNotifyDesc.text = "通知监听已绑定在线，正在自动识别支付与动账通知。"
                    btnStepNotify.text = "已开启（点此可复查）"
                }
                notifGranted -> {
                    styleBadge(tvStepNotifyStatus, "待重连", R.color.status_warn_bg, R.color.status_warn_text)
                    tvStepNotifyDesc.text = "已授权但暂未绑定（省电或系统重启后常见），可进入先关再开恢复。"
                    btnStepNotify.text = "一键直达重连"
                }
                else -> {
                    styleBadge(tvStepNotifyStatus, "未开启", R.color.status_warn_bg, R.color.status_warn_text)
                    tvStepNotifyDesc.text = "自动记账必备底座，开启后即可后台离线记录微信、支付宝和银行通知。"
                    btnStepNotify.text = "一键直达通知使用权"
                }
            }

            // Step 2: Battery Optimization
            if (batteryExempt) {
                styleBadge(tvStepBatteryStatus, "✓ 已豁免", R.color.status_ok_bg, R.color.status_ok_text)
                btnStepBattery.text = "已豁免电池优化 ✓"
                btnStepBattery.isEnabled = false
            } else {
                styleBadge(tvStepBatteryStatus, "待豁免", R.color.status_warn_bg, R.color.status_warn_text)
                btnStepBattery.text = "应用内弹窗允许"
                btnStepBattery.isEnabled = true
            }

            // Step 3: OEM Auto-Start (distinguishes generic device, direct OEM opened, and manual skip/done)
            tvStepOemTitle.text = "3. ${oemGuide.displayName}保活（防杀后台）"
            tvStepOemDesc.text = oemGuide.stepTip
            when {
                !hasOemCandidates -> {
                    styleBadge(tvStepOemStatus, "此机型无需", R.color.status_opt_bg, R.color.status_opt_text)
                    btnStepOem.text = "查看应用详情（可选手动）"
                    btnStepOemSkip.visibility = View.GONE
                }
                oemVisited -> {
                    styleBadge(tvStepOemStatus, "✓ 已直达", R.color.status_ok_bg, R.color.status_ok_text)
                    btnStepOem.text = "复查${oemGuide.displayName}启动管理"
                    btnStepOemSkip.visibility = View.GONE
                }
                oemAcknowledged -> {
                    styleBadge(tvStepOemStatus, "已手动处理", R.color.status_ok_bg, R.color.status_ok_text)
                    btnStepOem.text = "复查${oemGuide.displayName}启动管理"
                    btnStepOemSkip.visibility = View.GONE
                }
                else -> {
                    styleBadge(tvStepOemStatus, "建议开启", R.color.status_warn_bg, R.color.status_warn_text)
                    btnStepOem.text = "一键直达${oemGuide.displayName}启动管理"
                    btnStepOemSkip.visibility = View.VISIBLE
                }
            }

            // Step 4: Optional Read-Only Accessibility
            when {
                screenEnabled && app.isAccessibilityConnected -> {
                    styleBadge(tvStepScreenStatus, "✓ 只读运行中", R.color.status_ok_bg, R.color.status_ok_text)
                    btnStepScreen.text = "已开启（点此可随时关闭）"
                }
                screenEnabled -> {
                    styleBadge(tvStepScreenStatus, "已开启·待绑定", R.color.status_warn_bg, R.color.status_warn_text)
                    btnStepScreen.text = "已开启（点此管理）"
                }
                else -> {
                    styleBadge(tvStepScreenStatus, "可选·已关", R.color.status_opt_bg, R.color.status_opt_text)
                    btnStepScreen.text = "自愿开启只读屏幕增强"
                }
            }

            // Show on-demand Restricted Settings helper under Step 4 on Android 13+ when Step 4 is off
            btnScreenRestrictedHelp.visibility = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !screenEnabled && notifGranted
            ) View.VISIBLE else View.GONE

            // Auto-show prominent Restricted Settings card ONLY when required Notification Listener is missing on Android 13+
            cardRestrictedHint.visibility = if (
                PermissionRouter.shouldAutoShowRestrictedBanner(notifGranted = notifGranted)
            ) View.VISIBLE else View.GONE

            // Live verifiable diagnostics footer (strictly metadata-only, zero raw screen text; refreshes on return)
            val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            val lastNotif = if (app.lastObservedEventAtMs > 0L) timeFmt.format(Date(app.lastObservedEventAtMs)) else "尚无"
            val lastScreen = if (app.lastScreenCaptureAtMs > 0L) timeFmt.format(Date(app.lastScreenCaptureAtMs)) else "尚无"
            val powerSaveText = if (app.isPowerSaveMode()) "省电模式开启" else "省电模式关闭"
            tvPipelineDiagnostics.text = "最近通知：$lastNotif · 最近屏幕补全：$lastScreen\n" +
                "屏幕事件：收到 ${app.screenEventsCount} 次（返回本页刷新） · ${app.lastScreenDiagSummary}\n" +
                "$powerSaveText · 上次退出：${app.getLastReadableExitReason()}"
        }

        btnStepNotify.setOnClickListener {
            PermissionRouter.openNotificationListenerSettings(this)
        }
        btnStepBattery.setOnClickListener {
            PermissionRouter.requestIgnoreBatteryOptimizations(this)
        }
        btnStepOem.setOnClickListener {
            val res = PermissionRouter.openOemAutoStartSettings(this)
            if (!res.usedDirectOemPage) {
                Toast.makeText(
                    this,
                    "当前系统未开放直达入口，已降级打开应用详情：${res.guide.stepTip}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, res.guide.stepTip, Toast.LENGTH_LONG).show()
            }
            refreshCards()
        }
        btnStepOemSkip.setOnClickListener {
            PermissionRouter.markOemStepAcknowledged(this)
            refreshCards()
        }
        btnStepScreen.setOnClickListener {
            PermissionRouter.openAccessibilitySettings(this)
        }
        btnScreenRestrictedHelp.setOnClickListener {
            PermissionRouter.openAppDetails(this)
            Toast.makeText(this, "点右上角 ⋮ → 允许受限设置，再返回开启无障碍", Toast.LENGTH_LONG).show()
        }
        btnOpenAppDetails.setOnClickListener {
            PermissionRouter.openAppDetails(this)
            Toast.makeText(this, "点右上角 ⋮ → 允许受限设置，再返回开启开关", Toast.LENGTH_LONG).show()
        }

        refreshCards()
        activePipelineRefresher = refreshCards

        AlertDialog.Builder(this)
            .setTitle("一键权限配置与运行状态")
            .setView(view)
            .setNegativeButton("完成") { _, _ ->
                activePipelineRefresher = null
                refreshUi()
            }
            .setOnDismissListener {
                activePipelineRefresher = null
            }
            .show()
    }

    private fun showTransactionDetailDialog(tx: Transaction) {
        screenScope.launch {
            val sources = app.engine.store.listSourcesForTransaction(tx.id)
            val recentChanges = app.engine.store.listChanges(30).filter { tx.id in it.affectedTxIds && it.opType.name != "UNDO" }
            val latestChange = recentChanges.firstOrNull()
            val detailText = buildString {
                appendLine("商户：${tx.merchant}")
                appendLine("金额：${MoneyMath.formatUnsignedMinor(tx.amountMinor, tx.currency, tx.scale)} (${tx.direction.name} / ${tx.status.name})")
                appendLine("修订号：v${tx.revision}")
                if (tx.settlementAmountMinor != null && tx.settlementCurrency != null) {
                    appendLine("外币折算：${if (tx.settlementIsEstimated) "估值 " else "结算 "}${MoneyMath.formatUnsignedMinor(tx.settlementAmountMinor, tx.settlementCurrency, tx.settlementScale)}")
                }
                appendLine("说明：${tx.aiReasoning}")
                appendLine()
                if (tx.legacyMissingSources) {
                    appendLine("来源证据：历史迁移记录（原合并 ${tx.legacyMergedCount} 条），缺少独立来源记录，不支持自动拆分。")
                } else if (sources.isEmpty()) {
                    appendLine("来源证据：无关联来源记录。")
                } else {
                    appendLine("来源证据（共 ${sources.size} 条）：")
                    sources.forEachIndexed { i, (src, link) ->
                        val cands = src.amountCandidates.joinToString(", ") { c ->
                            "${c.candidateId}:${MoneyMath.formatUnsignedMinor(c.amountMinor, c.currency, c.scale)}[${c.role.name}]"
                        }
                        appendLine("${i + 1}. ${src.appName} (${src.sourceType}) · 角色=${link.linkRole.name} · 候选=${link.candidateId ?: "-"}")
                        if (cands.isNotBlank()) appendLine("   提取候选：$cands")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                if (!tx.legacyMissingSources && sources.size >= 2) {
                    actions.add("拆分误合并来源（拆成 ${sources.size} 笔）" to {
                        app.appScope.launch {
                            val res = app.engine.splitMergedTransaction(tx.id, tx.revision)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    if (res != null) "已拆分为 ${res.first.size} 笔独立记录" else "拆分失败（记录可能已被修改）",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    })
                }
                if (tx.status != TxStatus.IGNORED) {
                    actions.add("标为误记/非交易（移出主账本）" to {
                        app.appScope.launch {
                            val ch = app.engine.markAsIgnored(tx.id, expectedRevision = tx.revision)
                            withContext(Dispatchers.Main) {
                                if (!isDestroyed && !isFinishing) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        if (ch != null) "已标为误记并移出主账本（可在内部/忽略中撤销）" else "操作失败（记录可能已被修改）",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    })
                    actions.add("标为内部互转（移入内部/忽略）" to {
                        app.appScope.launch { app.engine.markAsInternal(tx.id, expectedRevision = tx.revision) }
                    })
                }
                if (tx.direction == TxDirection.INCOME || tx.direction == TxDirection.EXPENSE) {
                    actions.add("标为 AA/报销冲抵" to {
                        app.appScope.launch { app.engine.markAsReimbursementOffset(tx.id, expectedRevision = tx.revision) }
                    })
                }
                if (latestChange != null) {
                    actions.add("撤销最近一次操作 (${latestChange.opType.name})" to {
                        app.appScope.launch {
                            val ok = app.engine.undoChange(latestChange.opId)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    if (ok) "已安全撤销操作" else "该记录已有更新编辑，无法撤销旧操作",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    })
                }
                actions.add(tx.deepLinkLabel.ifBlank { "查看原账单" } to { openDeepLink(tx) })

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("账单来源与可撤销操作")
                    .setMessage(detailText)
                    .setPositiveButton("更多操作") { _, _ ->
                        val labels = actions.map { it.first }.toTypedArray()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("选择操作")
                            .setItems(labels) { _, which -> actions[which].second() }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    private fun runBuiltInSelfTest() {
        val btnSelfTest = findViewById<Button>(R.id.btnSelfTest)
        if (!btnSelfTest.isEnabled) return
        btnSelfTest.isEnabled = false

        Toast.makeText(this, "已注入 3 组内置演示账单，约 2 秒结算...", Toast.LENGTH_SHORT).show()
        app.appScope.launch {
            try {
                val now = System.currentTimeMillis()
                app.engine.ingestNotification(
                    RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：向蜜雪冰城付款12.00元，商品：柠檬水", now)
                )
                app.engine.ingestNotification(
                    RawNotification("cmb.pb", "招商银行", "动账提醒", "您尾号6288储蓄卡消费人民币12.00元，商户：财付通-蜜雪冰城", now + 300L)
                )
                app.engine.ingestNotification(
                    RawNotification("cmb.pb", "招商银行", "银证转账", "您尾号6288账户银行转证券向华泰证券第三方存管转出10000.00元", now + 500L)
                )
                app.engine.ingestNotification(
                    RawNotification("com.tencent.mm", "微信", "微信支付分", "您已在拼多多0元下单，已使用先用后付，确认收货后自动扣款59.00元", now + 700L)
                )
                delay(1500L)
                app.engine.flushExpired(forceAll = true)
                withContext(Dispatchers.Main) {
                    refreshUi()
                    Toast.makeText(this@MainActivity, "自检完成！主账本应记 1 笔 ¥12.00，内部/忽略栏应拦 2 笔", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    btnSelfTest.isEnabled = true
                }
            }
        }
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etRealName = view.findViewById<EditText>(R.id.etRealName)
        val etAliases = view.findViewById<EditText>(R.id.etAliases)
        val etCardTails = view.findViewById<EditText>(R.id.etCardTails)
        val etBrokers = view.findViewById<EditText>(R.id.etBrokers)
        val btnExportBackup = view.findViewById<Button>(R.id.btnExportBackup)
        val btnRestoreBackup = view.findViewById<Button>(R.id.btnRestoreBackup)
        val tvAppVersionInfo = view.findViewById<TextView>(R.id.tvAppVersionInfo)
        val btnCheckUpdate = view.findViewById<Button>(R.id.btnCheckUpdate)

        val buildFlavorLabel = if (BuildConfig.DEBUG) "调试共存版(.debug)" else "正式签名版"
        tvAppVersionInfo.text = "当前版本：v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) · $buildFlavorLabel\n" +
            "更新源：GitHub Releases (${BuildConfig.GITHUB_REPO})，下载前强制校验 SHA-256。"

        val p = app.engine.profile
        etRealName.setText(p.realName)
        etAliases.setText(p.aliases.joinToString(","))
        etCardTails.setText(p.ownCardTails.joinToString(","))
        etBrokers.setText(p.brokers.joinToString(","))

        val dialog = AlertDialog.Builder(this)
            .setTitle("档案、备份与更新")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                fun splitCsv(s: String) = s.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                val newProfile = UserProfile(
                    realName = etRealName.text.toString().trim(),
                    aliases = splitCsv(etAliases.text.toString()),
                    ownCardTails = splitCsv(etCardTails.text.toString()),
                    brokers = splitCsv(etBrokers.text.toString()).ifEmpty { UserProfile().brokers }
                )
                app.appScope.launch {
                    app.engine.updateProfile(newProfile)
                    if (app.engine.aiConfig.mode != AiConfig.MODE_LOCAL) {
                        val localAi = AiConfig(mode = AiConfig.MODE_LOCAL)
                        app.engine.updateAiConfig(newConfig = localAi, persistConfig = localAi)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "已保存：自己人档案已更新（纯离线本地解析）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create()

        btnExportBackup.setOnClickListener {
            dialog.dismiss()
            promptPasswordDialog(
                title = "设置加密备份密码",
                hint = "采用 PBKDF2 + AES-256-GCM 本地加密，请妥善记住密码"
            ) { pwd ->
                pendingBackupPassword?.fill('\u0000')
                pendingBackupPassword = pwd
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                exportBackupLauncher.launch("wallet_watcher_backup_$stamp.wwbk")
            }
        }

        btnRestoreBackup.setOnClickListener {
            dialog.dismiss()
            restoreBackupLauncher.launch(arrayOf("*/*"))
        }

        btnCheckUpdate.setOnClickListener {
            dialog.dismiss()
            startManualUpdateCheck()
        }

        dialog.show()
    }

    private fun startManualUpdateCheck() {
        if (updateJob?.isActive == true) {
            Toast.makeText(this, "正在检查或下载更新，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("检查更新")
            .setMessage("正在连接 GitHub Releases（仅手动触发）…")
            .setNegativeButton("取消") { _, _ -> updateJob?.cancel() }
            .setCancelable(true)
            .setOnCancelListener { updateJob?.cancel() }
            .show()

        updateJob = screenScope.launch {
            val res = runCatching {
                AppUpdater.checkForUpdate(currentVersionName = BuildConfig.VERSION_NAME)
            }
            res.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            withContext(Dispatchers.Main) {
                if (isDestroyed || isFinishing) return@withContext
                runCatching { loadingDialog.dismiss() }
                res.onSuccess { outcome ->
                    when (outcome) {
                        is UpdateCheckResult.UpdateAvailable -> showUpdateAvailableDialog(outcome.release)
                        is UpdateCheckResult.UpToDate -> {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("已是最新版本")
                                .setMessage("当前版本 v${BuildConfig.VERSION_NAME} 已不低于 GitHub 最新 Release（${outcome.latestTag ?: "-"}）。")
                                .setPositiveButton("确定", null)
                                .setNeutralButton("浏览器查看 Releases") { _, _ ->
                                    openValidatedReleaseBrowser(AppUpdater.RELEASES_PAGE_URL)
                                }
                                .show()
                        }
                        UpdateCheckResult.NoReleasePublished -> {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("尚未发布正式 Release")
                                .setMessage("仓库 ${BuildConfig.GITHUB_REPO} 尚未找到包含 APK 与 checksums.txt 的 GitHub Release。配置好固定 Release 签名并推送 v* 标签后，即可在此一键检查并覆盖更新。")
                                .setPositiveButton("确定", null)
                                .setNeutralButton("打开 Releases 页") { _, _ ->
                                    openValidatedReleaseBrowser(AppUpdater.RELEASES_PAGE_URL)
                                }
                                .show()
                        }
                    }
                }.onFailure { err ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("检查更新失败")
                        .setMessage("无法从 GitHub 获取版本信息：${err.message ?: err.javaClass.simpleName}\n\n如当前网络直连 GitHub 受限，可尝试用浏览器打开 Releases 页面下载。")
                        .setPositiveButton("重试") { _, _ -> startManualUpdateCheck() }
                        .setNeutralButton("浏览器打开") { _, _ ->
                            openValidatedReleaseBrowser(AppUpdater.RELEASES_PAGE_URL)
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(release: ReleaseUpdateInfo) {
        val sizeText = if (release.apkSizeBytes > 0L) {
            String.format(Locale.CHINA, "%.2f MB", release.apkSizeBytes / (1024.0 * 1024.0))
        } else {
            "未知大小"
        }
        val canInstallInApp = AppUpdater.canInstallReleaseFromBuild(
            isDebugBuild = BuildConfig.DEBUG,
            trustedReleaseCertSha256Raw = BuildConfig.TRUSTED_RELEASE_CERT_SHA256
        )
        val buildNotice = when {
            !BuildConfig.DEBUG -> {
                "\n\n提示：将自动核对 checksums.txt 中的 SHA-256 与签名证书，验证通过后再拉起系统安装器保留数据覆盖更新。"
            }
            canInstallInApp -> {
                "\n\n提示：当前安装的是调试版（.debug），下载后会严格校验 SHA-256 与官方正式签名证书指纹，验证通过后作为独立正式应用安装共存（建议先导出加密备份）。"
            }
            else -> {
                "\n\n提示：当前调试版未内置官方正式签名证书指纹，为保证首次安装身份可信，应用内下载安装已禁用。请点击「浏览器打开发行页」核对 checksums.txt 与签名指纹后手动下载安装正式版。"
            }
        }
        val notesPreview = if (release.notes.isNotBlank()) {
            "\n\n更新说明：\n${release.notes.take(450)}"
        } else {
            ""
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("发现新版本：${release.tagName}")
            .setMessage("当前版本：v${BuildConfig.VERSION_NAME}\n最新版本：${release.tagName}（$sizeText）$buildNotice$notesPreview")
            .setNegativeButton("稍后", null)
        if (canInstallInApp) {
            builder.setPositiveButton("下载并校验安装") { _, _ ->
                startDownloadAndVerifyUpdate(release)
            }
            builder.setNeutralButton("浏览器打开") { _, _ ->
                openValidatedReleaseBrowser(release.htmlUrl)
            }
        } else {
            builder.setPositiveButton("浏览器打开发行页") { _, _ ->
                openValidatedReleaseBrowser(release.htmlUrl)
            }
        }
        builder.show()
    }

    private fun startDownloadAndVerifyUpdate(release: ReleaseUpdateInfo) {
        if (updateJob?.isActive == true) return
        val progressView = TextView(this).apply {
            setPadding(48, 32, 48, 16)
            text = "正在获取 checksums.txt 并下载 ${release.apkAssetName}…"
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("正在下载并校验 ${release.tagName}")
            .setView(progressView)
            .setNegativeButton("取消") { _, _ -> updateJob?.cancel() }
            .setCancelable(false)
            .show()

        var lastUiUpdateMs = 0L
        updateJob = screenScope.launch {
            val res = runCatching {
                val targetDir = AppUpdater.getUpdatesCacheDir(this@MainActivity)
                val (apkFile, sha256) = AppUpdater.downloadAndVerifyApk(
                    release = release,
                    targetDir = targetDir,
                    userAgentVersion = BuildConfig.VERSION_NAME,
                    shouldCancel = { !isActive }
                ) { downloaded, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateMs >= 180L || (total > 0L && downloaded >= total)) {
                        lastUiUpdateMs = now
                        val msg = if (total > 0L) {
                            val pct = ((downloaded * 100L) / total).coerceIn(0L, 100L)
                            "已下载 ${downloaded / 1024} KB / ${total / 1024} KB（$pct%），完成后自动校验 SHA-256…"
                        } else {
                            "已下载 ${downloaded / 1024} KB，完成后自动校验 SHA-256…"
                        }
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) progressView.text = msg
                        }
                    }
                }
                val (archive, preflight) = try {
                    val inspected = AppUpdater.inspectApkArchive(this@MainActivity, apkFile, sha256)
                    val installedCode = AppUpdater.getInstalledVersionCode(this@MainActivity)
                        .takeIf { it > 0L } ?: BuildConfig.VERSION_CODE.toLong()
                    val check = AppUpdater.evaluateInstallPreflight(
                        installedPackageName = packageName,
                        installedVersionCode = installedCode,
                        installedSigningCertSha256s = AppUpdater.getInstalledSigningCertSha256s(this@MainActivity),
                        archive = inspected,
                        trustedReleaseCertSha256s = AppUpdater.parseTrustedCertFingerprints(
                            BuildConfig.TRUSTED_RELEASE_CERT_SHA256
                        )
                    )
                    if (check is ApkInstallPreflight.Blocked) {
                        runCatching { apkFile.delete() }
                    }
                    inspected to check
                } catch (t: Throwable) {
                    runCatching { apkFile.delete() }
                    throw t
                }
                archive to preflight
            }
            if (!isActive) return@launch
            res.exceptionOrNull()?.let { if (it is CancellationException) throw it }

            withContext(Dispatchers.Main) {
                if (isDestroyed || isFinishing) return@withContext
                runCatching { progressDialog.dismiss() }
                res.onSuccess { (archive, preflight) ->
                    when (preflight) {
                        is ApkInstallPreflight.Blocked -> {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("已阻止安装")
                                .setMessage(preflight.reason)
                                .setPositiveButton("知道了", null)
                                .show()
                        }
                        is ApkInstallPreflight.ReadyToInstall -> {
                            launchOrRequestInstallPermission(archive)
                        }
                    }
                }.onFailure { err ->
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("下载或校验失败")
                        .setMessage("${err.message ?: err.javaClass.simpleName}\n\n可改用浏览器手动打开 GitHub Releases 下载核对。")
                        .setPositiveButton("浏览器打开") { _, _ ->
                            openValidatedReleaseBrowser(release.htmlUrl)
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }
    }

    private fun launchOrRequestInstallPermission(archive: VerifiedApkArchive) {
        if (!AppUpdater.canRequestPackageInstalls(this)) {
            pendingInstallArchive = archive
            AlertDialog.Builder(this)
                .setTitle("需要允许安装应用更新")
                .setMessage("安装包已通过 SHA-256 校验（${archive.sha256Hex.take(12)}…）。首次在应用内更新需在系统设置中开启「允许来自此来源的应用」，开启后返回即可继续安装。")
                .setPositiveButton("前往开启") { _, _ ->
                    runCatching { startActivity(AppUpdater.buildUnknownAppSourcesIntent(this)) }
                        .onFailure {
                            Toast.makeText(this, "无法打开安装权限设置，请在系统设置中手动授权", Toast.LENGTH_LONG).show()
                        }
                }
                .setNegativeButton("取消") { _, _ ->
                    pendingInstallArchive = null
                }
                .show()
            return
        }
        pendingInstallArchive = null
        launchVerifiedApkInstaller(archive)
    }

    private fun resumePendingApkInstallIfAuthorized() {
        val pending = pendingInstallArchive ?: return
        if (!AppUpdater.canRequestPackageInstalls(this)) return
        pendingInstallArchive = null
        screenScope.launch {
            val valid = runCatching {
                val updatesDir = AppUpdater.getUpdatesCacheDir(this@MainActivity).canonicalFile
                val canonicalFile = pending.file.canonicalFile
                if (!canonicalFile.isFile || canonicalFile.parentFile != updatesDir) {
                    false
                } else {
                    val recomputed = AppUpdater.computeFileSha256(canonicalFile).lowercase(Locale.US)
                    java.security.MessageDigest.isEqual(
                        recomputed.toByteArray(Charsets.US_ASCII),
                        pending.sha256Hex.lowercase(Locale.US).toByteArray(Charsets.US_ASCII)
                    )
                }
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (isDestroyed || isFinishing) return@withContext
                if (valid) {
                    launchVerifiedApkInstaller(pending)
                } else {
                    runCatching { pending.file.delete() }
                    Toast.makeText(this@MainActivity, "待安装缓存文件已失效，请重新检查更新", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchVerifiedApkInstaller(archive: VerifiedApkArchive) {
        runCatching {
            val intent = AppUpdater.buildInstallApkIntent(this, archive.file)
            startActivity(intent)
        }.onFailure { err ->
            Toast.makeText(this, "无法拉起系统安装器：${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openValidatedReleaseBrowser(url: String) {
        val safeUrl = if (AppUpdater.isAllowedReleaseHtmlUrl(url)) url else AppUpdater.RELEASES_PAGE_URL
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
        }.onFailure {
            Toast.makeText(this, "未找到可用浏览器打开更新页面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun promptPasswordDialog(title: String, hint: String, onConfirm: (CharArray) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            this.hint = hint
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val raw = input.text.toString()
                if (raw.length < 4) {
                    Toast.makeText(this, "密码至少需要 4 位字符", Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(raw.toCharArray())
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRescueDialog(tx: Transaction) {
        AlertDialog.Builder(this)
            .setTitle("把这笔记录捞回主账本？")
            .setMessage("${tx.merchant} ${MoneyMath.formatUnsignedMinor(tx.amountMinor, tx.currency, tx.scale)}\n请选择它实际应该属于哪种类型：")
            .setPositiveButton("记为真实支出") { _, _ ->
                app.appScope.launch { app.engine.rescueIgnored(tx.id, TxDirection.EXPENSE, tx.revision) }
            }
            .setNeutralButton("记为真实收入") { _, _ ->
                app.appScope.launch { app.engine.rescueIgnored(tx.id, TxDirection.INCOME, tx.revision) }
            }
            .setNegativeButton("查看来源") { _, _ ->
                showTransactionDetailDialog(tx)
            }
            .show()
    }

    private fun openDeepLink(tx: Transaction) {
        val link = tx.deepLink.trim()
        if (link == "walletwatcher://launch/${ScreenBillParser.PKG_WECHAT}" || link.startsWith("weixin://")) {
            if (PermissionRouter.openWeChatApp(this)) {
                Toast.makeText(
                    this,
                    "已打开微信，请依次点击：我 → 服务 → 钱包 → 账单",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "未检测到已安装的微信 App", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (link.startsWith("walletwatcher://launch/")) {
            val targetPkg = link.removePrefix("walletwatcher://launch/").trim()
            val launchIntent = runCatching { packageManager.getLaunchIntentForPackage(targetPkg) }.getOrNull()
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(launchIntent) }
            } else {
                Toast.makeText(this, "未能拉起目标 App（可能未安装）", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (link.isBlank() || link.startsWith("walletwatcher://")) {
            Toast.makeText(this, "脱敏摘要：\n${tx.sanitizedText.ifBlank { tx.aiReasoning }}", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }.onFailure {
            Toast.makeText(this, "未能拉起目标 App（可能未安装）", Toast.LENGTH_SHORT).show()
        }
    }
}
