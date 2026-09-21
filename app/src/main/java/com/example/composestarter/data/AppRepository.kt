package com.example.composestarter.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * 查询设备上已安装的应用，用于判断名单中的应用是否存在。
 */
class AppRepository(private val context: Context) {

    /** 应用显示名缓存：桌面名称几乎不变，没必要每次切换前台应用都去问 PackageManager。 */
    private val labelCache = ConcurrentHashMap<String, String>()

    /**
     * 名单里哪些包已经装了。
     *
     * 只查名单里的几十个包，比枚举全机应用（几百个 PackageInfo）轻得多，
     * 内存和查询耗时都能省下来。
     */
    fun installedAmong(packageNames: Collection<String>): Set<String> =
        packageNames.filterTo(HashSet()) { isInstalled(it) }

    fun isInstalled(packageName: String): Boolean = runCatching {
        packageInfo(packageName) != null
    }.getOrDefault(false)

    /** 读取应用在桌面上显示的名称，取不到时返回包名。 */
    fun labelOf(packageName: String): String = labelCache.getOrPut(packageName) {
        packageInfo(packageName)?.loadLabel(context.packageManager)?.toString() ?: packageName
    }

    private fun packageInfo(packageName: String): ApplicationInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
