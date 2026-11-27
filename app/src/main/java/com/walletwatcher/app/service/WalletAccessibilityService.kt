package com.walletwatcher.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.walletwatcher.app.WalletApp
import com.walletwatcher.app.core.AccessibilitySnapshotCollector
import com.walletwatcher.app.core.AccessibleNode
import com.walletwatcher.app.core.ScreenBillParser
import com.walletwatcher.app.core.ScreenCaptureDeduplicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class ScreenProcessOutcome(
    val accepted: Boolean,
    val stage: String,
    val windowClassBucket: String,
    val visitedNodes: Int,
    val textCount: Int,
    val elapsedMs: Long
)

internal data class TrailingScanToken(
    val pkg: String,
    val expectedWindowId: Int,
    val expectedSessionId: Long,
    val eventSeq: Long = 0L,
    val expectedBindGen: Long = 0L
)

/**
 * Strictly read-only, opt-in AccessibilityService for WeChat and Alipay payment/receipt/bill detail pages.
 * - XML packageNames locks OS delivery to com.tencent.mm and com.eg.android.AlipayGphone only.
 * - Zero gesture/click permissions (canPerformGestures="false").
 * - Runs root resolution, bounded tree traversal, and parsing on a single-thread background executor
 *   with coalesced trailing settle rescans so Binder IPC never stalls the main thread.
 * - Binds trailing rescans to (pkg, expectedWindowId, expectedSessionId) and strictly verifies the active
 *   window + session before traversing or ingesting (never falls back to an inactive window when active root is null).
 * - Aborts immediately on password fields, keyboards, login screens, or chat windows.
 * - Emits only closed-set, metadata-only diagnostics with global monotonic rate limiting (ZERO raw text or external strings).
 */
class WalletAccessibilityService : AccessibilityService() {

    private val app: WalletApp
        get() = application as WalletApp

    private val appOrNull: WalletApp?
        get() = application as? WalletApp

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val scanExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ww-accessibility-scan").apply { isDaemon = true }
    }
    private val scanInFlight = AtomicBoolean(false)
    private val eventSeqCounter = AtomicLong(0L)
    private val deduplicator = ScreenCaptureDeduplicator()
    private val lifecycleLock = Any()
    @Volatile
    private var serviceBound: Boolean = false
    @Volatile
    private var bindGeneration: Long = 0L
    private var ingestJob: CompletableJob = SupervisorJob()

    private val windowLock = Any()
    private val windowClassById = LinkedHashMap<Int, String>()
    private val lastUnknownWindowClassByPackage = LinkedHashMap<String, String>()
    private var activeWindowSignature: Pair<Int, String>? = null
    private var windowSessionCounter: Long = 0L

    private val scheduleLock = Any()
    private var pendingRescanRunnable: Runnable? = null
    private var pendingRescanToken: TrailingScanToken? = null
    private var latestAcceptedEventSeq: Long = 0L

    private val diagLock = Any()
    private var lastGlobalDiagAtMs: Long = Long.MIN_VALUE
    private val lastDiagByKeyAtMs = LinkedHashMap<String, Long>()

    @Volatile
    private var lastScanMonotonicMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        markBoundLifecycle()
        app.isAccessibilityConnected = true
        app.engine.store.addLog(
            "ACCESSIBILITY",
            "只读屏幕增强已开启（仅监听微信/支付宝支付、收款与账单详情页，无模拟点击权限）"
        )
        app.notifyServiceStateChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handleUnbindLifecycle()
        appOrNull?.let {
            it.isAccessibilityConnected = false
            it.engine.store.addLog("ACCESSIBILITY", "只读屏幕增强服务已关闭或断开")
            it.notifyServiceStateChanged()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handleUnbindLifecycle()
        runCatching { scanExecutor.shutdownNow() }
        appOrNull?.let {
            it.isAccessibilityConnected = false
            it.notifyServiceStateChanged()
        }
        super.onDestroy()
    }

    internal fun markBoundLifecycle(): Long {
        return synchronized(lifecycleLock) {
            ingestJob.cancel()
            ingestJob = SupervisorJob()
            bindGeneration++
            serviceBound = true
            bindGeneration
        }
    }

    internal fun handleUnbindLifecycle() {
        cancelTrailingScan()
        synchronized(lifecycleLock) {
            serviceBound = false
            bindGeneration++
            ingestJob.cancel()
        }
        synchronized(windowLock) {
            windowClassById.clear()
            lastUnknownWindowClassByPackage.clear()
            activeWindowSignature = null
            windowSessionCounter++
        }
    }

    override fun onInterrupt() {
        // Read-only observer; nothing to interrupt.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val eventType = event.eventType

        if (pkg !in ScreenBillParser.ALLOWED_PACKAGES) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                cancelTrailingScan()
                synchronized(windowLock) {
                    lastUnknownWindowClassByPackage.clear()
                    activeWindowSignature = null
                    windowSessionCounter++
                }
            }
            return
        }

        val eventWinId = event.windowId
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString()
            if (!cls.isNullOrBlank() && !isTransientWidgetClass(cls)) {
                recordWindowState(pkg, eventWinId, cls)
            }
        } else if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        appOrNull?.let { it.screenEventsCount++ }

        val seq = eventSeqCounter.incrementAndGet()
        synchronized(scheduleLock) {
            if (seq > latestAcceptedEventSeq) {
                latestAcceptedEventSeq = seq
            }
        }
        val sessionAtEvent = currentSessionId()
        val bindGenAtEvent = currentBindGeneration()
        val token = TrailingScanToken(
            pkg = pkg,
            expectedWindowId = eventWinId,
            expectedSessionId = sessionAtEvent,
            eventSeq = seq,
            expectedBindGen = bindGenAtEvent
        )
        val monoNow = SystemClock.elapsedRealtime()
        val elapsedSinceScan = monoNow - lastScanMonotonicMs

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && elapsedSinceScan < CONTENT_SCAN_THROTTLE_MS) {
            scheduleTrailingScanOnMain(
                delayMs = (CONTENT_SCAN_THROTTLE_MS - elapsedSinceScan).coerceAtLeast(80L),
                token = token
            )
            return
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && elapsedSinceScan < STATE_SCAN_MIN_INTERVAL_MS) {
            scheduleTrailingScanOnMain(delayMs = STATE_SCAN_MIN_INTERVAL_MS, token = token)
            return
        }

        cancelTrailingScan()
        dispatchBackgroundScan(
            token = token,
            isTrailing = false,
            scheduleSettleOnMiss = (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventWinId != -1)
        )
    }

    private fun currentSessionId(): Long = synchronized(windowLock) { windowSessionCounter }

    private fun currentBindGeneration(): Long = synchronized(lifecycleLock) { bindGeneration }

    private fun isBindGenerationActive(expectedBindGen: Long): Boolean {
        if (expectedBindGen == 0L) return true
        return synchronized(lifecycleLock) { serviceBound && bindGeneration == expectedBindGen }
    }

    private fun tryAcceptTrailingTokenLocked(token: TrailingScanToken): Boolean {
        if (token.expectedWindowId == -1) return false
        if (!isBindGenerationActive(token.expectedBindGen)) return false
        if (currentSessionId() != token.expectedSessionId) return false
        if (token.eventSeq < latestAcceptedEventSeq) return false
        val existing = pendingRescanToken
        if (existing != null &&
            (existing.expectedSessionId > token.expectedSessionId ||
                existing.eventSeq > token.eventSeq)
        ) {
            return false
        }
        if (token.eventSeq > latestAcceptedEventSeq) {
            latestAcceptedEventSeq = token.eventSeq
        }
        return true
    }

    /**
     * Atomically validates that a trailing scan token has a valid windowId, still matches the active session
     * & bind generation, and is NOT older than either the latest event or any already-pending rescan token.
     */
    internal fun tryAcceptTrailingToken(token: TrailingScanToken): Boolean {
        return synchronized(scheduleLock) {
            if (!tryAcceptTrailingTokenLocked(token)) return@synchronized false
            pendingRescanToken = token
            true
        }
    }

    internal fun currentPendingTrailingTokenForTest(): TrailingScanToken? {
        return synchronized(scheduleLock) { pendingRescanToken }
    }

    private fun scheduleTrailingScanOnMain(delayMs: Long, token: TrailingScanToken) {
        // Delayed trailing rescans strictly require a known triggering windowId
        if (token.expectedWindowId == -1) return
        val scheduleAction = Runnable {
            synchronized(scheduleLock) {
                if (!tryAcceptTrailingTokenLocked(token)) return@Runnable
                pendingRescanRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingRescanToken = token
                val runnable = Runnable {
                    val shouldRun = synchronized(scheduleLock) {
                        if (pendingRescanToken == token) {
                            pendingRescanRunnable = null
                            pendingRescanToken = null
                            true
                        } else {
                            false
                        }
                    }
                    if (!shouldRun) return@Runnable
                    if (!isBindGenerationActive(token.expectedBindGen)) return@Runnable
                    if (currentSessionId() != token.expectedSessionId) return@Runnable
                    dispatchBackgroundScan(
                        token = token,
                        isTrailing = true,
                        scheduleSettleOnMiss = false
                    )
                }
                pendingRescanRunnable = runnable
                mainHandler.postDelayed(runnable, delayMs)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scheduleAction.run()
        } else {
            mainHandler.post(scheduleAction)
        }
    }

    private fun cancelTrailingScan() {
        synchronized(scheduleLock) {
            pendingRescanRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingRescanRunnable = null
            pendingRescanToken = null
        }
    }

    private fun dispatchBackgroundScan(
        token: TrailingScanToken,
        isTrailing: Boolean,
        scheduleSettleOnMiss: Boolean
    ) {
        if (!scanInFlight.compareAndSet(false, true)) {
            // Another scan is currently running on the single worker thread: coalesce into one trailing scan
            scheduleTrailingScanOnMain(delayMs = CONTENT_SCAN_THROTTLE_MS, token = token)
            return
        }
        lastScanMonotonicMs = SystemClock.elapsedRealtime()
        val submitted = runCatching {
            scanExecutor.execute {
                var shouldScheduleSettle = false
                try {
                    val outcome = scanLiveWindowWorker(token = token, isTrailing = isTrailing)
                    shouldScheduleSettle = scheduleSettleOnMiss &&
                        token.expectedWindowId != -1 &&
                        !outcome.accepted &&
                        outcome.stage != "blocked_window_class" &&
                        outcome.stage != "password_field" &&
                        outcome.stage != "stale_session" &&
                        isBindGenerationActive(token.expectedBindGen) &&
                        currentSessionId() == token.expectedSessionId
                } finally {
                    scanInFlight.set(false)
                }
                if (shouldScheduleSettle) {
                    // Routed back to main thread and ordered atomically under scheduleLock so an older scan
                    // can NEVER cancel or replace a newer window/event's scheduled rescan.
                    scheduleTrailingScanOnMain(delayMs = SETTLE_RESCAN_DELAY_MS, token = token)
                }
            }
            true
        }.getOrDefault(false)
        if (!submitted) {
            scanInFlight.set(false)
        }
    }

    private fun scanLiveWindowWorker(
        token: TrailingScanToken,
        isTrailing: Boolean
    ): ScreenProcessOutcome {
        val nowWallMs = System.currentTimeMillis()
        val nowMonoMs = SystemClock.elapsedRealtime()

        if (!isBindGenerationActive(token.expectedBindGen) || currentSessionId() != token.expectedSessionId) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "stale_session",
                windowClassBucket = "None",
                visitedNodes = 0,
                textCount = 0,
                elapsedMs = 0L
            )
        }

        if (isTrailing && token.expectedWindowId == -1) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "trailing_unknown_window_id",
                windowClassBucket = "None",
                visitedNodes = 0,
                textCount = 0,
                elapsedMs = 0L
            )
        }

        val root = resolveActiveTargetRoot(
            eventPackage = token.pkg,
            expectedWindowId = token.expectedWindowId,
            isTrailing = isTrailing
        )
        if (root == null) {
            val outcome = ScreenProcessOutcome(
                accepted = false,
                stage = if (isTrailing) "trailing_inactive_or_null" else "null_root",
                windowClassBucket = "None",
                visitedNodes = 0,
                textCount = 0,
                elapsedMs = 0L
            )
            if (!isTrailing) {
                recordLiveDiagnostics(token.pkg, outcome, nowMonoMs)
            }
            return outcome
        }

        val wrappedRoot = AndroidAccessibleNode(root)
        return try {
            val outcome = evaluateBoundRoot(
                token = token,
                rootNode = wrappedRoot,
                isTrailing = isTrailing,
                nowMs = nowWallMs
            )
            if (outcome.stage != "left_target_app" &&
                outcome.stage != "stale_session" &&
                outcome.stage != "trailing_unknown_window_id"
            ) {
                recordLiveDiagnostics(token.pkg, outcome, nowMonoMs)
            }
            outcome
        } finally {
            wrappedRoot.recycleSafely()
        }
    }

    /**
     * Resolves ONLY an active window root matching the triggering package & windowId.
     * - Trailing rescans strictly require a valid expectedWindowId (!= -1) and an active matching root
     *   (never fall back to inactive background windows or unknown windowIds).
     * - On API 26-32, every AccessibilityWindowInfo returned by `windows` is recycled in a `finally` block.
     */
    private fun resolveActiveTargetRoot(
        eventPackage: String,
        expectedWindowId: Int,
        isTrailing: Boolean
    ): AccessibilityNodeInfo? {
        if (isTrailing && expectedWindowId == -1) return null
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            val activePkg = active.packageName?.toString()
            if (isTrailing && activePkg != eventPackage) {
                recycleNodeSafely(active)
                return null
            }
            if ((!isTrailing && expectedWindowId == -1) || active.windowId == expectedWindowId) {
                return active
            }
            // Active root has a different windowId than the event: only accept an explicitly active matching window
            val matchedActive = findActiveWindowRootById(eventPackage, expectedWindowId)
            if (matchedActive != null) {
                recycleNodeSafely(active)
                return matchedActive
            }
            if (isTrailing) {
                recycleNodeSafely(active)
                return null
            }
            return active
        }

        // When rootInActiveWindow is null: trailing rescans fail closed; immediate scans only accept an explicitly active window with matching ID
        if (isTrailing || expectedWindowId == -1) return null
        return findActiveWindowRootById(eventPackage, expectedWindowId)
    }

    private fun findActiveWindowRootById(
        eventPackage: String,
        expectedWindowId: Int
    ): AccessibilityNodeInfo? {
        if (expectedWindowId == -1) return null
        val winList = runCatching { windows }.getOrNull() ?: return null
        var matched: AccessibilityNodeInfo? = null
        try {
            for (w in winList) {
                if (matched != null) continue
                if (!w.isActive) continue
                val r = runCatching { w.root }.getOrNull() ?: continue
                val rPkg = r.packageName?.toString()
                if (rPkg == eventPackage && (w.id == expectedWindowId || r.windowId == expectedWindowId)) {
                    matched = r
                } else {
                    recycleNodeSafely(r)
                }
            }
        } finally {
            for (w in winList) {
                recycleWindowSafely(w)
            }
        }
        return matched
    }

    @Suppress("DEPRECATION")
    private fun recycleNodeSafely(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { node.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleWindowSafely(win: AccessibilityWindowInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { win.recycle() }
        }
    }

    /**
     * Pure testable trailing/bound-root verifier:
     * - Rejects immediately if session changed or active root is null / switched to another app / different windowId.
     * - Delegates to processRootNodeDetailed which re-verifies session before ingest.
     */
    internal fun evaluateBoundRoot(
        token: TrailingScanToken,
        rootNode: AccessibleNode?,
        isTrailing: Boolean,
        nowMs: Long,
        onCaptureAccepted: ((com.walletwatcher.app.core.ScreenBillCapture, Long) -> Unit)? = null
    ): ScreenProcessOutcome {
        if (!isBindGenerationActive(token.expectedBindGen) || currentSessionId() != token.expectedSessionId) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "stale_session",
                windowClassBucket = "None",
                visitedNodes = 0,
                textCount = 0,
                elapsedMs = 0L
            )
        }
        if (isTrailing && token.expectedWindowId == -1) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "trailing_unknown_window_id",
                windowClassBucket = "None",
                visitedNodes = 0,
                textCount = 0,
                elapsedMs = 0L
            )
        }
        if (rootNode == null) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = if (isTrailing) "trailing_inactive_or_null" else "null_root",
                windowClassBucket = "None",
                visitedNodes = 0,
                textCount = 0,
                elapsedMs = 0L
            )
        }
        if (isTrailing && rootNode.packageName != token.pkg) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "left_target_app",
                windowClassBucket = "None",
                visitedNodes = 0,
                textCount = 0,
                elapsedMs = 0L
            )
        }
        if (token.expectedWindowId != -1 && (rootNode.windowId == -1 || rootNode.windowId != token.expectedWindowId)) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "window_id_mismatch",
                windowClassBucket = "None",
                visitedNodes = 0,
                textCount = 0,
                elapsedMs = 0L
            )
        }
        return processRootNodeDetailed(
            eventPackage = token.pkg,
            eventWindowId = token.expectedWindowId,
            rootNode = rootNode,
            nowMs = nowMs,
            expectedSessionId = token.expectedSessionId,
            expectedBindGen = token.expectedBindGen,
            onCaptureAccepted = onCaptureAccepted
        )
    }

    internal fun recordDiagnosticsToTargets(
        pkg: String,
        outcome: ScreenProcessOutcome,
        nowMonoMs: Long,
        updateSummary: (String) -> Unit,
        writeStoreLog: (String) -> Unit
    ): Boolean {
        val appLabel = if (pkg == ScreenBillParser.PKG_WECHAT) "微信" else "支付宝"
        val bucketPart = if (outcome.windowClassBucket != "None") " ${outcome.windowClassBucket}" else ""
        val hint = when (outcome.stage) {
            "accepted" -> "已捕获($appLabel$bucketPart, ${outcome.textCount}文本, ${outcome.elapsedMs}ms)"
            "deduped" -> "已去重($appLabel$bucketPart, ${outcome.textCount}文本)"
            "no_anchor" -> "无支付锚点($appLabel$bucketPart, ${outcome.visitedNodes}节点/${outcome.textCount}文本, ${outcome.elapsedMs}ms)"
            "null_root" -> "null_root($appLabel, 当前页面受限或未就绪)"
            "budget_truncated" -> "遍历超限($appLabel$bucketPart, ${outcome.visitedNodes}节点, ${outcome.elapsedMs}ms)"
            else -> "${outcome.stage}($appLabel$bucketPart, ${outcome.visitedNodes}节点/${outcome.textCount}文本)"
        }
        updateSummary(hint)

        val isRoutineNoise = outcome.accepted ||
            outcome.stage == "password_field" ||
            outcome.stage == "deduped" ||
            outcome.stage == "stale_session" ||
            outcome.stage == "left_target_app" ||
            outcome.stage == "trailing_inactive_or_null" ||
            (outcome.stage == "blocked_window_class" && outcome.windowClassBucket == "BlockedUI")
        if (isRoutineNoise) return false

        val diagKey = "$pkg|${outcome.stage}|${outcome.windowClassBucket}"
        val shouldWrite = synchronized(diagLock) {
            val globalOk = lastGlobalDiagAtMs == Long.MIN_VALUE || (nowMonoMs - lastGlobalDiagAtMs) >= GLOBAL_DIAG_LOG_INTERVAL_MS
            val prevKeyAt = lastDiagByKeyAtMs[diagKey]
            val keyOk = prevKeyAt == null || (nowMonoMs - prevKeyAt) >= PER_KEY_DIAG_LOG_INTERVAL_MS
            if (globalOk && keyOk) {
                lastGlobalDiagAtMs = nowMonoMs
                lastDiagByKeyAtMs[diagKey] = nowMonoMs
                while (lastDiagByKeyAtMs.size > 32) {
                    val oldest = lastDiagByKeyAtMs.keys.firstOrNull() ?: break
                    lastDiagByKeyAtMs.remove(oldest)
                }
                true
            } else {
                false
            }
        }
        if (shouldWrite) {
            writeStoreLog("屏幕诊断：$hint")
        }
        return shouldWrite
    }

    private fun recordLiveDiagnostics(pkg: String, outcome: ScreenProcessOutcome, nowMonoMs: Long) {
        val liveApp = appOrNull ?: return
        recordDiagnosticsToTargets(
            pkg = pkg,
            outcome = outcome,
            nowMonoMs = nowMonoMs,
            updateSummary = { liveApp.lastScreenDiagSummary = it },
            writeStoreLog = { msg ->
                liveApp.appScope.launch {
                    runCatching { liveApp.engine.store.addLog("SCREEN_DIAG", msg) }
                }
            }
        )
    }

    internal fun recordWindowState(pkg: String, eventWinId: Int, cls: String): Long {
        return synchronized(windowLock) {
            if (eventWinId != -1) {
                windowClassById[eventWinId] = cls
                lastUnknownWindowClassByPackage.remove(pkg)
                while (windowClassById.size > 16) {
                    val oldest = windowClassById.keys.firstOrNull() ?: break
                    windowClassById.remove(oldest)
                }
            } else {
                // Only record package-level fallback when ROM omits windowId (-1) on state events
                lastUnknownWindowClassByPackage[pkg] = cls
            }
            val sig = eventWinId to cls
            if (activeWindowSignature != sig) {
                activeWindowSignature = sig
                windowSessionCounter++
            }
            windowSessionCounter
        }
    }

    /**
     * Core service-layer pipeline separated cleanly from Android's final rootInActiveWindow getter
     * so unit/Robolectric tests can directly exercise cross-window switches and async ingestion rollback.
     */
    internal fun processRootNode(
        eventPackage: String,
        eventWindowId: Int,
        rootNode: AccessibleNode,
        nowMs: Long,
        onCaptureAccepted: ((com.walletwatcher.app.core.ScreenBillCapture, Long) -> Unit)? = null
    ): Boolean {
        return processRootNodeDetailed(
            eventPackage = eventPackage,
            eventWindowId = eventWindowId,
            rootNode = rootNode,
            nowMs = nowMs,
            onCaptureAccepted = onCaptureAccepted
        ).accepted
    }

    internal fun processRootNodeDetailed(
        eventPackage: String,
        eventWindowId: Int,
        rootNode: AccessibleNode,
        nowMs: Long,
        expectedSessionId: Long? = null,
        expectedBindGen: Long = 0L,
        onCaptureAccepted: ((com.walletwatcher.app.core.ScreenBillCapture, Long) -> Unit)? = null
    ): ScreenProcessOutcome {
        val (windowClass, sessionId) = synchronized(windowLock) {
            val rootWinId = rootNode.windowId
            val byId = if (rootWinId != -1) windowClassById[rootWinId] else null
            // Valid new windowIds never inherit an earlier valid window's class; fallback applies only
            // when the ROM itself emitted an unknown-windowId (-1) state event for this same package.
            val cls = byId ?: if (rootNode.packageName == eventPackage) {
                lastUnknownWindowClassByPackage[eventPackage]
            } else {
                null
            }
            cls to windowSessionCounter
        }
        val bucket = classifyWindowClassBucket(windowClass)

        if (!isBindGenerationActive(expectedBindGen) || (expectedSessionId != null && sessionId != expectedSessionId)) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "stale_session",
                windowClassBucket = bucket,
                visitedNodes = 0,
                textCount = 0,
                elapsedMs = 0L
            )
        }

        val snapshot = AccessibilitySnapshotCollector.collect(
            eventPackage = eventPackage,
            eventWindowId = eventWindowId,
            root = rootNode,
            windowClassForRoot = windowClass
        )

        // Strict fail-closed: discard on window mismatch, blocked window, password field, or ANY budget truncation
        if (!snapshot.isSafeToParse) {
            val stage = snapshot.rejectedReason ?: if (snapshot.texts.isEmpty()) "empty_texts" else "unsafe_snapshot"
            return ScreenProcessOutcome(
                accepted = false,
                stage = stage,
                windowClassBucket = bucket,
                visitedNodes = snapshot.visitedNodes,
                textCount = snapshot.texts.size,
                elapsedMs = snapshot.elapsedMs
            )
        }

        val capture = ScreenBillParser.parse(
            packageName = eventPackage,
            className = windowClass,
            rawTexts = snapshot.texts,
            hasPasswordField = snapshot.hasPasswordField,
            observedAtMs = nowMs
        ) ?: return ScreenProcessOutcome(
            accepted = false,
            stage = "no_anchor",
            windowClassBucket = bucket,
            visitedNodes = snapshot.visitedNodes,
            textCount = snapshot.texts.size,
            elapsedMs = snapshot.elapsedMs
        )

        // Re-verify bind lifecycle & session after traversal + parse so unbind or window switch mid-scan never commits stale data
        if (!isBindGenerationActive(expectedBindGen) || currentSessionId() != sessionId) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "stale_session",
                windowClassBucket = bucket,
                visitedNodes = snapshot.visitedNodes,
                textCount = snapshot.texts.size,
                elapsedMs = snapshot.elapsedMs
            )
        }

        if (!deduplicator.tryAcquire(sessionId, capture, nowMs)) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "deduped",
                windowClassBucket = bucket,
                visitedNodes = snapshot.visitedNodes,
                textCount = snapshot.texts.size,
                elapsedMs = snapshot.elapsedMs
            )
        }

        if (onCaptureAccepted != null) {
            onCaptureAccepted(capture, sessionId)
            return ScreenProcessOutcome(
                accepted = true,
                stage = "accepted",
                windowClassBucket = bucket,
                visitedNodes = snapshot.visitedNodes,
                textCount = snapshot.texts.size,
                elapsedMs = snapshot.elapsedMs
            )
        }

        val effectiveBindGen = if (expectedBindGen != 0L) expectedBindGen else currentBindGeneration()
        val liveApp = app
        val job = enqueueCaptureIngest(
            capture = capture,
            expectedBindGen = effectiveBindGen,
            expectedSessionId = sessionId,
            dispatcher = Dispatchers.IO,
            ingestAction = { cap -> liveApp.engine.ingestScreenCapture(cap) },
            onError = { err ->
                liveApp.engine.store.addLog(
                    "ACCESSIBILITY",
                    "屏幕增强入账异常（已允许重试）：${err.javaClass.simpleName}"
                )
            },
            onSuccess = {
                liveApp.lastScreenCaptureAtMs = nowMs
            }
        )
        if (job == null) {
            return ScreenProcessOutcome(
                accepted = false,
                stage = "stale_session",
                windowClassBucket = bucket,
                visitedNodes = snapshot.visitedNodes,
                textCount = snapshot.texts.size,
                elapsedMs = snapshot.elapsedMs
            )
        }
        return ScreenProcessOutcome(
            accepted = true,
            stage = "accepted",
            windowClassBucket = bucket,
            visitedNodes = snapshot.visitedNodes,
            textCount = snapshot.texts.size,
            elapsedMs = snapshot.elapsedMs
        )
    }

    /**
     * Service-bound ingestion dispatcher:
     * - Runs under the service's per-bind SupervisorJob (cancelled immediately on onUnbind/onDestroy).
     * - Re-verifies serviceBound + bindGeneration + sessionId at coroutine execution entry before
     *   touching LedgerEngine, rolling back deduplicator if invalidated while queued.
     */
    internal fun enqueueCaptureIngest(
        capture: com.walletwatcher.app.core.ScreenBillCapture,
        expectedBindGen: Long,
        expectedSessionId: Long,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ingestAction: (com.walletwatcher.app.core.ScreenBillCapture) -> Unit,
        onError: ((Throwable) -> Unit)? = null,
        onSuccess: (() -> Unit)? = null
    ): Job? {
        val activeParentJob = synchronized(lifecycleLock) {
            if (!serviceBound || bindGeneration != expectedBindGen || !ingestJob.isActive) {
                null
            } else {
                ingestJob
            }
        }
        if (activeParentJob == null) {
            deduplicator.releaseOnFailure(expectedSessionId, capture)
            return null
        }

        val scope = CoroutineScope(activeParentJob + dispatcher)
        val launched = scope.launch {
            val stillValid = synchronized(lifecycleLock) {
                serviceBound && bindGeneration == expectedBindGen
            } && currentSessionId() == expectedSessionId
            if (!stillValid) {
                deduplicator.releaseOnFailure(expectedSessionId, capture)
                return@launch
            }
            try {
                ingestAction(capture)
                onSuccess?.invoke()
            } catch (ce: CancellationException) {
                deduplicator.releaseOnFailure(expectedSessionId, capture)
                throw ce
            } catch (t: Throwable) {
                deduplicator.releaseOnFailure(expectedSessionId, capture)
                onError?.invoke(t)
            }
        }
        launched.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                deduplicator.releaseOnFailure(expectedSessionId, capture)
            }
        }
        return launched
    }

    private fun isTransientWidgetClass(cls: String): Boolean {
        return cls.startsWith("android.widget.") ||
            cls.startsWith("android.view.") ||
            cls.endsWith("Toast")
    }

    private class AndroidAccessibleNode(private val delegate: AccessibilityNodeInfo) : AccessibleNode {
        override val packageName: String?
            get() = delegate.packageName?.toString()
        override val windowId: Int
            get() = delegate.windowId
        override val isPassword: Boolean
            get() = delegate.isPassword
        override val text: String?
            get() = delegate.text?.toString()
        override val contentDescription: String?
            get() = delegate.contentDescription?.toString()
        override val childCount: Int
            get() = delegate.childCount

        override fun getChild(index: Int): AccessibleNode? {
            return delegate.getChild(index)?.let { AndroidAccessibleNode(it) }
        }

        @Suppress("DEPRECATION")
        override fun recycleSafely() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                runCatching { delegate.recycle() }
            }
        }
    }

    companion object {
        private const val CONTENT_SCAN_THROTTLE_MS = 250L
        private const val STATE_SCAN_MIN_INTERVAL_MS = 120L
        private const val SETTLE_RESCAN_DELAY_MS = 350L
        internal const val GLOBAL_DIAG_LOG_INTERVAL_MS = 10_000L
        internal const val PER_KEY_DIAG_LOG_INTERVAL_MS = 30_000L

        /**
         * Maps an external AccessibilityEvent className into a strictly closed set of constant buckets.
         * Guarantees no arbitrary external string ever enters UI diagnostics or SQLite logs.
         */
        internal fun classifyWindowClassBucket(className: String?): String {
            if (className.isNullOrBlank()) return "None"
            if (ScreenBillParser.isBlockedWindowClass(className)) return "BlockedUI"
            val lower = className.substringAfterLast('.').lowercase(Locale.US)
            return when {
                lower.contains("walletorder") || lower.contains("walletpaid") -> "WalletOrderUI"
                lower.contains("remittance") -> "RemittanceUI"
                lower.contains("luckymoney") -> "LuckyMoneyUI"
                lower.contains("bill") || lower.contains("mall") || lower.contains("transaction") -> "BillDetailUI"
                lower.contains("wallet") || lower.contains("cashier") || lower.contains("msp") -> "WalletPayUI"
                lower.contains("webview") || lower.contains("xweb") -> "WebViewUI"
                lower.contains("appbrand") -> "MiniProgramUI"
                lower.contains("dialog") -> "DialogUI"
                else -> "OtherUI"
            }
        }
    }
}
