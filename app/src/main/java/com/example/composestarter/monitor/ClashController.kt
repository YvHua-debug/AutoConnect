package com.example.composestarter.monitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * 通过 Clash Meta for Android 官方开放的外部控制入口启动 VPN。
 *
 * Clash Meta for Android 在清单里导出了
 * `com.github.kr328.clash.ExternalControlActivity`（exported=true、无权限限制、
 * 透明主题且 noHistory），支持启动 / 停止 / 切换三个动作。这里只用启动方向：
 * 真机实测（Redmi K80 / HyperOS 3）停止动作时灵时不灵（Clash 进程被冻结时经常没反应），
 * 不可靠的按钮不如没有，关闭 VPN 交给用户在 Clash 里手动操作。
 *
 * 该 Activity 没有界面，启动后立即结束，因此整个过程对用户不可见。
 *
 * 注意两点系统限制：
 * 1. 首次启动 VPN 时系统会弹出一次 VPN 授权确认框，这是 Android 的硬性要求，
 *    授权一次之后不再出现；
 * 2. 从后台启动 Activity 需要「悬浮窗」权限（Android 10+ 的后台启动限制）。
 */
class ClashController(private val context: Context) {

    fun isInstalled(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                CLASH_PACKAGE,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(CLASH_PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    /** 启动 Clash 的 VPN 服务，返回是否成功发出控制指令。 */
    fun start(): Boolean = send(ACTION_START)

    private fun send(action: String): Boolean = runCatching {
        val intent = Intent(action).apply {
            setClassName(CLASH_PACKAGE, CLASH_EXTERNAL_CONTROL_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        context.startActivity(intent)
        Log.i(TAG, "已发出控制指令 ${action.substringAfterLast('.')}")
        true
    }.getOrElse { error ->
        Log.w(TAG, "控制指令发送失败", error)
        false
    }

    companion object {
        private const val TAG = "MonitorService"

        const val CLASH_PACKAGE = "com.github.metacubex.clash.meta"

        private const val CLASH_EXTERNAL_CONTROL_ACTIVITY = "com.github.kr328.clash.ExternalControlActivity"
        private const val ACTION_START = "com.github.metacubex.clash.meta.action.START_CLASH"
    }
}
