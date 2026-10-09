package com.example.composestarter.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process

/**
 * 基于 UsageStatsManager 的前台应用检测。
 *
 * 需要用户在系统设置中授予「使用情况访问权限」（PACKAGE_USAGE_STATS），
 * 这是普通应用读取前台应用信息的唯一合规途径。
 */
class ForegroundAppDetector(private val context: Context) {

    /** 复用同一个事件对象，避免每轮都新建。 */
    private val event = UsageEvents.Event()

    /** 上一次查到的前台包名与查询结束时间，用于做增量查询。 */
    private var tracker = ForegroundTracker()
    private var lastQueryEnd = 0L

    /** 是否已获得「使用情况访问权限」。 */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 返回最近切到前台的应用包名，取不到时返回 null。
     *
     * 首次回看一天；之后从上次查询位置继续，重叠十秒覆盖事件的延迟写入。
     * 即使进程被冻结数分钟，也不能截掉中间的应用切换事件。
     *
     * 用户长时间停在同一个应用上不会产生新的切前台事件，
     * 这种情况下返回的就是上一次缓存的结果。
     */
    fun currentForegroundPackage(): String? {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        if (now < lastQueryEnd) tracker = ForegroundTracker()
        val start = ForegroundTracker.queryStart(lastQueryEnd, now)

        val events = usageStatsManager.queryEvents(start, now) ?: return tracker.packageName
        lastQueryEnd = now

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == resumedEventType || event.eventType == pausedEventType) {
                tracker.accept(event.packageName, event.className, event.timeStamp, event.eventType == resumedEventType)
            }
        }
        return tracker.packageName
    }

    /**
     * 当前是否有 VPN 处于连接状态。
     *
     * 只能走 `ConnectivityManager`。看起来「内核里有没有 tun 网卡」更直接，
     * 但实测（HyperOS 3 / Android 16）应用进程遍历 `NetworkInterface` 时
     * **看不到** VPN 的隧道网卡：tun0 明明存在，应用侧什么都读不到。
     * 用那个判断会把「VPN 已连接」误报成未连接，因此这里不做这种优化。
     */
    fun isVpnActive(): Boolean = runCatching {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    private companion object {
        val pausedEventType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }
        /** ACTIVITY_RESUMED 与已废弃的 MOVE_TO_FOREGROUND 取值相同（均为 1）。 */
        val resumedEventType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

    }
}
