package com.example.composestarter.monitor

import com.example.composestarter.data.VpnEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** 一次「打开了名单内应用」的记录。 */
data class DetectionHit(
    val id: Long,
    val packageName: String,
    val displayName: String,
    val timestamp: Long,
    val vpnActive: Boolean,
    /** 是否已经为这条记录发出过拉起 VPN 的指令（结果另见 [vpnEstablished]）。 */
    val vpnTriggered: Boolean = false,
    /** 正在等 VPN 客户端把隧道建起来。 */
    val vpnConnecting: Boolean = false,
    /** 补连之后回读到的 VPN 结果，null 表示仍在确认中。 */
    val vpnEstablished: Boolean? = null,
    /** 看起来是系统把「后台拉起 VPN 客户端」拦掉了（没有悬浮窗权限 / 小米没开「后台弹出界面」）。 */
    val vpnBlocked: Boolean = false,
    /**
     * 离开这次记录对应的应用之后，VPN 有没有被自动断开。
     * null = 还没到需要断开的时候，true / false = 断开尝试的结果。
     */
    val vpnStopped: Boolean? = null,
)

data class MonitorUiState(
    val serviceRunning: Boolean = false,
    val vpnActive: Boolean = false,
    val foregroundPackage: String? = null,
    val foregroundLabel: String? = null,
    val installedPackages: Set<String> = emptySet(),
    /** 设备上装了哪些可联动的 VPN 客户端（[VpnEngine.id]）。 */
    val engineInstalled: Set<String> = emptySet(),
    /** 当前生效的客户端（用户选的，没选/没装则自动取第一个已安装的），null 表示一台都没有。 */
    val engineSelected: VpnEngine? = null,
    /** 当前这条隧道实际是哪个客户端建的；读不到归属时为 null。 */
    val engineOwner: VpnEngine? = null,
    val hits: List<DetectionHit> = emptyList(),
    /** 最近一次轮询的时间、累计次数，以及观察到的最长间隔。 */
    val lastPollAt: Long = 0L,
    val pollCount: Long = 0L,
    /** 最长的一次「两轮之间隔了多久」，被系统冻结过就会明显变大。 */
    val maxPollGapMs: Long = 0L,
)

/**
 * 进程内共享状态：前台服务写入，Compose 界面读取。
 */
object MonitorRepository {

    internal const val MAX_HITS = 50

    private val hitIds = AtomicLong(0L)

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    fun nextHitId(): Long = hitIds.incrementAndGet()

    fun setServiceRunning(running: Boolean) = _state.update {
        if (it.serviceRunning == running) it else it.copy(serviceRunning = running)
    }

    fun setVpnActive(active: Boolean) = _state.update {
        if (it.vpnActive == active) it else it.copy(vpnActive = active)
    }

    /** 每完成一轮轮询上报一次，界面据此显示「后台是否还活着」。 */
    fun onPoll(at: Long, gapMs: Long) = _state.update {
        it.copy(
            lastPollAt = at,
            pollCount = it.pollCount + 1,
            maxPollGapMs = maxOf(it.maxPollGapMs, gapMs),
        )
    }

    fun setInstalledPackages(packages: Set<String>) =
        _state.update { it.copy(installedPackages = packages) }

    fun setEngines(installed: List<VpnEngine>, selected: VpnEngine?) = _state.update {
        it.copy(engineInstalled = installed.map(VpnEngine::id).toSet(), engineSelected = selected)
    }

    fun setEngineOwner(engine: VpnEngine?) = _state.update {
        if (it.engineOwner == engine) it else it.copy(engineOwner = engine)
    }

    fun onForegroundChanged(packageName: String?, label: String?) = _state.update {
        if (it.foregroundPackage == packageName) {
            it
        } else {
            it.copy(foregroundPackage = packageName, foregroundLabel = label)
        }
    }

    fun addHit(hit: DetectionHit) = _state.update {
        it.copy(hits = (listOf(hit) + it.hits).take(MAX_HITS))
    }

    /**
     * 按 id 改写一条命中记录。
     *
     * 「补连 VPN」是个持续过程（发出指令 → 等隧道 → 回读到真实结果），中途要多次回写同一条记录，
     * 所以统一走这里，而不是每加一个状态字段就再来一个专用函数。
     */
    fun updateHit(hitId: Long, transform: (DetectionHit) -> DetectionHit) = _state.update { current ->
        current.copy(
            hits = current.hits.map { hit -> if (hit.id == hitId) transform(hit) else hit },
        )
    }

    fun clearHits() = _state.update { it.copy(hits = emptyList()) }

    /**
     * 把「离开某个名单应用之后 VPN 有没有断开」写回它最近的那条命中记录。
     *
     * 找到的是这个包最新的一条记录——用户每次重新打开名单应用都会新记一条，
     * 所以它正好对应当前这一次使用。
     */
    fun markLatestHitStopped(packageName: String, stopped: Boolean) = _state.update { current ->
        val target = current.hits.firstOrNull { it.packageName == packageName } ?: return@update current
        current.copy(
            hits = current.hits.map {
                if (it.id == target.id) it.copy(vpnStopped = stopped) else it
            },
        )
    }
}
