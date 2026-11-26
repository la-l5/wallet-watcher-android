package com.walletwatcher.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.walletwatcher.app.WalletApp
import com.walletwatcher.app.core.AccessibilitySnapshotCollector
import com.walletwatcher.app.core.AccessibleNode
import com.walletwatcher.app.core.ScreenBillParser
import com.walletwatcher.app.core.ScreenCaptureDeduplicator
import kotlinx.coroutines.launch

/**
 * Strictly read-only, opt-in AccessibilityService for WeChat and Alipay payment/receipt/bill detail pages.
 * - XML packageNames locks OS delivery to com.tencent.mm and com.eg.android.AlipayGphone only.
 * - Zero gesture/click permissions (canPerformGestures="false").
 * - Verifies rootInActiveWindow.packageName and windowId match the event BEFORE reading any node.
 * - Uses per-windowId class tracking and 50ms traversal budget (any truncation discards snapshot).
 * - Aborts immediately on password fields, keyboards, login screens, or chat windows.
 */
class WalletAccessibilityService : AccessibilityService() {

    private val app: WalletApp
        get() = application as WalletApp

    private val deduplicator = ScreenCaptureDeduplicator()
    private val windowLock = Any()
    private val windowClassById = LinkedHashMap<Int, String>()
    private val lastUnknownWindowClassByPackage = LinkedHashMap<String, String>()
    private var activeWindowSignature: Pair<Int, String>? = null
    private var windowSessionCounter: Long = 0L

    @Volatile
    private var lastScanAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        app.isAccessibilityConnected = true
        app.engine.store.addLog(
            "ACCESSIBILITY",
            "只读屏幕增强已开启（仅监听微信/支付宝支付、收款与账单详情页，无模拟点击权限）"
        )
        app.notifyServiceStateChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        app.isAccessibilityConnected = false
        synchronized(windowLock) {
            windowClassById.clear()
            lastUnknownWindowClassByPackage.clear()
            activeWindowSignature = null
        }
        app.engine.store.addLog("ACCESSIBILITY", "只读屏幕增强服务已关闭或断开")
        app.notifyServiceStateChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        app.isAccessibilityConnected = false
        app.notifyServiceStateChanged()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Read-only observer; nothing to interrupt.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val eventType = event.eventType

        // If any non-whitelisted window becomes active, advance session boundary so returning later is a fresh session
        if (pkg !in ScreenBillParser.ALLOWED_PACKAGES) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
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

        val nowMs = System.currentTimeMillis()
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && nowMs - lastScanAtMs < CONTENT_SCAN_THROTTLE_MS) {
            return
        }
        lastScanAtMs = nowMs

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val wrappedRoot = AndroidAccessibleNode(root)
        try {
            processRootNode(
                eventPackage = pkg,
                eventWindowId = eventWinId,
                rootNode = wrappedRoot,
                nowMs = nowMs
            )
        } finally {
            wrappedRoot.recycleSafely()
        }
    }

    internal fun recordWindowState(pkg: String, eventWinId: Int, cls: String) {
        synchronized(windowLock) {
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

        val snapshot = AccessibilitySnapshotCollector.collect(
            eventPackage = eventPackage,
            eventWindowId = eventWindowId,
            root = rootNode,
            windowClassForRoot = windowClass
        )

        // Strict fail-closed: discard on window mismatch, blocked window, password field, or ANY budget truncation
        if (!snapshot.isSafeToParse) return false

        val capture = ScreenBillParser.parse(
            packageName = eventPackage,
            className = windowClass,
            rawTexts = snapshot.texts,
            hasPasswordField = snapshot.hasPasswordField,
            observedAtMs = nowMs
        ) ?: return false

        if (!deduplicator.tryAcquire(sessionId, capture, nowMs)) return false

        if (onCaptureAccepted != null) {
            onCaptureAccepted(capture, sessionId)
            return true
        }

        app.appScope.launch {
            val res = runCatching {
                app.engine.ingestScreenCapture(capture)
            }
            if (res.isFailure) {
                deduplicator.releaseOnFailure(sessionId, capture)
                app.engine.store.addLog(
                    "ACCESSIBILITY",
                    "屏幕增强入账异常（已允许重试）：${res.exceptionOrNull()?.javaClass?.simpleName}"
                )
            } else {
                app.lastScreenCaptureAtMs = nowMs
            }
        }
        return true
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
    }
}
