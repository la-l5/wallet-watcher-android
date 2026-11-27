package com.walletwatcher.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.walletwatcher.app.service.WalletAccessibilityService
import com.walletwatcher.app.service.WalletNotificationListenerService
import com.walletwatcher.app.ui.OemFamily
import com.walletwatcher.app.ui.PermissionRouter
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
}
