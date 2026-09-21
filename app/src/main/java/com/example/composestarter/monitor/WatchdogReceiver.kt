package com.example.composestarter.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.composestarter.data.SettingsStore

/**
 * 看门狗闹钟到点：确认服务还应该运行就把它拉回来，并安排下一次检查。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 先把下一次检查排上：闹钟是一次性的，链条一旦断了就再也起不来。
        Watchdog.schedule(context)

        SettingsStore.attach(context)
        if (!SettingsStore.serviceEnabled.value) return
        if (!MonitorPermissions.hasUsageAccess(context)) return

        Log.d("MonitorService", "看门狗触发，检查监控服务")
        runCatching { MonitorService.start(context) }
    }
}
