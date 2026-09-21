package com.example.composestarter.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.composestarter.MainActivity
import com.example.composestarter.data.SettingsStore

/**
 * 开机自启：拉起监控服务，并在允许的情况下把界面带到前台。
 *
 * Android 10 起禁止后台随意启动 Activity，只有拿到「悬浮窗」权限
 * （SYSTEM_ALERT_WINDOW）才允许，因此这里会先检查再启动。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        SettingsStore.attach(context)
        if (SettingsStore.serviceEnabled.value) {
            runCatching { MonitorService.start(context) }
        }
        Watchdog.schedule(context)

        if (Settings.canDrawOverlays(context)) {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
            }
        }
    }
}
