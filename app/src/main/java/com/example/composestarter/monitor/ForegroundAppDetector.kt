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
    private var lastForegroundPackage: String? = null
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
     * 只有第一次（或 [reset] 之后）才回看 [FULL_LOOK_BACK_MS]；
     * 之后每轮只查「上一次查询结束时间往前 [QUERY_OVERLAP_MS]」到现在的增量窗口，
     * 再和上一次的结果取较新的那个。窗口首尾相接不会漏事件，
     * 但每轮要遍历的事件从几百上千条降到个位数，这是后台占用最大的一处优化。
     *
     * 用户长时间停在同一个应用上不会产生新的切前台事件，
     * 这种情况下返回的就是上一次缓存的结果。
     */
    fun currentForegroundPackage(): String? {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val start = if (lastForegroundPackage == null || lastQueryEnd == 0L) {
            now - FULL_LOOK_BACK_MS
        } else {
            maxOf(lastQueryEnd - QUERY_OVERLAP_MS, now - MAX_INCREMENTAL_WINDOW_MS)
        }

        val events = usageStatsManager.queryEvents(start, now) ?: return lastForegroundPackage
        lastQueryEnd = now

        var latest = lastForegroundPackage
        var latestAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == resumedEventType && event.timeStamp >= latestAt) {
                val packageName = event.packageName
                if (packageName != null) {
                    latestAt = event.timeStamp
                    latest = packageName
                }
            }
        }
        lastForegroundPackage = latest
        return latest
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
        /** ACTIVITY_RESUMED 与已废弃的 MOVE_TO_FOREGROUND 取值相同（均为 1）。 */
        val resumedEventType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

        /** 首次查询（以及 reset 之后）的回看窗口，要够长才能覆盖「一直停在同一个应用」的情况。 */
        const val FULL_LOOK_BACK_MS = 30 * 60 * 1000L

        /** 增量查询时往前多取一点，保证相邻两轮窗口首尾相接。 */
        const val QUERY_OVERLAP_MS = 1_000L

        /** 增量窗口上限，防止长时间没查询时一次性拉回大量事件。 */
        const val MAX_INCREMENTAL_WINDOW_MS = 60_000L
    }
}
