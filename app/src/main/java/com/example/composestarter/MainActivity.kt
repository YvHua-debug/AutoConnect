package com.example.composestarter

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.composestarter.data.SettingsStore
import com.example.composestarter.monitor.ClashController
import com.example.composestarter.monitor.MonitorPermissions
import com.example.composestarter.monitor.MonitorRepository
import com.example.composestarter.monitor.MonitorService
import com.example.composestarter.monitor.Watchdog
import com.example.composestarter.ui.MonitorScreen
import com.example.composestarter.ui.theme.ComposeStarterTheme

/**
 * 应用入口，负责三件事：
 * 1. 保持屏幕常亮，让监控界面始终处于可见状态；
 * 2. 已获得「使用情况访问权限」时自动拉起监控服务；
 * 3. 承载 Compose 界面。
 */
class MainActivity : ComponentActivity() {

    private var permissions by mutableStateOf(MonitorPermissions.Snapshot())

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SettingsStore.attach(this)
        Watchdog.schedule(this)
        refreshPermissions()
        autoStartMonitoring()

        setContent {
            ComposeStarterTheme {
                val state by MonitorRepository.state.collectAsState()
                val autoStartClash by SettingsStore.autoStartClash.collectAsState()
                val customApps by SettingsStore.customApps.collectAsState()
                MonitorScreen(
                    state = state,
                    permissions = permissions,
                    autoStartClash = autoStartClash,
                    customApps = customApps,
                    showStatusNotification = permissions.statusNotificationVisible,
                    onToggleService = { enabled ->
                        SettingsStore.setServiceEnabled(enabled)
                        if (enabled) {
                            MonitorService.start(this)
                            Watchdog.schedule(this)
                        } else {
                            MonitorService.stop(this)
                            Watchdog.cancel(this)
                        }
                    },
                    onToggleClashAutoStart = { enabled -> SettingsStore.setAutoStartClash(enabled) },
                    onToggleShowStatusNotification = {
                        // 应用没法自己隐藏前台服务的通知，只能把用户送到系统里那条通道的设置页
                        runCatching {
                            startActivity(MonitorPermissions.statusChannelSettingsIntent(this))
                        }
                    },
                    onStartClashNow = { startClashNow() },
                    onOpenIntent = { intent -> runCatching { startActivity(intent) } },
                    onOpenBatterySettings = {
                        runCatching { startActivity(MonitorPermissions.batterySettingsIntent(this)) }
                    },
                    onOpenAutoStartSettings = {
                        runCatching { startActivity(MonitorPermissions.autoStartSettingsIntent(this)) }
                    },
                    onOpenBackgroundPopupSettings = {
                        runCatching {
                            startActivity(MonitorPermissions.backgroundPopupSettingsIntent(this))
                        }
                    },
                    onRequestNotification = { requestNotificationPermission() },
                    onClearHits = { MonitorRepository.clearHits() },
                    onAddCustomApp = { packageName, displayName ->
                        SettingsStore.addCustomApp(packageName, displayName)
                    },
                    onRemoveCustomApp = { packageName ->
                        SettingsStore.removeCustomApp(packageName)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val miui = MonitorPermissions.isMiui(this)
        permissions = MonitorPermissions.Snapshot(
            usageAccess = MonitorPermissions.hasUsageAccess(this),
            overlay = MonitorPermissions.hasOverlayAccess(this),
            notification = MonitorPermissions.hasNotificationAccess(this),
            batteryUnrestricted = MonitorPermissions.hasBatteryUnrestricted(this),
            miui = miui,
            statusNotificationVisible = MonitorPermissions.isStatusNotificationVisible(this),
            autoStart = if (miui) {
                MonitorPermissions.miuiPermission(this, MonitorPermissions.MIUI_OP_AUTO_START)
            } else {
                null
            },
            backgroundPopup = if (miui) {
                MonitorPermissions.miuiPermission(this, MonitorPermissions.MIUI_OP_BACKGROUND_POPUP)
            } else {
                null
            },
        )
        MonitorRepository.setClashInstalled(ClashController(this).isInstalled())
    }

    private fun autoStartMonitoring() {
        if (SettingsStore.serviceEnabled.value && MonitorPermissions.hasUsageAccess(this)) {
            MonitorService.start(this)
        }
    }

    /** 手动触发一次 Clash Meta 的 VPN 启动，方便在没有命中名单时验证联动是否可用。 */
    private fun startClashNow() {
        val sent = ClashController(this).start()
        Toast.makeText(
            this,
            if (sent) R.string.clash_start_sent else R.string.clash_start_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

}
