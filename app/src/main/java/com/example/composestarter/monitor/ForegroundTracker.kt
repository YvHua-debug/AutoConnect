package com.example.composestarter.monitor

/** 保存事件状态；重叠查询里的旧事件不能覆盖已经读到的新状态。 */
internal class ForegroundTracker(private val tracksVisibility: Boolean = false) {
    var packageName: String? = null
        private set
    private var activityName: String? = null
    private var latestAt = Long.MIN_VALUE
    private val visibleActivities = linkedMapOf<Pair<String, String?>, Long>()

    /** 优先当前焦点；失去焦点或被系统贴边收起的小窗仍可维持名单会话。 */
    fun packageRequiringVpn(
        pinnedPackages: Set<String> = emptySet(),
        isListed: (String) -> Boolean,
    ): String? {
        packageName?.takeIf(isListed)?.let { return it }
        if (!tracksVisibility) return null
        return visibleActivities.entries.sortedByDescending { it.value }
            .firstOrNull { isListed(it.key.first) }?.key?.first
            ?: pinnedPackages.firstOrNull(isListed)
    }

    fun accept(packageName: String?, activityName: String?, at: Long, resumed: Boolean) {
        if (packageName == null || at < latestAt || activityName in CONTROL_ACTIVITIES) return
        latestAt = at
        if (resumed) {
            if (tracksVisibility) visibleActivities[packageName to activityName] = at
            this.packageName = packageName
            this.activityName = activityName
        } else if (this.packageName == packageName && this.activityName == activityName) {
            this.packageName = null
            this.activityName = null
        }
    }

    /** Android 10+ 的 paused 只表示失去焦点，stopped 才表示窗口不可见。 */
    fun stopped(packageName: String?, activityName: String?, at: Long) {
        if (!tracksVisibility || packageName == null || at < latestAt || activityName in CONTROL_ACTIVITIES) return
        latestAt = at
        visibleActivities.remove(packageName to activityName)
        if (this.packageName == packageName && this.activityName == activityName) {
            this.packageName = null
            this.activityName = null
        }
    }

    /** 重启设备时系统不会为每个旧窗口补发 stopped。 */
    fun deviceRestarted(at: Long) {
        if (at < latestAt) return
        latestAt = at
        visibleActivities.clear()
        packageName = null
        activityName = null
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
