package com.example.composestarter.monitor

import android.os.Build
import android.os.Process
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * HyperOS 贴边收起的小窗会收到 ACTIVITY_STOPPED，但仍属于正在使用的窗口。
 * 只补充系统当前明确标记 inPinMode 的窗口，不把最近任务或普通后台应用算进去。
 * 厂商接口不存在 / 读取失败时返回空集合，继续使用原来的 UsageEvents 判断。
 */
internal class MiuiFreeformDetector {
    private val reader: Reader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { Reader() }.getOrNull()
    } else null
    private var failureLogged = false
    private var lastPackages = emptySet<String>()

    fun pinnedPackages(): Set<String> {
        val reader = reader ?: return emptySet()
        val packages = runCatching { reader.pinnedPackages() }.getOrElse {
            if (!failureLogged) {
                Log.w(TAG, "无法读取贴边小窗状态，继续使用应用生命周期判断", it)
                failureLogged = true
            }
            // 每轮读取最新快照，不能因上次读到过贴边窗口而永久保留 VPN。
            emptySet()
        }
        if (packages != lastPackages) {
            Log.d(TAG, "贴边小窗：$packages")
            lastPackages = packages
        }
        return packages
    }

    private class Reader {
        private val manager = Class.forName("miui.app.MiuiFreeFormManager")
        private val query: Method = manager.getMethod(
            "getAllFreeFormStackInfosOnDisplay", Int::class.javaPrimitiveType,
        )
        private val info = Class.forName("miui.app.MiuiFreeFormManager\$MiuiFreeFormStackInfo")
        private val packageName: Field = info.getField("packageName")
        private val inPinMode: Field = info.getField("inPinMode")
        private val userId: Field = info.getField("userId")

        // Android UID 按每用户 100000 个分段；排除其它用户 / 应用分身的窗口。
        private val currentUserId = Process.myUid() / 100_000

        fun pinnedPackages(): Set<String> {
            // -1 表示所有显示屏，避免把外接屏的贴边窗口误判为已退出。
            val windows = query.invoke(null, -1) as? List<*> ?: return emptySet()
            return buildSet {
                for (window in windows) {
                    if (window != null && userId.getInt(window) == currentUserId && inPinMode.getBoolean(window)) {
                        (packageName.get(window) as? String)?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "MiuiFreeformDetector"
    }
}
