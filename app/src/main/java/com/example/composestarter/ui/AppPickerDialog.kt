package com.example.composestarter.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.composestarter.R
import com.example.composestarter.data.InstalledApp
import com.example.composestarter.data.listLaunchableApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「添加监控应用」选择器：列出设备上带桌面图标的应用，已经在名单里的会被过滤掉。
 *
 * 枚举应用要走 PackageManager，所以放到 IO 线程上加载，加载期间显示提示。
 */
@Composable
fun AppPickerDialog(
    excludedPackages: Set<String>,
    onPick: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { listLaunchableApps(context) }
        loading = false
    }

    val candidates = remember(apps, excludedPackages, query) {
        val keyword = query.trim().lowercase()
        apps.filter { app ->
            app.packageName !in excludedPackages &&
                (
                    keyword.isEmpty() ||
                        app.label.lowercase().contains(keyword) ||
                        app.packageName.lowercase().contains(keyword)
                    )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.app_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        start = 24.dp,
                        end = 24.dp,
                        top = 20.dp,
                        bottom = 12.dp,
                    ),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(text = stringResource(R.string.app_picker_search)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (loading) {
                    PickerHint(text = stringResource(R.string.app_picker_loading))
                } else if (candidates.isEmpty()) {
                    PickerHint(text = stringResource(R.string.app_picker_empty))
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(items = candidates, key = { it.packageName }) { app ->
                            AppPickerRow(app = app, onClick = { onPick(app) })
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(start = 24.dp),
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(text = stringResource(R.string.action_close))
                }
            }
        }
    }
}

@Composable
private fun PickerHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

@Composable
private fun AppPickerRow(app: InstalledApp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon = app.icon)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 应用图标已经在 [listLaunchableApps] 里缩放到固定尺寸，这里只负责画出来。 */
@Composable
private fun AppIcon(icon: Bitmap?) {
    val imageBitmap = remember(icon) { icon?.asImageBitmap() }
    if (imageBitmap == null) {
        Spacer(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    }
}
