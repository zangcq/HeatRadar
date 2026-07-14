package com.example.heatradar.feature.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.heatradar.R
import com.example.heatradar.core.common.AppResourceSnapshot
import com.example.heatradar.core.common.DeviceStateSnapshot
import com.example.heatradar.core.monitor.MonitorState
import com.example.heatradar.core.monitor.ThermalZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAppClick: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasUsagePermission by viewModel.hasUsagePermission.collectAsStateWithLifecycle()
    val dataSource by viewModel.dataSource.collectAsStateWithLifecycle()
    val daemonStatus by viewModel.daemonStatus.collectAsStateWithLifecycle()
    val showSystem by viewModel.showSystemProcesses.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshPermission()
        viewModel.deployDaemonScript()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_dashboard)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!hasUsagePermission) {
                item {
                    PermissionCard(onGrantClick = { viewModel.openUsageAccessSettings() })
                }
            }

            item {
                DataSourceCard(
                    dataSource = dataSource,
                    daemonRunning = daemonStatus.isRunning,
                    daemonPid = daemonStatus.pid,
                    adbCommand = viewModel.daemonAdbCommand,
                    onCopyCommand = { viewModel.copyDaemonCommand() }
                )
            }

            item { DeviceStateCard(uiState.monitorState) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "应用资源占用",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    FilterChip(
                        selected = showSystem,
                        onClick = { viewModel.toggleShowSystemProcesses() },
                        label = {
                            Text(
                                text = "系统",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            items(uiState.cpuTop, key = { it.packageName }) { snapshot ->
                AppResourceCard(snapshot, onClick = { onAppClick(snapshot.packageName) })
            }
        }
    }
}

@Composable
private fun DataSourceCard(
    dataSource: String,
    daemonRunning: Boolean,
    daemonPid: Int,
    adbCommand: String,
    onCopyCommand: () -> Unit
) {
    when {
        dataSource == "daemon" -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE8F5E9)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "本地守护进程运行中 (PID: $daemonPid)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        dataSource == "shizuku" -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF1565C0),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Shizuku 服务已连接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF1565C0),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        else -> {
            DaemonSetupCard(
                adbCommand = adbCommand,
                onCopyCommand = onCopyCommand,
                isLimited = dataSource == "usagestats" || dataSource == "system" || dataSource == "self"
            )
        }
    }
}

@Composable
private fun DaemonSetupCard(
    adbCommand: String,
    onCopyCommand: () -> Unit,
    isLimited: Boolean
) {
    var copied by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLimited) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isLimited) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isLimited) "数据不完整" else "一键启动",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isLimited) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (isLimited) {
                Text(
                    text = "无法获取其他应用的实时 CPU/内存数据。请通过电脑连接手机，执行以下命令启动本地守护进程（无需安装额外 App）：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            } else {
                Text(
                    text = "只需执行一次以下 ADB 命令即可查看所有应用的真实 CPU 和内存数据（手机重启后需重新执行）：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.06f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = adbCommand,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (isLimited) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    )
                    IconButton(
                        onClick = {
                            onCopyCommand()
                            copied = true
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制命令",
                            tint = if (copied) Color(0xFF2E7D32)
                            else (if (isLimited) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (copied) {
                Text(
                    text = "✓ 已复制到剪贴板",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF2E7D32)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "💡 使用方法：手机通过 USB 连接电脑，在终端中粘贴执行该命令后返回此页面即可看到完整数据。",
                style = MaterialTheme.typography.bodySmall,
                color = (if (isLimited) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun PermissionCard(onGrantClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "需要使用情况访问权限",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "授予权限后才能查看其他应用的使用情况和资源占用数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onGrantClick) {
                Text("去授权")
            }
        }
    }
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap()
            } catch (e: Exception) { null }
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null, modifier = modifier.clip(RoundedCornerShape(8.dp)))
    } else {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
    }
}

fun formatMemorySize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
private fun DeviceStateCard(state: MonitorState) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "设备状态（实时）",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开"
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                CpuMiniSection(state)
                MemoryMiniSection(state)
                TempMiniSection(state)
                if (state.fps > 0f || state.powerMw > 0) {
                    ExtraMiniSection(state)
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                DeviceDetailPanel(state)
            }
        }
    }
}

@Composable
private fun CpuMiniSection(state: MonitorState) {
    val cpuColor = when {
        state.cpuPercent > 80 -> Color(0xFFD32F2F)
        state.cpuPercent > 50 -> Color(0xFFFFA000)
        else -> Color(0xFF388E3C)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "CPU", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "%.0f%%".format(state.cpuPercent),
            style = MaterialTheme.typography.titleLarge,
            color = cpuColor,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${state.cpuFreqMhz} MHz",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MemoryMiniSection(state: MonitorState) {
    val memColor = when {
        state.memPercent > 85 -> Color(0xFFD32F2F)
        state.memPercent > 70 -> Color(0xFFFFA000)
        else -> Color(0xFF1976D2)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "内存", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "%.0f%%".format(state.memPercent),
            style = MaterialTheme.typography.titleLarge,
            color = memColor,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "%d/%d MB".format(state.memUsedMb, state.memTotalMb),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TempMiniSection(state: MonitorState) {
    val tempColor = when {
        state.tempCelsius >= 45 -> Color(0xFFD32F2F)
        state.tempCelsius >= 38 -> Color(0xFFFFA000)
        state.tempCelsius >= 30 -> Color(0xFFFFD54F)
        else -> Color(0xFF388E3C)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "温度", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tempColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (state.tempCelsius > 0) "%.0f".format(state.tempCelsius) else "—",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (state.tempCelsius > 0) "℃" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExtraMiniSection(state: MonitorState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val label: String
        val value: String
        val sub: String
        val color: Color
        if (state.fps > 0f) {
            label = "FPS"
            value = "%.0f".format(state.fps)
            sub = ""
            color = when {
                state.fps >= 55f -> Color(0xFF388E3C)
                state.fps >= 30f -> Color(0xFFFFA000)
                else -> Color(0xFFD32F2F)
            }
        } else {
            label = "功耗"
            value = "%d".format(state.powerMw)
            sub = "mW"
            color = when {
                state.powerMw >= 10000 -> Color(0xFFD32F2F)
                state.powerMw >= 6000 -> Color(0xFFFFA000)
                else -> Color(0xFF388E3C)
            }
        }
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold
        )
        if (sub.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceDetailPanel(state: MonitorState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // CPU 详情
        DetailSection(title = "CPU") {
            DetailRow("使用率", "%.1f%%".format(state.cpuPercent), valueColor = getCpuDashboardColor(state.cpuPercent))
            DetailRow("平均频率", "${state.cpuFreqMhz} MHz")
            if (state.maxCpuFreqMhz > 0) {
                DetailRow("最高频率", "${state.maxCpuFreqMhz} MHz")
            }
            DetailRow("核心数", "${state.cpuFreqsMhz.size}")
            if (state.cpuFreqsMhz.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                CpuFreqBarsPanel(freqs = state.cpuFreqsMhz, maxFreq = maxOf(state.maxCpuFreqMhz, state.cpuFreqsMhz.maxOrNull() ?: 0L))
            }
        }

        // 内存详情
        DetailSection(title = "内存") {
            val usedGb = state.memUsedMb / 1024f
            val totalGb = state.memTotalMb / 1024f
            val availGb = state.memAvailableMb / 1024f
            DetailRow("已用", "%.1f GB".format(usedGb), valueColor = getMemDashboardColor(state.memPercent))
            DetailRow("可用", "%.1f GB".format(availGb))
            DetailRow("总计", "%.1f GB".format(totalGb))
            if (state.memCachedMb > 0) {
                DetailRow("缓存", "${state.memCachedMb} MB")
            }
        }

        // GPU / FPS
        if (state.gpuPercent > 0f || state.gpuFreqMhz > 0 || state.fps > 0f) {
            DetailSection(title = "GPU / 帧率") {
                if (state.gpuFreqMhz > 0) {
                    DetailRow("GPU 使用率", "%.0f%%".format(state.gpuPercent),
                        valueColor = if (state.gpuPercent >= 70f) Color(0xFFFFA000) else Color(0xFF1565C0))
                    DetailRow("GPU 频率", "${state.gpuFreqMhz} MHz")
                }
                if (state.fps > 0f) {
                    DetailRow("当前 FPS", "%.0f".format(state.fps),
                        valueColor = when {
                            state.fps >= 55f -> Color(0xFF388E3C)
                            state.fps >= 30f -> Color(0xFFFFA000)
                            else -> Color(0xFFD32F2F)
                        })
                }
            }
        }

        // 温度详情
        if (state.allTemps.isNotEmpty() || state.batteryTempCelsius > 0f) {
            DetailSection(title = "温度") {
                DetailRow("CPU 温度", "%.0f℃".format(state.tempCelsius), valueColor = getTempDashboardColor(state.tempCelsius))
                if (state.batteryTempCelsius > 0f) {
                    DetailRow("电池温度", "%.0f℃".format(state.batteryTempCelsius), valueColor = getTempDashboardColor(state.batteryTempCelsius))
                }
                if (state.allTemps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("传感器温度", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    val shown = state.allTemps.filter { it.tempCelsius in 1..99 }.take(10)
                    val cols = 2
                    for (r in 0 until (shown.size + cols - 1) / cols) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            for (c in 0 until cols) {
                                val idx = r * cols + c
                                if (idx < shown.size) {
                                    val t = shown[idx]
                                    Box(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(getTempDashboardColor(t.tempCelsius.toFloat())))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Column {
                                                Text(t.type.take(12), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                                Text("${t.tempCelsius}℃",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = getTempDashboardColor(t.tempCelsius.toFloat()))
                                            }
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                    }
                }
            }
        }

        // 功耗详情
        if (state.powerMw > 0) {
            DetailSection(title = "功耗 / 电池") {
                val powerColor = when {
                    state.powerMw >= 10000 -> Color(0xFFD32F2F)
                    state.powerMw >= 6000 -> Color(0xFFFFA000)
                    else -> Color(0xFF388E3C)
                }
                DetailRow("实时功耗", "${state.powerMw} mW", valueColor = powerColor)
                DetailRow("电流", "${state.currentMa} mA")
                if (state.voltageV > 0f) {
                    DetailRow("电压", "%.2f V".format(state.voltageV))
                }
                if (state.batteryCapacity > 0) {
                    DetailRow("电量", "${state.batteryCapacity}%%")
                }
                if (state.batteryStatus.isNotEmpty()) {
                    val statusColor = if (state.batteryStatus.equals("Charging", ignoreCase = true))
                        Color(0xFF388E3C) else Color(0xFFFFA000)
                    DetailRow("状态", state.batteryStatus, valueColor = statusColor)
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        .padding(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@Composable
private fun CpuFreqBarsPanel(freqs: List<Long>, maxFreq: Long) {
    val maxF = maxFreq.coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        freqs.take(16).forEach { f ->
            val ratio = f.toFloat() / maxF.toFloat()
            val hRatio = ratio.coerceIn(0.05f, 1f)
            val color = when {
                ratio >= 0.8f -> Color(0xFFD32F2F)
                ratio >= 0.5f -> Color(0xFFFFA000)
                ratio >= 0.2f -> Color(0xFFFFD54F)
                else -> Color(0xFF388E3C)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((28 * hRatio).dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(color)
            )
        }
    }
}

private fun getCpuDashboardColor(p: Float) = when {
    p >= 80f -> Color(0xFFD32F2F)
    p >= 50f -> Color(0xFFFFA000)
    else -> Color(0xFF388E3C)
}
private fun getMemDashboardColor(p: Float) = when {
    p >= 85f -> Color(0xFFD32F2F)
    p >= 70f -> Color(0xFFFFA000)
    else -> Color(0xFF1976D2)
}
private fun getTempDashboardColor(t: Float) = when {
    t >= 45f -> Color(0xFFD32F2F)
    t >= 38f -> Color(0xFFFFA000)
    t >= 30f -> Color(0xFFFFD54F)
    else -> Color(0xFF388E3C)
}

@Composable
private fun AppResourceCard(snapshot: AppResourceSnapshot, onClick: () -> Unit) {
    val cpuColor = when {
        snapshot.cpuPercent > 50 -> Color(0xFFD32F2F)
        snapshot.cpuPercent > 20 -> Color(0xFFFFA000)
        else -> Color(0xFF388E3C)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(
                        packageName = snapshot.packageName,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = snapshot.appName, style = MaterialTheme.typography.bodyLarge)
                        Text(text = snapshot.packageName, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(cpuColor)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "%.0f".format(snapshot.cpuPercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { (snapshot.cpuPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp),
                    color = cpuColor
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = formatMemorySize(snapshot.memoryBytes), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
