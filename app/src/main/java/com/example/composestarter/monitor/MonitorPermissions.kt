package com.example.composestarter.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 各类特殊权限的检查与跳转入口。 */
object MonitorPermissions {

    /** 权限快照，供界面渲染。 */
    data class Snapshot(
        val usageAccess: Boolean = false,
        val overlay: Boolean = false,
        val notification: Boolean = false,
        val batteryUnrestricted: Boolean = false,
        /** 小米 / Redmi / POCO 机型：多两项 MIUI 自家的权限。 */
        val miui: Boolean = false,
        /** 「自启动 / 后台运行」；ROM 读不到状态时为 null。 */
        val autoStart: Boolean? = null,
        /** 「后台弹出界面」；ROM 读不到状态时为 null。 */
        val backgroundPopup: Boolean? = null,
        /** 「正在监控前台应用」这条常驻通知现在是否会显示出来。 */
        val statusNotificationVisible: Boolean = true,
    )

    /**
     * 常驻通知（「Auto Connect 状态」这条通道）现在会不会显示出来。
     *
     * 前台服务必须持有一条通知，而**应用没法把通知「发出去但谁都不显示」**：
     * 试过把它挂到一条 `IMPORTANCE_NONE` 的通道上，真机（HyperOS 3 / Android 16）实测无效——
     * 系统会在往被屏蔽的通道投递前台服务通知时把这条通道的重要性抬回 LOW，通知照样显示。
     * 唯一有效的办法是**用户在系统设置里关掉这条通道**，所以这里读的是系统的真实状态：
     * 应用通知总开关开着、且这条通道没被关掉，才算「显示中」。
     */
    fun isStatusNotificationVisible(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(MonitorService.CHANNEL_STATUS) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 跳转「Auto Connect 状态」这条通知通道的系统设置页。
     *
     * 用户在那一页关掉「允许通知」只影响这一条常驻通知，命中提醒走的是另一条通道，不受影响。
     */
    fun statusChannelSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, MonitorService.CHANNEL_STATUS)

    /**
     * 读取小米自家的两项权限状态（「自启动」「后台弹出界面」）。
     *
     * MIUI 把这两项做成了自己扩展的 appops：[MIUI_OP_AUTO_START]、[MIUI_OP_BACKGROUND_POPUP]。
     * 它们没有公开名称（`appops get <名字>` 一律报 Unknown operation string），只能按编号查，
     * 编号是真机实测出来的（HyperOS 3 / Android 16，2026-09-20）：
     * - `10008`=allow 的第三方应用，与「自启动管理」页里显示为允许的那几个完全一致；
     * - `10021` 置成 ignore 后，后台 startActivity 立刻被系统拦（日志 `Abort background activity starts`），
     *   置回 allow 后同一条路径恢复正常。
     *
     * 读不到（ROM 没有这套 op、或查询被拒）时返回 null，让界面显示「需手动确认」，
     * 而不是把「读不到」误报成「没开」。
     */
    fun miuiPermission(context: Context, op: Int): Boolean? = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching null
        val mode = ownOpMode(appOps, context.packageName, op)
        if (mode == null) null else mode == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    /**
     * 查自己这条 op 的模式。
     *
     * 只能反射：按编号查 op 的接口（`getOpsForPackage` / `checkOpNoThrow(int, …)`）
     * 都是隐藏 API，公开的那几个只认 op 名字，而 MIUI 自家 op 没有名字。
     * 两条路都试，哪条通用哪条；都失败就返回 null。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "MissingPermission")
    private fun ownOpMode(appOps: AppOpsManager, packageName: String, op: Int): Int? {
        val uid = Process.myUid()
        runCatching {
            val method = AppOpsManager::class.java.getMethod(
                "getOpsForPackage",
                Int::class.javaPrimitiveType,
                String::class.java,
                IntArray::class.java,
            )
            val packageOps = (method.invoke(appOps, uid, packageName, intArrayOf(op)) as? List<*>)
                ?.firstOrNull() ?: return@runCatching
            val entries = packageOps.javaClass.getMethod("getOps").invoke(packageOps) as? List<*>
                ?: return@runCatching
            for (entry in entries) {
                if (entry == null) continue
                if (entry.javaClass.getMethod("getOp").invoke(entry) != op) continue
                return entry.javaClass.getMethod("getMode").invoke(entry) as? Int
            }
        }
        return runCatching {
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            method.invoke(appOps, op, uid, packageName) as? Int
        }.getOrNull()
    }

    fun hasUsageAccess(context: Context): Boolean = ForegroundAppDetector(context).hasUsageAccess()

    fun hasOverlayAccess(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasNotificationAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * 是否已加入「电池优化」白名单。
     *
     * 国产 ROM（小米 / 华为 / OPPO…）默认会把后台应用冻结、杀进程，
     * 加了白名单才会放行，这是本项目能不能稳定常驻的最关键一项。
     */
    fun hasBatteryUnrestricted(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(true)
    }

    /** 跳转「使用情况访问权限」设置页。 */
    fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** 跳转本应用的「悬浮窗」授权页。 */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /** 跳转本应用的通知设置页。 */
    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** 弹出系统的「是否允许后台运行 / 忽略电池优化」对话框。 */
    fun batterySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** 是否是小米系机型（自启动、省电策略这些开关只在他们家的设置里有）。 */
    fun isMiui(context: Context): Boolean = runCatching {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val xiaomi = manufacturer.equals("Xiaomi", ignoreCase = true) ||
            brand.equals("Redmi", ignoreCase = true) ||
            brand.equals("POCO", ignoreCase = true)
        xiaomi && hasPackage(context, "com.miui.securitycenter")
    }.getOrDefault(false)

    /**
     * 「自启动」设置入口。
     *
     * 小米：直接送到安全中心的「自启动管理」页；
     * 其他 ROM：退回到应用详情页，由用户自己去电池/后台管理里找。
     */
    fun autoStartSettingsIntent(context: Context): Intent {
        if (isMiui(context)) {
            val miuiIntent = Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            if (miuiIntent.resolveActivity(context.packageManager) != null) return miuiIntent
        }
        return appDetailsSettingsIntent(context)
    }

    /** 跳转本应用的系统详情页。 */
    fun appDetailsSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * 「后台弹出界面」设置入口（小米 / Redmi / POCO）。
     *
     * Android 从后台启动 Activity 的限制在 MIUI 上被换成了自家的开关。没开这个开关时，
     * 后台服务发出的 startActivity（例如拉起 Clash 的控制页）会被系统直接拒绝，日志表现为：
     * `MIUILOG- Permission Denied Activity` 加 `Abort background activity starts`。
     * 注意：系统的「悬浮窗权限」已授权也没用，MIUI 这里必须单独允许。
     */
    fun backgroundPopupSettingsIntent(context: Context): Intent {
        val miuiIntent = Intent(MIUI_PERM_EDITOR_ACTION)
            .setClassName(MIUI_SECURITY_CENTER, MIUI_PERMISSIONS_EDITOR)
            .putExtra(MIUI_EXTRA_PKG_NAME, context.packageName)
        if (isMiui(context) && miuiIntent.resolveActivity(context.packageManager) != null) {
            return miuiIntent
        }
        return appDetailsSettingsIntent(context)
    }

    private fun hasPackage(context: Context, packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)

    private const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
    private const val MIUI_PERMISSIONS_EDITOR = "com.miui.permcenter.permissions.PermissionsEditorActivity"
    private const val MIUI_PERM_EDITOR_ACTION = "miui.intent.action.APP_PERM_EDITOR"

    /** MIUI 权限页通过这个 extra 决定展示哪个应用的权限。 */
    private const val MIUI_EXTRA_PKG_NAME = "extra_pkgname"

    /** MIUI 自家 appop：「自启动 / 后台运行」。 */
    const val MIUI_OP_AUTO_START = 10008

    /** MIUI 自家 appop：「后台弹出界面」。 */
    const val MIUI_OP_BACKGROUND_POPUP = 10021
}
