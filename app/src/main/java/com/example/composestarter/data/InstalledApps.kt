package com.example.composestarter.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap

/** 「添加监控应用」选择器里的一条记录。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
)

/** 图标统一按这个尺寸缩好，避免把整张原图留在内存里。 */
private const val ICON_SIZE = 72

/**
 * 枚举设备上「有桌面图标」的应用。
 *
 * 只查带 LAUNCHER 入口的应用：比全量 `getInstalledApplications` 少很多，
 * 也天然过滤掉各种后台服务包，正好是用户想加进监控名单的那一批。
 */
fun listLaunchableApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(launcherIntent, 0)
    }

    return activities
        .asSequence()
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { info ->
            InstalledApp(
                packageName = info.packageName,
                label = info.loadLabel(packageManager).toString(),
                icon = runCatching {
                    info.loadIcon(packageManager).toBitmap(ICON_SIZE, ICON_SIZE)
                }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}
