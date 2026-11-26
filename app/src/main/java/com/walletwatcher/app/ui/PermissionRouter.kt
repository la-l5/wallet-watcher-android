package com.walletwatcher.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.walletwatcher.app.core.ScreenBillParser
import com.walletwatcher.app.service.WalletAccessibilityService
import com.walletwatcher.app.service.WalletNotificationListenerService
import java.util.Locale

enum class OemFamily {
    HONOR,
    HUAWEI,
    XIAOMI,
    OPPO,
    VIVO,
    GENERIC
}

data class OemAutoStartGuide(
    val family: OemFamily,
    val displayName: String,
    val stepTip: String,
    val candidates: List<Pair<String, String>>
)

data class OemLaunchResult(
    val launched: Boolean,
    val usedDirectOemPage: Boolean,
    val guide: OemAutoStartGuide
)

/**
 * Centralized one-click permission pipeline router and live permission checker.
 * - Uses :settings:fragment_args_key + :settings:show_fragment_args to highlight target entries.
 * - Supports in-app battery optimization request dialog.
 * - Guides Android 13-15 sideloaded Restricted Settings via App Details.
 * - Routes Honor/Huawei/Xiaomi/OPPO/vivo Auto-Start pages with safe try-catch fallback.
 */
object PermissionRouter {

    const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    private const val PREFS_NAME = "wallet_watcher_perm_prefs"
    private const val KEY_OEM_AUTOSTART_VISITED = "oem_autostart_visited"

    fun isNotificationListenerGranted(context: Context): Boolean {
        val enabled = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context)
        }.getOrDefault(emptySet())
        return context.packageName in enabled
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, WalletAccessibilityService::class.java)
        val enabledRaw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        if (enabledRaw.isBlank()) return false

        val flat = expected.flattenToString()
        val shortFlat = expected.flattenToShortString()
        return enabledRaw.split(':').any { rawEntry ->
            val entry = rawEntry.trim()
            if (entry.isEmpty()) return@any false
            val parsed = ComponentName.unflattenFromString(entry)
            parsed == expected ||
                entry.equals(flat, ignoreCase = true) ||
                entry.equals(shortFlat, ignoreCase = true)
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    fun isOemAutoStartVisited(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OEM_AUTOSTART_VISITED, false)
    }

    fun markOemAutoStartVisited(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OEM_AUTOSTART_VISITED, true)
            .apply()
    }

    fun needsRestrictedSettingsHint(
        sdkInt: Int = Build.VERSION.SDK_INT,
        notifGranted: Boolean,
        accessibilityGranted: Boolean = true
    ): Boolean {
        return sdkInt >= Build.VERSION_CODES.TIRAMISU && (!notifGranted || !accessibilityGranted)
    }

    /**
     * Auto-displays the Android 13-15 Restricted Settings warning banner ONLY when the required
     * Notification Listener is not yet granted (avoids nagging users who keep optional Accessibility off).
     */
    fun shouldAutoShowRestrictedBanner(
        sdkInt: Int = Build.VERSION.SDK_INT,
        notifGranted: Boolean
    ): Boolean {
        return sdkInt >= Build.VERSION_CODES.TIRAMISU && !notifGranted
    }

    fun detectOemGuide(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty()
    ): OemAutoStartGuide {
        val m = manufacturer.trim().lowercase(Locale.US)
        val b = brand.trim().lowercase(Locale.US)
        val token = "$m $b"
        return when {
            token.contains("honor") -> OemAutoStartGuide(
                family = OemFamily.HONOR,
                displayName = "荣耀 MagicOS",
                stepTip = "找到本应用 → 关闭「自动管理」→ 手动全开「自启动 / 关联启动 / 后台活动」",
                candidates = listOf(
                    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            )
            token.contains("huawei") -> OemAutoStartGuide(
                family = OemFamily.HUAWEI,
                displayName = "华为 EMUI / HarmonyOS",
                stepTip = "找到本应用 → 关闭「自动管理」→ 手动全开「自启动 / 关联启动 / 后台活动」",
                candidates = listOf(
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            )
            token.contains("xiaomi") || token.contains("redmi") || token.contains("poco") -> OemAutoStartGuide(
                family = OemFamily.XIAOMI,
                displayName = "小米 HyperOS / MIUI",
                stepTip = "开启「自启动」，并在应用省电策略中设为「无限制」",
                candidates = listOf(
                    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            )
            token.contains("oppo") || token.contains("oneplus") || token.contains("realme") -> OemAutoStartGuide(
                family = OemFamily.OPPO,
                displayName = "OPPO / 一加 / 真我",
                stepTip = "开启「允许自启动」与「允许完全后台行为」",
                candidates = listOf(
                    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                    "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            )
            token.contains("vivo") || token.contains("iqoo") -> OemAutoStartGuide(
                family = OemFamily.VIVO,
                displayName = "vivo / iQOO",
                stepTip = "开启「允许自启动」与「允许后台高耗电」",
                candidates = listOf(
                    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            )
            else -> OemAutoStartGuide(
                family = OemFamily.GENERIC,
                displayName = "安卓后台保活",
                stepTip = "在应用详情 → 电池中设为「不受限制」，并在多任务列表下拉加锁",
                candidates = emptyList()
            )
        }
    }

    fun buildNotificationListenerIntent(context: Context): Intent {
        val component = ComponentName(context, WalletNotificationListenerService::class.java)
        return buildHighlightedSettingsIntent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, component)
    }

    fun buildAccessibilitySettingsIntent(context: Context): Intent {
        val component = ComponentName(context, WalletAccessibilityService::class.java)
        return buildHighlightedSettingsIntent(Settings.ACTION_ACCESSIBILITY_SETTINGS, component)
    }

    fun buildRequestIgnoreBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun buildAppDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun buildOemAutoStartIntents(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty()
    ): List<Intent> {
        val guide = detectOemGuide(manufacturer, brand)
        return guide.candidates.map { (pkg, cls) ->
            Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private fun buildHighlightedSettingsIntent(action: String, component: ComponentName): Intent {
        val argKey = component.flattenToString()
        val bundle = Bundle().apply {
            putString(EXTRA_FRAGMENT_ARG_KEY, argKey)
        }
        return Intent(action).apply {
            putExtra(EXTRA_FRAGMENT_ARG_KEY, argKey)
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle)
        }
    }

    fun openNotificationListenerSettings(context: Context): Boolean {
        return runCatching {
            context.startActivity(buildNotificationListenerIntent(context))
            true
        }.getOrElse {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                true
            }.getOrElse {
                openAppDetails(context)
            }
        }
    }

    fun openAccessibilitySettings(context: Context): Boolean {
        return runCatching {
            context.startActivity(buildAccessibilitySettingsIntent(context))
            true
        }.getOrElse {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                true
            }.getOrElse {
                openAppDetails(context)
            }
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        return runCatching {
            context.startActivity(buildRequestIgnoreBatteryOptimizationIntent(context))
            true
        }.getOrElse {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                true
            }.getOrElse {
                openAppDetails(context)
            }
        }
    }

    fun openAppDetails(context: Context): Boolean {
        val intent = buildAppDetailsIntent(context).apply {
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun openOemAutoStartSettings(
        context: Context,
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty()
    ): OemLaunchResult {
        val guide = detectOemGuide(manufacturer, brand)
        for (intent in buildOemAutoStartIntents(manufacturer, brand)) {
            val ok = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) {
                markOemAutoStartVisited(context)
                return OemLaunchResult(launched = true, usedDirectOemPage = true, guide = guide)
            }
        }
        val fallbackOk = openAppDetails(context)
        return OemLaunchResult(launched = fallbackOk, usedDirectOemPage = false, guide = guide)
    }

    fun openWeChatApp(context: Context): Boolean {
        val launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(ScreenBillParser.PKG_WECHAT)
        }.getOrNull() ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }
}
