package com.example.composestarter.monitor

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
    /** 是否已经为这条记录发出过拉起 Clash 的指令（结果另见 [clashVpnEstablished]）。 */
    val clashTriggered: Boolean = false,
    /** 正在等 Clash 把隧道建起来。 */
    val clashConnecting: Boolean = false,
    /** 补连之后回读到的 VPN 结果，null 表示仍在确认中。 */
    val clashVpnEstablished: Boolean? = null,
    /** 看起来是系统把「后台拉起 Clash」拦掉了（没有悬浮窗权限 / 小米没开「后台弹出界面」）。 */
    val clashBlocked: Boolean = false,
)

data class MonitorUiState(
    val serviceRunning: Boolean = false,
    val vpnActive: Boolean = false,
    val foregroundPackage: String? = null,
    val foregroundLabel: String? = null,
    val installedPackages: Set<String> = emptySet(),
    val clashInstalled: Boolean = false,
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

    private const val MAX_HITS = 50

    private val hitIds = AtomicLong(0L)

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    fun nextHitId(): Long = hitIds.incrementAndGet()

    fun setServiceRunning(running: Boolean) = _state.update { it.copy(serviceRunning = running) }

    fun setVpnActive(active: Boolean) = _state.update { it.copy(vpnActive = active) }

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

    fun setClashInstalled(installed: Boolean) =
        _state.update { it.copy(clashInstalled = installed) }

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
}
