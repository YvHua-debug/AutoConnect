package com.example.composestarter.data

import android.content.Intent
import android.net.Uri

/**
 * 本应用可以联动的 VPN 客户端。
 *
 * 三家都把自己对外部开放的控制入口写在清单里，本应用只负责把「命中名单 / 离开名单」翻译成
 * 这些入口，不碰它们的配置、节点和订阅：
 *
 * | 客户端 | 入口 | 启动 / 停止 |
 * |---|---|---|
 * | Clash Meta | `com.github.kr328.clash.ExternalControlActivity` | `ACTION_START_CLASH` / `ACTION_STOP_CLASH` |
 * | FLClash | `com.follow.clash.QuickActionActivity` | `com.follow.clash.action.START` / `...STOP` |
 * | Surfboard | `com.getsurfboard.ui.activity.DeeplinkActivity` | `surfboard:///start` / `surfboard:///stop` |
 *
 * 三个入口都是各客户端给自己桌面快捷方式 / 快捷设置图块用的同一套控制接口（真机核对过：
 * FLClash 的 `TileService`、Surfboard 的 `SurfboardTile`、Clash Meta 的 `TileService` 点下去执行的
 * 就是这里发出的那条指令）。
 *
 * 枚举顺序 = 没显式选择时的自动探测顺序。
 */
enum class VpnEngine(
    val id: String,
    val displayName: String,
    val packageName: String,
    private val controlActivity: String,
    private val startAction: String? = null,
    private val stopAction: String? = null,
    private val startUri: String? = null,
    private val stopUri: String? = null,
    /**
     * 停止方向是否可靠。
     *
     * FLClash / Surfboard 的停止指令在真机上实测 3/3 一次成功（2026-09-28，Redmi K80 / HyperOS 3）；
     * Clash Meta 的停止是它自己进程里的私有广播（`CLASH_REQUEST_STOP`），冷启动状态下实测 0/3，
     * 连发 6 次也无效，只有它的界面被打开过一次之后才能收到——这条限制在它自己的代码里，
     * 本应用改不了，所以如实标出来。
     */
    val stopReliable: Boolean,
) {

    CLASH_META(
        id = "clash-meta",
        displayName = "Clash Meta",
        packageName = "com.github.metacubex.clash.meta",
        controlActivity = "com.github.kr328.clash.ExternalControlActivity",
        startAction = "com.github.metacubex.clash.meta.action.START_CLASH",
        stopAction = "com.github.metacubex.clash.meta.action.STOP_CLASH",
        stopReliable = false,
    ),

    FLCLASH(
        id = "flclash",
        displayName = "FLClash",
        packageName = "com.follow.clash",
        // FlClash 0.8.98（2026-09-28 更新）把 TempActivity 改名为 QuickActionActivity，action 不变；
        // 旧名已从清单移除，继续用旧名会抛 ActivityNotFoundException。
        controlActivity = "com.follow.clash.QuickActionActivity",
        startAction = "com.follow.clash.action.START",
        stopAction = "com.follow.clash.action.STOP",
        stopReliable = true,
    ),

    SURFBOARD(
        id = "surfboard",
        displayName = "Surfboard",
        packageName = "com.getsurfboard",
        controlActivity = "com.getsurfboard.ui.activity.DeeplinkActivity",
        startUri = "surfboard:///start",
        stopUri = "surfboard:///stop",
        stopReliable = true,
    ),
    ;

    /** 启动指令。 */
    fun startIntent(): Intent = controlIntent(startAction, startUri)

    /** 停止指令，客户端没提供停止入口时返回 null。 */
    fun stopIntent(): Intent? =
        if (stopAction == null && stopUri == null) null else controlIntent(stopAction, stopUri)

    private fun controlIntent(action: String?, uri: String?): Intent {
        val intent = if (uri != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        } else {
            Intent(requireNotNull(action))
        }
        return intent.apply {
            setClassName(packageName, controlActivity)
            // 目标 Activity 都是透明 + noHistory，启动后立刻结束，不会盖住用户当前界面
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
    }

    companion object {
        fun byId(id: String?): VpnEngine? = entries.firstOrNull { it.id == id }

        /**
         * 「用户选的那个」——选了但没装（或压根没选）时退回第一个已安装的。
         *
         * 三家里只装了一个的用户什么都不用配置；装了多个的可以在界面上点选。
         */
        fun effective(selectedId: String?, installed: List<VpnEngine>): VpnEngine? {
            val selected = byId(selectedId)
            return if (selected != null && selected in installed) selected else installed.firstOrNull()
        }
    }
}
