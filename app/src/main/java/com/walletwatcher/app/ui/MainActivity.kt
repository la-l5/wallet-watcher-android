package com.walletwatcher.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.walletwatcher.app.R
import com.walletwatcher.app.WalletApp
import com.walletwatcher.app.core.AiConfig
import com.walletwatcher.app.core.RawNotification
import com.walletwatcher.app.core.Transaction
import com.walletwatcher.app.core.TxDirection
import com.walletwatcher.app.core.TxStatus
import com.walletwatcher.app.core.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val app: WalletApp
        get() = application as WalletApp

    private enum class Tab { LEDGER, IGNORED, LOGS }
    private var currentTab = Tab.LEDGER

    private lateinit var tvServiceStatus: TextView
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

    private val engineListener: () -> Unit = {
        runOnUiThread { refreshUi() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupListeners()
        requestPostNotificationIfNeeded()
        app.engine.addOnChangeListener(engineListener)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        app.engine.removeOnChangeListener(engineListener)
        refreshJob?.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
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
            onDeepLinkClick = { tx -> openDeepLink(tx) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnGrantNotify).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnBatteryGuard).setOnClickListener {
            showHonorGuardDialog()
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
            val text = app.engine.exportSanitizedDebugSamples()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("wallet_samples", text))
            Toast.makeText(this, "已复制脱敏通知样本，可直接粘贴发给 AI 调试规则", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空所有本地记录？")
                .setMessage("仅清空账单和抓包日志，不会清空你的个人档案和 API 配置。")
                .setPositiveButton("清空") { _, _ ->
                    app.engine.store.clearLedgerAndLogs()
                    refreshUi()
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
        val granted = isNotificationListenerGranted()
        val connText = when {
            !granted -> "未授权（请先点“开通知权限”）"
            app.isListenerConnected -> "已连接守护中"
            else -> "已授权，等待系统绑定（可开关一次权限激活）"
        }
        val netText = if (app.checkNetworkAvailable()) "在线" else "断网暂存"
        val bufCount = app.engine.activeBufferCount()
        tvServiceStatus.text = "监听：$connText | 网络：$netText | AI：${app.engine.aiConfig.displayLabel}" +
            if (bufCount > 0) " | 缓冲中($bufCount)" else ""

        refreshJob?.cancel()
        val tab = currentTab
        refreshJob = app.appScope.launch {
            val summary = app.engine.getSummary()
            val rows: List<ListRow> = when (tab) {
                Tab.LEDGER -> app.engine.store.listTransactions()
                    .filter { it.status == TxStatus.RECORDED || it.status == TxStatus.NEEDS_REVIEW }
                    .map { ListRow.TxRow(it, isIgnoredTab = false) }
                Tab.IGNORED -> app.engine.store.listTransactions()
                    .filter { it.status == TxStatus.IGNORED }
                    .map { ListRow.TxRow(it, isIgnoredTab = true) }
                Tab.LOGS -> app.engine.store.listLogs(120).map { ListRow.LogRow(it) }
            }

            withContext(Dispatchers.Main) {
                tvNetExpense.text = fmtMoney(summary.netExpense)
                tvIncome.text = fmtMoney(summary.totalIncome)
                tvBalance.text = fmtMoney(summary.netBalance)
                val dbKb = String.format(Locale.CHINA, "%.1f", summary.dbSizeBytes / 1024.0)
                tvSubStats.text = "总支出 ${fmtMoney(summary.grossExpense)} · 退款冲抵 ${fmtMoney(summary.totalRefund)} · 拦截内部 ${summary.ignoredCount} 笔 · 断网队列 ${summary.offlineQueueCount} · 本地库 ${dbKb}KB"

                tabLedger.text = "主账本(${summary.recordedCount})"
                tabIgnored.text = "已忽略(${summary.ignoredCount})"

                adapter.submitList(rows)
                tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun isNotificationListenerGranted(): Boolean {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this)
        return packageName in enabled
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

    private fun showHonorGuardDialog() {
        AlertDialog.Builder(this)
            .setTitle("荣耀 MagicOS 后台保活 4 步清单")
            .setMessage(
                "为防止荣耀系统锁屏杀后台导致漏单，请完成以下设置：\n\n" +
                    "1. 点击下方“打开应用详情” -> 耗电管理/应用启动管理 -> 改为“手动管理”，全开【允许自启动、允许关联启动、允许后台活动】。\n" +
                    "2. 打开系统最近任务列表，找到本 App 卡片并【下拉加锁】。\n" +
                    "3. 确保微信/支付宝/银行 App 开启了动账推送（建议银行 App 调成 0.01 元起步推送），且微信支付服务号未静音。\n" +
                    "4. 点击“忽略电池优化”放入系统省电白名单。"
            )
            .setPositiveButton("打开应用详情") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                runCatching { startActivity(intent) }
            }
            .setNeutralButton("忽略电池优化") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                runCatching { startActivity(intent) }
                    .onFailure {
                        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun runBuiltInSelfTest() {
        Toast.makeText(this, "已注入 3 组内置模拟通知（防重合并 + 银证拦截 + 先用后付0元），约 2 秒结算...", Toast.LENGTH_SHORT).show()
        app.appScope.launch {
            val now = System.currentTimeMillis()
            // 1. WeChat + CMB concurrent notifications (should merge into 1 expense ¥12.00)
            app.engine.ingestNotification(
                RawNotification("com.tencent.mm", "微信", "微信支付", "微信支付凭证：向蜜雪冰城付款12.00元，商品：柠檬水", now)
            )
            app.engine.ingestNotification(
                RawNotification("cmb.pb", "招商银行", "动账提醒", "您尾号6288储蓄卡消费人民币12.00元，商户：财付通-蜜雪冰城", now + 300L)
            )
            // 2. Bank -> Securities (should be ignored as internal transfer)
            app.engine.ingestNotification(
                RawNotification("cmb.pb", "招商银行", "银证转账", "您尾号6288账户银行转证券向华泰证券第三方存管转出10000.00元", now + 500L)
            )
            // 3. Pay-later 0-yuan order (should be ignored)
            app.engine.ingestNotification(
                RawNotification("com.tencent.mm", "微信", "微信支付分", "您已在拼多多0元下单，已使用先用后付，确认收货后自动扣款59.00元", now + 700L)
            )
            delay(1500L)
            app.engine.flushExpired(forceAll = true)
            withContext(Dispatchers.Main) {
                refreshUi()
                Toast.makeText(this@MainActivity, "自检完成！主账本应记 1 笔 ¥12.00，已忽略栏应拦 2 笔", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etRealName = view.findViewById<EditText>(R.id.etRealName)
        val etAliases = view.findViewById<EditText>(R.id.etAliases)
        val etCardTails = view.findViewById<EditText>(R.id.etCardTails)
        val etBrokers = view.findViewById<EditText>(R.id.etBrokers)
        val cbUseRealApi = view.findViewById<CheckBox>(R.id.cbUseRealApi)
        val etBaseUrl = view.findViewById<EditText>(R.id.etBaseUrl)
        val etModel = view.findViewById<EditText>(R.id.etModel)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)

        val p = app.engine.profile
        etRealName.setText(p.realName)
        etAliases.setText(p.aliases.joinToString(","))
        etCardTails.setText(p.ownCardTails.joinToString(","))
        etBrokers.setText(p.brokers.joinToString(","))

        val ai = app.engine.aiConfig
        cbUseRealApi.isChecked = ai.mode == AiConfig.MODE_OPENAI_COMPATIBLE
        etBaseUrl.setText(ai.baseUrl)
        etModel.setText(ai.model)
        etApiKey.setText(ai.apiKey)

        AlertDialog.Builder(this)
            .setTitle("个人档案 & AI 接口设置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                fun splitCsv(s: String) = s.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                app.engine.updateProfile(
                    UserProfile(
                        realName = etRealName.text.toString().trim(),
                        aliases = splitCsv(etAliases.text.toString()),
                        ownCardTails = splitCsv(etCardTails.text.toString()),
                        brokers = splitCsv(etBrokers.text.toString()).ifEmpty { UserProfile().brokers }
                    )
                )
                app.engine.updateAiConfig(
                    AiConfig(
                        mode = if (cbUseRealApi.isChecked) AiConfig.MODE_OPENAI_COMPATIBLE else AiConfig.MODE_MOCK,
                        baseUrl = etBaseUrl.text.toString().trim().ifEmpty { "https://api.deepseek.com/v1" },
                        model = etModel.text.toString().trim().ifEmpty { "deepseek-chat" },
                        apiKey = etApiKey.text.toString().trim()
                    )
                )
                Toast.makeText(this, "设置已保存到本地", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRescueDialog(tx: Transaction) {
        AlertDialog.Builder(this)
            .setTitle("把这笔记录捞回主账本？")
            .setMessage("${tx.merchant} ¥${String.format(Locale.CHINA, "%.2f", tx.amount)}\n请选择它实际应该属于支出还是收入：")
            .setPositiveButton("记为真实支出") { _, _ ->
                app.engine.rescueIgnored(tx.id, TxDirection.EXPENSE)
                refreshUi()
            }
            .setNeutralButton("记为真实收入") { _, _ ->
                app.engine.rescueIgnored(tx.id, TxDirection.INCOME)
                refreshUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openDeepLink(tx: Transaction) {
        if (tx.deepLink.isBlank() || tx.deepLink.startsWith("walletwatcher://")) {
            Toast.makeText(this, "原始脱敏记录：\n${tx.sanitizedText}", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tx.deepLink)))
        }.onFailure {
            Toast.makeText(this, "未能拉起目标 App（可能未安装），脱敏原文已在抓包日志中保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fmtMoney(v: Double): String = "¥${String.format(Locale.CHINA, "%.2f", v)}"
}
