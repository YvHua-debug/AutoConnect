package com.example.composestarter.data

import android.content.Intent
import android.net.Uri

/**
 * 本应用可以识别的 VPN 客户端；具备外部控制入口的可自动联动。
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
    private val controlActivity: String? = null,
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

    // 0.7.7 的 VPN 服务不导出，图块受系统权限保护；没有外部启停入口。
    // 仅用于隧道归属识别，不列入适配名单，也不能被选为联动客户端。
    UMIVPN(
        id = "umivpn",
        displayName = "UmiVPN",
        packageName = "com5vnetwork.umi",
        stopReliable = false,
    ),
    ;

    val supportsAutoControl: Boolean get() = controlActivity != null

    /** 启动指令，客户端未开放控制入口时返回 null。 */
    fun startIntent(): Intent? =
        if (!supportsAutoControl) null else controlIntent(startAction, startUri)

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
            setClassName(packageName, requireNotNull(controlActivity))
            // NEW_TASK 单独使用会复用客户端主界面的任务并将它带到前台。
            // 控制页用独立临时任务，结束后回到原应用；启动和停止都必须隔离。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
    }

    companion object {
        /** 适配名单只包含提供外部启停入口的客户端。 */
        val supportedEntries: List<VpnEngine> = entries.filter { it.supportsAutoControl }

        fun byId(id: String?): VpnEngine? = entries.firstOrNull { it.id == id }

        /**
         * 「用户选的那个」——选了但没装（或压根没选）时退回第一个已安装的。
         *
         * 没有控制入口的客户端不参与选择，旧设置里保存的此类 id 也不生效。
         */
        fun effective(selectedId: String?, installed: List<VpnEngine>): VpnEngine? {
            val selected = byId(selectedId)
            return if (selected != null && selected.supportsAutoControl && selected in installed) selected
            else installed.firstOrNull { it.supportsAutoControl }
        }

        /** 只向具备控制入口的客户端发停止指令，兜底时也跳过不支持控制的客户端。 */
        fun stopCandidates(preferred: VpnEngine, installed: List<VpnEngine>): List<VpnEngine> =
            if (!preferred.supportsAutoControl) emptyList()
            else (listOf(preferred) + installed).distinct().filter { it.supportsAutoControl }
    }
}
