package com.walletwatcher.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.walletwatcher.app.core.LedgerDatabase
import com.walletwatcher.app.core.LedgerEngine
import com.walletwatcher.app.core.ScreenBillCapture
import com.walletwatcher.app.core.ScreenBillParser
import com.walletwatcher.app.service.TrailingScanToken
import com.walletwatcher.app.service.WalletAccessibilityService
import com.walletwatcher.app.service.WalletNotificationListenerService
import com.walletwatcher.app.ui.NextPermissionStep
import com.walletwatcher.app.ui.OemFamily
import com.walletwatcher.app.ui.PermissionRouter
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PermissionRouterRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun highlightedSettingsIntentsCarryFragmentArgKeyForListenerAndAccessibility() {
        val notifyIntent = PermissionRouter.buildNotificationListenerIntent(context)
        val expectedListener = ComponentName(context, WalletNotificationListenerService::class.java).flattenToString()
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, notifyIntent.action)
        assertEquals(expectedListener, notifyIntent.getStringExtra(PermissionRouter.EXTRA_FRAGMENT_ARG_KEY))
        assertEquals(
            expectedListener,
            notifyIntent.getBundleExtra(PermissionRouter.EXTRA_SHOW_FRAGMENT_ARGUMENTS)
                ?.getString(PermissionRouter.EXTRA_FRAGMENT_ARG_KEY)
        )

        val accIntent = PermissionRouter.buildAccessibilitySettingsIntent(context)
        val expectedAcc = ComponentName(context, WalletAccessibilityService::class.java).flattenToString()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, accIntent.action)
        assertEquals(expectedAcc, accIntent.getStringExtra(PermissionRouter.EXTRA_FRAGMENT_ARG_KEY))
        assertEquals(
            expectedAcc,
            accIntent.getBundleExtra(PermissionRouter.EXTRA_SHOW_FRAGMENT_ARGUMENTS)
                ?.getString(PermissionRouter.EXTRA_FRAGMENT_ARG_KEY)
        )
    }

    @Test
    fun batteryAndAppDetailsIntentsTargetCurrentPackage() {
        val battery = PermissionRouter.buildRequestIgnoreBatteryOptimizationIntent(context)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.action)
        assertEquals("package:${context.packageName}", battery.data?.toString())

        val details = PermissionRouter.buildAppDetailsIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details.action)
        assertEquals("package:${context.packageName}", details.data?.toString())
    }

    @Test
    fun detectsOemAutoStartGuidesAndBuildsCandidateIntents() {
        val honor = PermissionRouter.detectOemGuide("HONOR", "HONOR")
        assertEquals(OemFamily.HONOR, honor.family)
        val honorIntents = PermissionRouter.buildOemAutoStartIntents("HONOR", "HONOR")
        assertTrue(honorIntents.any { it.component?.packageName == "com.hihonor.systemmanager" })
        assertTrue(honorIntents.any { it.component?.packageName == "com.huawei.systemmanager" })

        assertEquals(OemFamily.HUAWEI, PermissionRouter.detectOemGuide("HUAWEI", "HUAWEI").family)
        assertEquals(OemFamily.XIAOMI, PermissionRouter.detectOemGuide("Xiaomi", "Redmi").family)
        assertEquals(OemFamily.OPPO, PermissionRouter.detectOemGuide("OnePlus", "OnePlus").family)
        assertEquals(OemFamily.VIVO, PermissionRouter.detectOemGuide("vivo", "iQOO").family)
        assertEquals(OemFamily.GENERIC, PermissionRouter.detectOemGuide("Google", "google").family)
        assertTrue(PermissionRouter.buildOemAutoStartIntents("Google", "google").isEmpty())
    }

    @Test
    fun detectsAccessibilityServiceEnabledStateAndRestrictedSettingsHint() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
        assertFalse(PermissionRouter.isAccessibilityServiceEnabled(context))

        val expected = ComponentName(context, WalletAccessibilityService::class.java).flattenToString()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other.app/.OtherService:$expected"
        )
        assertTrue(PermissionRouter.isAccessibilityServiceEnabled(context))

        assertTrue(
            PermissionRouter.needsRestrictedSettingsHint(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                notifGranted = false,
                accessibilityGranted = false
            )
        )
        assertFalse(
            PermissionRouter.needsRestrictedSettingsHint(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                notifGranted = true,
                accessibilityGranted = true
            )
        )
        assertFalse(
            PermissionRouter.needsRestrictedSettingsHint(
                sdkInt = Build.VERSION_CODES.S_V2,
                notifGranted = false,
                accessibilityGranted = false
            )
        )
        // Auto-show restricted banner ONLY when required notification listener is missing
        assertTrue(PermissionRouter.shouldAutoShowRestrictedBanner(Build.VERSION_CODES.TIRAMISU, notifGranted = false))
        assertFalse(PermissionRouter.shouldAutoShowRestrictedBanner(Build.VERSION_CODES.TIRAMISU, notifGranted = true))
    }

    @Test
    fun oemFallbackToAppDetailsDoesNotMarkAutoStartVisited() {
        val res = PermissionRouter.openOemAutoStartSettings(context, manufacturer = "Google", brand = "google")
        assertFalse(res.usedDirectOemPage)
        assertFalse(PermissionRouter.isOemAutoStartVisited(context))
    }

    @Test
    fun accessibilityServiceLayerRejectsSwitchedActiveRootWithZeroReadsAndZeroIngest() {
        val service = WalletAccessibilityService()
        var childReads = 0
        val switchedRoot = object : com.walletwatcher.app.core.AccessibleNode {
            override val packageName: String = "com.android.chrome"
            override val windowId: Int = 99
            override val isPassword: Boolean = false
            override val text: String? = null
            override val contentDescription: String? = null
            override val childCount: Int = 1
            override fun getChild(index: Int): com.walletwatcher.app.core.AccessibleNode? {
                childReads++
                return null
            }
        }
        var acceptedCount = 0
        val accepted = service.processRootNode(
            eventPackage = com.walletwatcher.app.core.ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            rootNode = switchedRoot,
            nowMs = 1_700_000_000_000L,
            onCaptureAccepted = { _, _ -> acceptedCount++ }
        )
        assertFalse(accepted)
        assertEquals(0, childReads)
        assertEquals(0, acceptedCount)
    }

    @Test
    fun validNewWindowIdDoesNotInheritStaleClassWhileUnknownWindowIdFallbackStillBlocksChat() {
        val service = WalletAccessibilityService()
        val now = 1_700_000_000_000L

        fun makePayRoot(winId: Int) = object : com.walletwatcher.app.core.AccessibleNode {
            override val packageName = com.walletwatcher.app.core.ScreenBillParser.PKG_WECHAT
            override val windowId = winId
            override val isPassword = false
            override val text: String? = null
            override val contentDescription: String? = null
            override val childCount = 3
            override fun getChild(index: Int): com.walletwatcher.app.core.AccessibleNode {
                val label = when (index) {
                    0 -> "支付成功"
                    1 -> "瑞幸咖啡"
                    else -> "¥19.90"
                }
                return object : com.walletwatcher.app.core.AccessibleNode {
                    override val packageName = com.walletwatcher.app.core.ScreenBillParser.PKG_WECHAT
                    override val windowId = winId
                    override val isPassword = false
                    override val text = label
                    override val contentDescription: String? = null
                    override val childCount = 0
                    override fun getChild(index: Int): com.walletwatcher.app.core.AccessibleNode? = null
                }
            }
        }

        // 1. Window 10 was ChattingUI (valid windowId). New valid window 11 MUST NOT inherit window 10's stale class!
        service.recordWindowState(com.walletwatcher.app.core.ScreenBillParser.PKG_WECHAT, eventWinId = 10, cls = "com.tencent.mm.ui.chatting.ChattingUI")
        var acceptedCount = 0
        val win11Accepted = service.processRootNode(
            eventPackage = com.walletwatcher.app.core.ScreenBillParser.PKG_WECHAT,
            eventWindowId = 11,
            rootNode = makePayRoot(11),
            nowMs = now,
            onCaptureAccepted = { _, _ -> acceptedCount++ }
        )
        assertTrue("Valid new window 11 should not inherit ChattingUI from window 10", win11Accepted)
        assertEquals(1, acceptedCount)

        // 2. Window 10 itself is still blocked by its cached ChattingUI class!
        val win10Accepted = service.processRootNode(
            eventPackage = com.walletwatcher.app.core.ScreenBillParser.PKG_WECHAT,
            eventWindowId = 10,
            rootNode = makePayRoot(10),
            nowMs = now + 2000L,
            onCaptureAccepted = { _, _ -> acceptedCount++ }
        )
        assertFalse("Window 10 must remain blocked as ChattingUI", win10Accepted)
        assertEquals(1, acceptedCount)

        // 3. When ROM omits windowId (-1) on ChattingUI state event, package-level fallback STILL blocks!
        service.recordWindowState(com.walletwatcher.app.core.ScreenBillParser.PKG_WECHAT, eventWinId = -1, cls = "com.tencent.mm.ui.chatting.ChattingUI")
        val unknownWinBlocked = service.processRootNode(
            eventPackage = com.walletwatcher.app.core.ScreenBillParser.PKG_WECHAT,
            eventWindowId = -1,
            rootNode = makePayRoot(25),
            nowMs = now + 4000L,
            onCaptureAccepted = { _, _ -> acceptedCount++ }
        )
        assertFalse("Unknown-windowId ROM fallback must still block ChattingUI", unknownWinBlocked)
        assertEquals(1, acceptedCount)
    }

    @Test
    fun updaterFileProviderAndUnknownSourcesIntentsWorkForUpdatesCacheDir() {
        val unknownIntent = com.walletwatcher.app.core.AppUpdater.buildUnknownAppSourcesIntent(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, unknownIntent.action)
        assertEquals("package:${context.packageName}", unknownIntent.data?.toString())

        val updatesDir = com.walletwatcher.app.core.AppUpdater.getUpdatesCacheDir(context)
        updatesDir.mkdirs()
        val fakeApk = java.io.File(updatesDir, com.walletwatcher.app.core.AppUpdater.UPDATE_APK_FILENAME)
        fakeApk.writeBytes("PK\u0003\u0004dummy".toByteArray())
        try {
            val installIntent = com.walletwatcher.app.core.AppUpdater.buildInstallApkIntent(context, fakeApk)
            assertEquals(android.content.Intent.ACTION_VIEW, installIntent.action)
            assertEquals("application/vnd.android.package-archive", installIntent.type)
            assertEquals("content", installIntent.data?.scheme)
            assertEquals("${context.packageName}.fileprovider", installIntent.data?.authority)
            assertTrue((installIntent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        } finally {
            fakeApk.delete()
        }
    }

    @Test
    fun nextPendingStepRelayHandlesOemGenericAndManualSkipWithoutGettingStuck() {
        assertEquals(
            NextPermissionStep.NOTIFY,
            PermissionRouter.resolveNextPendingStep(notifGranted = false, batteryExempt = false, oemVisited = false)
        )
        assertEquals(
            NextPermissionStep.BATTERY,
            PermissionRouter.resolveNextPendingStep(notifGranted = true, batteryExempt = false, oemVisited = false)
        )
        assertEquals(
            NextPermissionStep.OEM_AUTOSTART,
            PermissionRouter.resolveNextPendingStep(notifGranted = true, batteryExempt = true, oemVisited = false, hasOemCandidates = true)
        )
        assertEquals(
            NextPermissionStep.ALL_READY,
            PermissionRouter.resolveNextPendingStep(notifGranted = true, batteryExempt = true, oemVisited = true, hasOemCandidates = true)
        )
        // Generic phone without OEM candidates MUST NOT get stuck on OEM step
        assertEquals(
            NextPermissionStep.ALL_READY,
            PermissionRouter.resolveNextPendingStep(notifGranted = true, batteryExempt = true, oemVisited = false, hasOemCandidates = false)
        )
        // OEM phone where user manually handled/skipped MUST NOT stay stuck even if direct launch failed
        assertFalse(PermissionRouter.isOemStepAcknowledged(context))
        PermissionRouter.markOemStepAcknowledged(context)
        assertTrue(PermissionRouter.isOemStepAcknowledged(context))
        assertEquals(
            NextPermissionStep.ALL_READY,
            PermissionRouter.resolveNextPendingStep(
                notifGranted = true,
                batteryExempt = true,
                oemVisited = false,
                hasOemCandidates = true,
                oemAcknowledged = PermissionRouter.isOemStepAcknowledged(context)
            )
        )
    }

    @Test
    fun trailingScanRejectsSamePackageChatSwitchAndNullActiveRoot() {
        val service = WalletAccessibilityService()
        val now = 1_700_000_000_000L

        fun makeNode(pkg: String, winId: Int, lines: List<String>) = object : com.walletwatcher.app.core.AccessibleNode {
            override val packageName = pkg
            override val windowId = winId
            override val isPassword = false
            override val text: String? = null
            override val contentDescription: String? = null
            override val childCount = lines.size
            override fun getChild(index: Int) = object : com.walletwatcher.app.core.AccessibleNode {
                override val packageName = pkg
                override val windowId = winId
                override val isPassword = false
                override val text = lines[index]
                override val contentDescription: String? = null
                override val childCount = 0
                override fun getChild(i: Int): com.walletwatcher.app.core.AccessibleNode? = null
            }
        }

        // 1. Triggered on WeChat payment window (id=20), then user switches to same-package ChattingUI (id=21)
        val paySession = service.recordWindowState(ScreenBillParser.PKG_WECHAT, eventWinId = 20, cls = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI")
        val payToken = TrailingScanToken(pkg = ScreenBillParser.PKG_WECHAT, expectedWindowId = 20, expectedSessionId = paySession)

        // Switching to ChattingUI advances session; trailing scan MUST fail closed as stale_session
        service.recordWindowState(ScreenBillParser.PKG_WECHAT, eventWinId = 21, cls = "com.tencent.mm.ui.chatting.ChattingUI")
        var acceptedCount = 0
        val switchedOut = service.evaluateBoundRoot(
            token = payToken,
            rootNode = makeNode(ScreenBillParser.PKG_WECHAT, 21, listOf("支付成功", "瑞幸咖啡", "¥19.90")),
            isTrailing = true,
            nowMs = now + 350L,
            onCaptureAccepted = { _, _ -> acceptedCount++ }
        )
        assertFalse(switchedOut.accepted)
        assertEquals("stale_session", switchedOut.stage)
        assertEquals(0, acceptedCount)

        // 2. Same session, but active root switched to a different windowId in same package -> window_id_mismatch
        val paySession2 = service.recordWindowState(ScreenBillParser.PKG_WECHAT, eventWinId = 30, cls = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI")
        val payToken2 = TrailingScanToken(pkg = ScreenBillParser.PKG_WECHAT, expectedWindowId = 30, expectedSessionId = paySession2)
        val wrongWinOut = service.evaluateBoundRoot(
            token = payToken2,
            rootNode = makeNode(ScreenBillParser.PKG_WECHAT, 31, listOf("支付成功", "瑞幸咖啡", "¥19.90")),
            isTrailing = true,
            nowMs = now + 700L,
            onCaptureAccepted = { _, _ -> acceptedCount++ }
        )
        assertFalse(wrongWinOut.accepted)
        assertEquals("window_id_mismatch", wrongWinOut.stage)
        assertEquals(0, acceptedCount)

        // 3. Active root is null (e.g., switched to desktop while old payment window still enumerable) -> fails closed
        val nullActiveOut = service.evaluateBoundRoot(
            token = payToken2,
            rootNode = null,
            isTrailing = true,
            nowMs = now + 1050L,
            onCaptureAccepted = { _, _ -> acceptedCount++ }
        )
        assertFalse(nullActiveOut.accepted)
        assertEquals("trailing_inactive_or_null", nullActiveOut.stage)
        assertEquals(0, acceptedCount)

        // 4. Unknown triggering windowId (-1) on trailing rescan MUST fail closed BEFORE reading any text or child!
        val unknownWinSession = service.recordWindowState(ScreenBillParser.PKG_WECHAT, eventWinId = -1, cls = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI")
        val unknownWinToken = TrailingScanToken(
            pkg = ScreenBillParser.PKG_WECHAT,
            expectedWindowId = -1,
            expectedSessionId = unknownWinSession,
            eventSeq = 10L
        )
        assertFalse("Trailing scheduler must reject unknown windowId -1", service.tryAcceptTrailingToken(unknownWinToken))
        var unknownWinChildReads = 0
        var unknownWinTextReads = 0
        val newPageWithValidWinId = object : com.walletwatcher.app.core.AccessibleNode {
            override val packageName = ScreenBillParser.PKG_WECHAT
            override val windowId = 55
            override val isPassword = false
            override val text: String?
                get() {
                    unknownWinTextReads++
                    return "支付成功"
                }
            override val contentDescription: String? = null
            override val childCount = 1
            override fun getChild(index: Int): com.walletwatcher.app.core.AccessibleNode? {
                unknownWinChildReads++
                return null
            }
        }
        val unknownWinOut = service.evaluateBoundRoot(
            token = unknownWinToken,
            rootNode = newPageWithValidWinId,
            isTrailing = true,
            nowMs = now + 1200L,
            onCaptureAccepted = { _, _ -> acceptedCount++ }
        )
        assertFalse(unknownWinOut.accepted)
        assertEquals("trailing_unknown_window_id", unknownWinOut.stage)
        assertEquals("Must not read node text when trailing windowId is -1", 0, unknownWinTextReads)
        assertEquals("Must not read children when trailing windowId is -1", 0, unknownWinChildReads)
        assertEquals(0, acceptedCount)

        // 5. Genuine settled payment window with matching (pkg, valid windowId, session) -> accepted
        val paySession3 = service.recordWindowState(ScreenBillParser.PKG_WECHAT, eventWinId = 30, cls = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI")
        val payToken3 = TrailingScanToken(pkg = ScreenBillParser.PKG_WECHAT, expectedWindowId = 30, expectedSessionId = paySession3, eventSeq = 11L)
        val validOut = service.evaluateBoundRoot(
            token = payToken3,
            rootNode = makeNode(ScreenBillParser.PKG_WECHAT, 30, listOf("支付成功", "瑞幸咖啡", "¥19.90")),
            isTrailing = true,
            nowMs = now + 1400L,
            onCaptureAccepted = { _, _ -> acceptedCount++ }
        )
        assertTrue(validOut.accepted)
        assertEquals("accepted", validOut.stage)
        assertEquals("WalletOrderUI", validOut.windowClassBucket)
        assertEquals(1, acceptedCount)
    }

    @Test
    fun staleWorkerTrailingScheduleCannotOverwriteNewerWindowAndUnbindCancelsQueuedIngest() {
        val service = WalletAccessibilityService()
        val bindGen = service.markBoundLifecycle()

        // 1. Stale worker from session 1 (seq 1) CANNOT overwrite pending trailing rescan of newer session 2 (seq 2)
        val sess1 = service.recordWindowState(ScreenBillParser.PKG_WECHAT, eventWinId = 40, cls = "com.tencent.mm.plugin.wallet_core.ui.WalletPayUI")
        val oldToken = TrailingScanToken(ScreenBillParser.PKG_WECHAT, expectedWindowId = 40, expectedSessionId = sess1, eventSeq = 1L, expectedBindGen = bindGen)
        val sess2 = service.recordWindowState(ScreenBillParser.PKG_WECHAT, eventWinId = 41, cls = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI")
        val newToken = TrailingScanToken(ScreenBillParser.PKG_WECHAT, expectedWindowId = 41, expectedSessionId = sess2, eventSeq = 2L, expectedBindGen = bindGen)

        assertTrue(service.tryAcceptTrailingToken(newToken))
        assertFalse("Older session/seq worker must not overwrite newer window rescan", service.tryAcceptTrailingToken(oldToken))
        assertEquals(newToken, service.currentPendingTrailingTokenForTest())

        // Same-session older seq also cannot overwrite newer seq
        val sameSessOlder = newToken.copy(eventSeq = 1L)
        assertFalse(service.tryAcceptTrailingToken(sameSessOlder))
        assertEquals(newToken, service.currentPendingTrailingTokenForTest())

        // 2. Parse succeeds -> pause ingest dispatcher -> unbind -> resume dispatcher -> ZERO booked transactions!
        val db = LedgerDatabase(context)
        try {
            val engine = LedgerEngine(store = db)
            val pausedQueue = ArrayDeque<Runnable>()
            val pausingDispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    pausedQueue.addLast(block)
                }
            }

            val payRoot = object : com.walletwatcher.app.core.AccessibleNode {
                override val packageName = ScreenBillParser.PKG_WECHAT
                override val windowId = 41
                override val isPassword = false
                override val text: String? = null
                override val contentDescription: String? = null
                override val childCount = 3
                override fun getChild(index: Int): com.walletwatcher.app.core.AccessibleNode {
                    val txt = when (index) {
                        0 -> "支付成功"
                        1 -> "瑞幸咖啡"
                        else -> "¥19.90"
                    }
                    return object : com.walletwatcher.app.core.AccessibleNode {
                        override val packageName = ScreenBillParser.PKG_WECHAT
                        override val windowId = 41
                        override val isPassword = false
                        override val text = txt
                        override val contentDescription: String? = null
                        override val childCount = 0
                        override fun getChild(i: Int): com.walletwatcher.app.core.AccessibleNode? = null
                    }
                }
            }

            var parsedCapture: ScreenBillCapture? = null
            var parsedSession = -1L
            val parsedOut = service.processRootNodeDetailed(
                eventPackage = ScreenBillParser.PKG_WECHAT,
                eventWindowId = 41,
                rootNode = payRoot,
                nowMs = 1_700_000_000_000L,
                expectedSessionId = sess2,
                expectedBindGen = bindGen,
                onCaptureAccepted = { cap, sid ->
                    parsedCapture = cap
                    parsedSession = sid
                }
            )
            assertTrue(parsedOut.accepted)
            assertNotNull(parsedCapture)

            var ingestInvoked = 0
            val queuedJob = service.enqueueCaptureIngest(
                capture = parsedCapture!!,
                expectedBindGen = bindGen,
                expectedSessionId = parsedSession,
                dispatcher = pausingDispatcher,
                ingestAction = { cap ->
                    ingestInvoked++
                    engine.ingestScreenCapture(cap)
                }
            )
            assertNotNull(queuedJob)
            assertTrue("Coroutine should be queued and paused before unbind", pausedQueue.isNotEmpty())

            // Simulate user disabling AccessibilityService while ingest coroutine is still queued
            service.handleUnbindLifecycle()
            assertTrue("Queued ingest job must be cancelled immediately on unbind", queuedJob!!.isCancelled)

            // Resume / drain paused dispatcher
            while (pausedQueue.isNotEmpty()) {
                pausedQueue.removeFirst().run()
            }

            assertEquals("Unbound service must NOT execute queued ingest", 0, ingestInvoked)
            assertTrue("No transaction may be booked after unbind", engine.store.listTransactions().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun sqliteAndUiDiagnosticsNeverLeakRawTextOrExternalClassAndThrottleAlternatingEvents() {
        val service = WalletAccessibilityService()
        val db = LedgerDatabase(context)
        try {
            val externalClassSentinel = "com.tencent.mm.EvilCustomClassName_9911"
            val textSentinel = "PRIVATE_SCREEN_BODY_SENTINEL_7733"
            service.recordWindowState(ScreenBillParser.PKG_WECHAT, eventWinId = 42, cls = externalClassSentinel)

            val noAnchorRoot = object : com.walletwatcher.app.core.AccessibleNode {
                override val packageName = ScreenBillParser.PKG_WECHAT
                override val windowId = 42
                override val isPassword = false
                override val text: String? = null
                override val contentDescription: String? = null
                override val childCount = 1
                override fun getChild(index: Int) = object : com.walletwatcher.app.core.AccessibleNode {
                    override val packageName = ScreenBillParser.PKG_WECHAT
                    override val windowId = 42
                    override val isPassword = false
                    override val text = textSentinel
                    override val contentDescription: String? = null
                    override val childCount = 0
                    override fun getChild(i: Int): com.walletwatcher.app.core.AccessibleNode? = null
                }
            }

            val outcomeA = service.processRootNodeDetailed(
                eventPackage = ScreenBillParser.PKG_WECHAT,
                eventWindowId = 42,
                rootNode = noAnchorRoot,
                nowMs = 1_700_000_000_000L,
                onCaptureAccepted = { _, _ -> }
            )
            assertFalse(outcomeA.accepted)
            assertEquals("no_anchor", outcomeA.stage)
            assertEquals("OtherUI", outcomeA.windowClassBucket)

            var uiSummary = ""
            // Rapid A -> B -> A alternating diagnostics within 10s MUST obey global monotonic rate limit (only 1 DB row)
            val outcomeB = outcomeA.copy(stage = "window_id_mismatch", windowClassBucket = "WalletPayUI")
            val wrote1 = service.recordDiagnosticsToTargets(
                pkg = ScreenBillParser.PKG_WECHAT,
                outcome = outcomeA,
                nowMonoMs = 100_000L,
                updateSummary = { uiSummary = it },
                writeStoreLog = { db.addLog("SCREEN_DIAG", it) }
            )
            val wrote2 = service.recordDiagnosticsToTargets(
                pkg = ScreenBillParser.PKG_WECHAT,
                outcome = outcomeB,
                nowMonoMs = 101_000L,
                updateSummary = { uiSummary = it },
                writeStoreLog = { db.addLog("SCREEN_DIAG", it) }
            )
            val wrote3 = service.recordDiagnosticsToTargets(
                pkg = ScreenBillParser.PKG_WECHAT,
                outcome = outcomeA,
                nowMonoMs = 102_000L,
                updateSummary = { uiSummary = it },
                writeStoreLog = { db.addLog("SCREEN_DIAG", it) }
            )
            assertTrue(wrote1)
            assertFalse("Alternating B within 10s global window must be throttled", wrote2)
            assertFalse("Alternating A within 10s global window must be throttled", wrote3)

            // After 10s global interval, distinct key B is allowed
            val wrote4 = service.recordDiagnosticsToTargets(
                pkg = ScreenBillParser.PKG_WECHAT,
                outcome = outcomeB,
                nowMonoMs = 111_000L,
                updateSummary = { uiSummary = it },
                writeStoreLog = { db.addLog("SCREEN_DIAG", it) }
            )
            assertTrue(wrote4)

            // Same key A at 122s (11s after B, passing global 10s, but only 22s after prior A < 30s per-key) MUST still be throttled
            val wrote5 = service.recordDiagnosticsToTargets(
                pkg = ScreenBillParser.PKG_WECHAT,
                outcome = outcomeA,
                nowMonoMs = 122_000L,
                updateSummary = { uiSummary = it },
                writeStoreLog = { db.addLog("SCREEN_DIAG", it) }
            )
            assertFalse("Same key A within 30s per-key cooldown must be throttled", wrote5)

            // Same key A at 132s (32s >= 30s per-key and 21s >= 10s global) is allowed again
            val wrote6 = service.recordDiagnosticsToTargets(
                pkg = ScreenBillParser.PKG_WECHAT,
                outcome = outcomeA,
                nowMonoMs = 132_000L,
                updateSummary = { uiSummary = it },
                writeStoreLog = { db.addLog("SCREEN_DIAG", it) }
            )
            assertTrue(wrote6)

            val diagLogs = db.listLogs(50).filter { it.stage == "SCREEN_DIAG" }
            assertEquals(3, diagLogs.size)
            assertFalse(uiSummary.contains(textSentinel))
            assertFalse(uiSummary.contains("EvilCustomClassName"))
            for (log in diagLogs) {
                assertFalse("Raw screen text must never enter SQLite", log.message.contains(textSentinel))
                assertFalse("Arbitrary external class name must never enter SQLite", log.message.contains("EvilCustomClassName"))
                assertFalse(log.sanitizedSample.contains(textSentinel))
            }
        } finally {
            db.close()
        }
    }
}
