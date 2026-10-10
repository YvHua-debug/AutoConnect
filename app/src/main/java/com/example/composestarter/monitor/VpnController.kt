package com.example.composestarter.monitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.example.composestarter.data.VpnEngine

/**
 * 把「启动 / 停止 VPN」翻译成各 VPN 客户端自己导出的外部控制入口（见 [VpnEngine]）。
 *
 * 支持控制的入口是透明 Activity，使用独立临时任务，避免拉出客户端已有的主界面。
 * 没有外部控制入口的客户端不参与联动，不从后台启动主界面。
 * 有两条系统限制是本应用绕不过去的：
 * 1. 首次启动 VPN 时系统会弹出一次 VPN 授权确认框（Android 的硬性要求），授权一次之后不再出现；
 * 2. 从后台启动 Activity 需要「悬浮窗」权限（Android 10+ 的后台启动限制），小米机型还要额外允许
 *    「后台弹出界面」，否则指令会被系统静默丢掉（详见 README 的「小米 / HyperOS」）。
 */
class VpnController(private val context: Context) {

    /** 隧道归属探测上一次的输出，内容没变就不重复打日志。 */
    private var lastProbeLog: String? = null

    fun isInstalled(engine: VpnEngine): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                engine.packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(engine.packageName, 0)
        }
        true
    }.getOrDefault(false)

    /** 设备上装了哪些可联动的客户端，顺序与 [VpnEngine] 的声明顺序一致。 */
    fun installedEngines(): List<VpnEngine> = VpnEngine.supportedEntries.filter { isInstalled(it) }

    /** 启动客户端的 VPN，返回是否成功发出控制指令。 */
    fun start(engine: VpnEngine): Boolean {
        val intent = engine.startIntent() ?: return false
        return send(engine, intent)
    }

    /** 停止客户端的 VPN，返回是否成功发出控制指令。 */
    fun stop(engine: VpnEngine): Boolean {
        val intent = engine.stopIntent() ?: return false
        return send(engine, intent)
    }

    /**
     * 当前这条隧道是哪个客户端建的（没有 VPN 时返回 null）。
     *
     * 用 `NetworkCapabilities.getOwnerUid()`（API 29+）拿隧道归属的 uid 再映射回包名。
     * 自动断开时优先停「真正在建隧道的那一个」，这样用户手动开的 VPN 也能被正确收掉；
     * **实测 HyperOS 3 / Android 17 读不到归属**：`dumpsys connectivity` 里明明写着
     * `OwnerUid: 10393`，应用侧拿到的却是 `-1`（这个字段只对特权调用方填），
     * 所以调用方必须有「换一个客户端再试」的后手（见 `MonitorService.updateAutoStop`）。
     */
    fun activeVpnEngine(): VpnEngine? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager?.getNetworkCapabilities(it) }
        return try {
            when {
                manager == null -> logProbe("拿不到 ConnectivityManager", null)
                network == null -> logProbe("activeNetwork 为空", null)
                capabilities == null -> logProbe("读不到 activeNetwork 的能力集", null)
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                    logProbe("activeNetwork 不是 VPN 隧道", null)
                else -> {
                    val ownerUid = capabilities.ownerUid
                    val packages = if (ownerUid > 0) {
                        context.packageManager.getPackagesForUid(ownerUid)?.toSet().orEmpty()
                    } else {
                        emptySet()
                    }
                    logProbe(
                        detail = "ownerUid=$ownerUid packages=$packages",
                        engine = VpnEngine.entries.firstOrNull { it.packageName in packages },
                    )
                }
            }
        } catch (error: Throwable) {
            // 别把探测失败吞掉：`getOwnerUid()` 在个别 ROM 上会抛，吞了就只能看到「未知」
            Log.w(TAG, "隧道归属探测失败，退回用户选中的客户端", error)
            null
        }
    }

    /**
     * 打一行归属探测结果（内容没变就不重复打），并把认出的客户端原样返回。
     *
     * 留着这行日志是有意为之：设备到底填没填 `ownerUid`、认出了谁，adb logcat 一眼就能看到，
     * 不用去猜「为什么自动断开停错了客户端」。
     */
    private fun logProbe(detail: String, engine: VpnEngine?): VpnEngine? {
        val line = "$detail -> ${engine?.displayName ?: "未知"}"
        if (line != lastProbeLog) {
            lastProbeLog = line
            Log.d(TAG, "隧道归属探测：$line")
        }
        return engine
    }

    private fun send(engine: VpnEngine, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        Log.i(TAG, "${engine.displayName}：已发出控制指令 ${intent.action ?: intent.data}")
        true
    }.getOrElse { error ->
        Log.w(TAG, "${engine.displayName}：控制指令发送失败", error)
        false
    }

    private companion object {
        private const val TAG = "MonitorService"
    }
}
