package com.example.composestarter.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.composestarter.R
import com.example.composestarter.data.VpnAppCatalog
import com.example.composestarter.data.VpnRequiredApp
import com.example.composestarter.monitor.DetectionHit
import com.example.composestarter.monitor.MonitorPermissions
import com.example.composestarter.monitor.MonitorUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    state: MonitorUiState,
    permissions: MonitorPermissions.Snapshot,
    autoStartClash: Boolean,
    customApps: List<VpnRequiredApp>,
    showStatusNotification: Boolean,
    onToggleService: (Boolean) -> Unit,
    onToggleShowStatusNotification: (Boolean) -> Unit,
    onToggleClashAutoStart: (Boolean) -> Unit,
    onStartClashNow: () -> Unit,
    onOpenIntent: (Intent) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit,
    onOpenBackgroundPopupSettings: () -> Unit,
    onRequestNotification: () -> Unit,
    onClearHits: () -> Unit,
    onAddCustomApp: (String, String) -> Unit,
    onRemoveCustomApp: (String) -> Unit,
) {
    val context = LocalContext.current
    val catalog = remember(customApps) { VpnAppCatalog.all(customApps) }
    val groupedApps = remember(customApps) { VpnAppCatalog.grouped(customApps) }
    val excludedPackages = remember(catalog) { catalog.map { it.packageName }.toSet() }

    // 二级折叠区块的展开状态：默认全部收起，标题行上的摘要足够看清状态。
    var permissionsExpanded by rememberSaveable { mutableStateOf(false) }
    var clashExpanded by rememberSaveable { mutableStateOf(false) }
    var catalogExpanded by rememberSaveable { mutableStateOf(false) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    val addAppLabel = stringResource(R.string.action_add_app)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ServiceCard(
                    state = state,
                    showStatusNotification = showStatusNotification,
                    onToggleService = onToggleService,
                    onToggleShowStatusNotification = onToggleShowStatusNotification,
                )
            }

            item(key = "permissions") {
                val pending = permissionPendingCount(permissions)
                CollapsibleCard(
                    title = stringResource(R.string.monitor_section_permissions),
                    subtitle = permissionSummary(permissions, pending),
                    subtitleColor = if (pending == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    expanded = permissionsExpanded,
                    onToggleExpanded = { permissionsExpanded = !permissionsExpanded },
                ) {
                    PermissionSection(
                        permissions = permissions,
                        onOpenUsageAccess = { onOpenIntent(MonitorPermissions.usageAccessSettingsIntent()) },
                        onOpenBatterySettings = onOpenBatterySettings,
                        onOpenAutoStartSettings = onOpenAutoStartSettings,
                        onOpenBackgroundPopupSettings = onOpenBackgroundPopupSettings,
                        onOpenOverlay = { onOpenIntent(MonitorPermissions.overlaySettingsIntent(context)) },
                        onOpenNotification = onRequestNotification,
                    )
                }
            }

            item { LiveStatusCard(state = state) }

            item(key = "clash") {
                CollapsibleCard(
                    title = stringResource(R.string.clash_section),
                    subtitle = if (state.clashInstalled) {
                        stringResource(R.string.clash_installed)
                    } else {
                        stringResource(R.string.clash_not_installed)
                    },
                    subtitleColor = if (state.clashInstalled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    expanded = clashExpanded,
                    onToggleExpanded = { clashExpanded = !clashExpanded },
                ) {
                    ClashSection(
                        state = state,
                        autoStartClash = autoStartClash,
                        onToggleClashAutoStart = onToggleClashAutoStart,
                        onStartClashNow = onStartClashNow,
                    )
                }
            }

            item { HitsCard(state = state, onClearHits = onClearHits) }

            item(key = "catalog") {
                CollapsibleCard(
                    title = stringResource(R.string.monitor_section_catalog, catalog.size),
                    subtitle = stringResource(
                        R.string.catalog_summary,
                        VpnAppCatalog.builtInApps.size,
                        customApps.size,
                    ),
                    expanded = catalogExpanded,
                    onToggleExpanded = { catalogExpanded = !catalogExpanded },
                    trailing = {
                        // 右侧加号：把没在名单里的已安装应用加进来
                        IconButton(
                            onClick = { showAppPicker = true },
                            modifier = Modifier.semantics {
                                contentDescription = addAppLabel
                            },
                        ) {
                            Text(
                                text = "＋",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                ) {
                    groupedApps.forEach { (category, apps) ->
                        Text(
                            text = category,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                        apps.forEach { app ->
                            AppRow(
                                app = app,
                                installed = state.installedPackages.contains(app.packageName),
                                removable = !VpnAppCatalog.isBuiltIn(app.packageName),
                                onRemove = { onRemoveCustomApp(app.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            excludedPackages = excludedPackages,
            onPick = { app ->
                // 不关弹窗：选完一个接着选下一个，加过的条目会立刻从列表里消失
                onAddCustomApp(app.packageName, app.label)
            },
            onDismiss = { showAppPicker = false },
        )
    }
}

@Composable
private fun ServiceCard(
    state: MonitorUiState,
    showStatusNotification: Boolean,
    onToggleService: (Boolean) -> Unit,
    onToggleShowStatusNotification: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.monitor_section_service),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (state.serviceRunning) {
                            stringResource(R.string.monitor_service_running)
                        } else {
                            stringResource(R.string.monitor_service_stopped)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.serviceRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(checked = state.serviceRunning, onCheckedChange = onToggleService)
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            )
            ToggleRow(
                title = stringResource(R.string.monitor_show_notification),
                description = stringResource(R.string.monitor_show_notification_desc),
                checked = showStatusNotification,
                onCheckedChange = onToggleShowStatusNotification,
                enabled = true,
            )
        }
    }
}

@Composable
private fun PermissionSection(
    permissions: MonitorPermissions.Snapshot,
    onOpenUsageAccess: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAutoStartSettings: () -> Unit,
    onOpenBackgroundPopupSettings: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenNotification: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        PermissionRow(
            title = stringResource(R.string.permission_usage_access),
            description = stringResource(R.string.permission_usage_access_desc),
            granted = permissions.usageAccess,
            onAction = onOpenUsageAccess,
        )
        PermissionRow(
            title = stringResource(R.string.permission_battery),
            description = stringResource(R.string.permission_battery_desc),
            granted = permissions.batteryUnrestricted,
            onAction = onOpenBatterySettings,
        )
        if (permissions.miui) {
            PermissionRow(
                title = stringResource(R.string.permission_autostart),
                description = stringResource(R.string.permission_autostart_desc),
                granted = permissions.autoStart,
                onAction = onOpenAutoStartSettings,
            )
            PermissionRow(
                title = stringResource(R.string.permission_background_popup),
                description = stringResource(R.string.permission_background_popup_desc),
                granted = permissions.backgroundPopup,
                onAction = onOpenBackgroundPopupSettings,
            )
        }
        PermissionRow(
            title = stringResource(R.string.permission_overlay),
            description = stringResource(R.string.permission_overlay_desc),
            granted = permissions.overlay,
            onAction = onOpenOverlay,
        )
        PermissionRow(
            title = stringResource(R.string.permission_notification),
            description = stringResource(R.string.permission_notification_desc),
            granted = permissions.notification,
            onAction = onOpenNotification,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        when (granted) {
            true -> Text(
                text = stringResource(R.string.permission_granted),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            false -> TextButton(onClick = onAction) {
                Text(text = stringResource(R.string.action_grant))
            }

            // 读不到状态（ROM 没有这套查询接口）：只给入口，不谎报「没开」
            null -> TextButton(onClick = onAction) {
                Text(text = stringResource(R.string.action_open_settings))
            }
        }
    }
}

@Composable
private fun LiveStatusCard(state: MonitorUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.monitor_section_foreground),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledValue(
                label = stringResource(R.string.label_foreground_app),
                value = state.foregroundLabel ?: stringResource(R.string.value_unknown),
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabeledValue(
                label = stringResource(R.string.label_last_poll),
                value = pollHealthValue(state.lastPollAt, state.pollCount, state.maxPollGapMs),
                valueColor = pollHealthColor(state),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.label_vpn),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(12.dp))
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = if (state.vpnActive) {
                                stringResource(R.string.vpn_connected)
                            } else {
                                stringResource(R.string.vpn_disconnected)
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun pollHealthValue(lastPollAt: Long, pollCount: Long, maxPollGapMs: Long): String {
    if (lastPollAt == 0L) return stringResource(R.string.value_never_polled)
    val seconds = ((System.currentTimeMillis() - lastPollAt) / 1000).coerceAtLeast(0)
    val ago = when {
        seconds <= 5 -> stringResource(R.string.value_just_now)
        seconds < 60 -> stringResource(R.string.value_seconds_ago, seconds.toInt())
        else -> stringResource(R.string.value_minutes_ago, (seconds / 60).toInt())
    }
    val gap = when {
        maxPollGapMs < 60_000L -> stringResource(R.string.unit_seconds, (maxPollGapMs / 1000).toInt())
        else -> stringResource(R.string.unit_minutes, (maxPollGapMs / 60_000L).toInt())
    }
    return stringResource(R.string.value_last_poll, ago, pollCount, gap)
}

/** 服务在跑，但出现过 3 分钟以上的空档，说明被国产 ROM 的省电策略冻结过，标红提示。 */
@Composable
private fun pollHealthColor(state: MonitorUiState): Color? {
    if (!state.serviceRunning || state.lastPollAt == 0L) return null
    val seconds = (System.currentTimeMillis() - state.lastPollAt) / 1000
    val stalled = seconds > 120 || state.maxPollGapMs > 3 * 60_000L
    return if (stalled) MaterialTheme.colorScheme.error else null
}

@Composable
private fun LabeledValue(label: String, value: String, valueColor: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ClashSection(
    state: MonitorUiState,
    autoStartClash: Boolean,
    onToggleClashAutoStart: (Boolean) -> Unit,
    onStartClashNow: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.clash_profile_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ToggleRow(
            title = stringResource(R.string.clash_auto_start),
            description = stringResource(R.string.clash_auto_start_desc),
            checked = autoStartClash,
            onCheckedChange = onToggleClashAutoStart,
            enabled = state.clashInstalled,
        )
        if (state.clashInstalled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onStartClashNow) {
                    Text(text = stringResource(R.string.action_start_clash_now))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun HitsCard(state: MonitorUiState, onClearHits: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.monitor_section_hits),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.hits.isNotEmpty()) {
                    TextButton(onClick = onClearHits) {
                        Text(text = stringResource(R.string.action_clear))
                    }
                }
            }
            if (state.hits.isEmpty()) {
                Text(
                    text = stringResource(R.string.hits_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.hits.take(8).forEach { hit -> HitRow(hit) }
            }
        }
    }
}

@Composable
private fun HitRow(hit: DetectionHit) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeFormatter.format(Date(hit.timestamp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = hit.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        // 「正在补连」是过程态，用中性色；连上了才是主题色；失败和「被系统拦掉」用错误色。
        val (statusText, statusColor) = when {
            hit.vpnActive -> stringResource(R.string.vpn_connected) to MaterialTheme.colorScheme.primary
            hit.clashVpnEstablished == true ->
                stringResource(R.string.hit_clash_vpn_ok) to MaterialTheme.colorScheme.primary
            hit.clashConnecting ->
                stringResource(R.string.hit_clash_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
            hit.clashBlocked ->
                stringResource(R.string.hit_clash_blocked) to MaterialTheme.colorScheme.error
            hit.clashVpnEstablished == false ->
                stringResource(R.string.hit_clash_vpn_failed) to MaterialTheme.colorScheme.error
            hit.clashTriggered ->
                stringResource(R.string.hit_clash_started) to MaterialTheme.colorScheme.onSurfaceVariant
            else -> stringResource(R.string.vpn_disconnected) to MaterialTheme.colorScheme.error
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
        )
    }
}

@Composable
private fun AppRow(
    app: VpnRequiredApp,
    installed: Boolean,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (installed) {
                stringResource(R.string.app_installed)
            } else {
                stringResource(R.string.app_not_installed)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (installed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // 只有用户自己加进来的条目才能移除，内置名单删不掉
        if (removable) {
            TextButton(onClick = onRemove) {
                Text(
                    text = stringResource(R.string.action_remove_app),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(start = 16.dp),
    )
}

/**
 * 带二级折叠的卡片：标题行常驻，点标题行展开 / 收起内容。
 * [trailing] 用于在标题行右侧放额外按钮（例如监控名单的「加号」）。
 */
@Composable
private fun CollapsibleCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    subtitleColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandLabel = stringResource(
        if (expanded) R.string.action_collapse else R.string.action_expand,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                trailing?.invoke()
                ExpandIndicator(expanded = expanded, contentDescription = expandLabel)
                Spacer(modifier = Modifier.width(12.dp))
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(bottom = 12.dp), content = content)
            }
        }
    }
}

/**
 * 折叠卡片的展开指示：一个描边的小箭头，收起时朝下、展开时转成朝上。
 *
 * 手绘而不是用文字符号或图标字体：「▲ / ▼」的粗细、位置、基线都跟着系统字体走，
 * 真机上又粗又偏，和卡片里其他元素不是一个视觉体系；引入 material-icons 又白搭一个依赖。
 * 这里按 Material 的 chevron 比例画两条圆头线段，旋转用 [animateFloatAsState] 过渡，
 * 展开 / 收起时箭头是转过去的，不是瞬间跳过去。
 */
@Composable
private fun ExpandIndicator(expanded: Boolean, contentDescription: String) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "expandIndicator",
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer { rotationZ = rotation }
            .semantics { this.contentDescription = contentDescription },
    ) {
        // 画布按 24dp 设计，顶点取 (7,10) → (12,15) → (17,10)，与 Material 的 chevron 一致
        val path = Path().apply {
            moveTo(size.width * 7f / 24f, size.height * 10f / 24f)
            lineTo(size.width * 12f / 24f, size.height * 15f / 24f)
            lineTo(size.width * 17f / 24f, size.height * 10f / 24f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** 需要用户处理的权限项：4 项通用 + 小米机型特有的 2 项。 */
private fun permissionTotal(permissions: MonitorPermissions.Snapshot): Int =
    if (permissions.miui) PERMISSION_TOTAL_BASE + 2 else PERMISSION_TOTAL_BASE

/**
 * 还没处理好的项数。
 *
 * 小米那两项读不到状态时按「待处理」算（拿不准就提醒用户去确认一下），
 * 但界面那一行不会写成「未开启」，只给一个跳转入口。
 */
private fun permissionPendingCount(permissions: MonitorPermissions.Snapshot): Int {
    val states = mutableListOf(
        permissions.usageAccess,
        permissions.overlay,
        permissions.notification,
        permissions.batteryUnrestricted,
    )
    if (permissions.miui) {
        states += permissions.autoStart == true
        states += permissions.backgroundPopup == true
    }
    return states.count { !it }
}

@Composable
private fun permissionSummary(permissions: MonitorPermissions.Snapshot, pending: Int): String {
    val total = permissionTotal(permissions)
    return if (pending == 0) {
        stringResource(R.string.permission_summary_ok, total, total)
    } else {
        stringResource(R.string.permission_summary_todo, pending, total)
    }
}

private const val PERMISSION_TOTAL_BASE = 4
