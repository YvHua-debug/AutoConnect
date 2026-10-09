package com.example.composestarter.monitor

/** 保存事件状态；重叠查询里的旧事件不能覆盖已经读到的新状态。 */
internal class ForegroundTracker {
    var packageName: String? = null
        private set
    private var activityName: String? = null
    private var latestAt = Long.MIN_VALUE

    fun accept(packageName: String?, activityName: String?, at: Long, resumed: Boolean) {
        if (packageName == null || at < latestAt || activityName in CONTROL_ACTIVITIES) return
        latestAt = at
        if (resumed) {
            this.packageName = packageName
            this.activityName = activityName
        } else if (this.packageName == packageName && this.activityName == activityName) {
            this.packageName = null
            this.activityName = null
        }
    }

    companion object {
        // 仅忽略透明控制入口；VPN 客户端自己的主界面仍是正常的应用切换。
        private val CONTROL_ACTIVITIES = setOf(
            "com.follow.clash.QuickActionActivity",
            "com.follow.clash.TempActivity",
            "com.github.kr328.clash.ExternalControlActivity",
            "com.getsurfboard.ui.activity.DeeplinkActivity",
        )

        fun queryStart(lastEnd: Long, now: Long): Long = when {
            lastEnd == 0L || now < lastEnd -> now - LOOK_BACK_MS
            else -> lastEnd - OVERLAP_MS
        }

        private const val LOOK_BACK_MS = 24 * 60 * 60 * 1000L
        private const val OVERLAP_MS = 10_000L
    }
}
