package com.example.heatradar.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.heatradar.R
import com.example.heatradar.core.common.AppResourceSnapshot
import com.example.heatradar.core.common.DeviceStateSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAppClick: (String) -> Unit,
    onNavigateToTrends: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasUsagePermission by viewModel.hasUsagePermission.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshPermission()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_dashboard)) },
                actions = {
                    IconButton(onClick = onNavigateToTrends) {
                        Icon(Icons.Default.TrendingUp, contentDescription = "趋势")
                    }
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

            item { DeviceStateCard(uiState.deviceState) }

            item {
                Text(
                    text = "应用资源占用",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            items(uiState.cpuTop, key = { it.packageName }) { snapshot ->
                AppResourceCard(snapshot, onClick = { onAppClick(snapshot.packageName) })
            }
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
private fun DeviceStateCard(state: DeviceStateSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "设备状态（实时）",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CpuMiniSection(state)
                MemoryMiniSection(state)
                TemperatureMiniSection(state)
            }
        }
    }
}

@Composable
private fun CpuMiniSection(state: DeviceStateSnapshot) {
    val cpuColor = when {
        state.cpuUsagePercent > 80 -> Color(0xFFD32F2F)
        state.cpuUsagePercent > 50 -> Color(0xFFFFA000)
        else -> Color(0xFF388E3C)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "CPU", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "%.0f%%".format(state.cpuUsagePercent),
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
private fun MemoryMiniSection(state: DeviceStateSnapshot) {
    val memColor = when {
        state.memoryUsagePercent > 85 -> Color(0xFFD32F2F)
        state.memoryUsagePercent > 70 -> Color(0xFFFFA000)
        else -> Color(0xFF1976D2)
    }
    val usedGb = state.usedMemoryBytes / 1_073_741_824.0
    val totalGb = state.totalMemoryBytes / 1_073_741_824.0

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "内存", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "%.0f%%".format(state.memoryUsagePercent),
            style = MaterialTheme.typography.titleLarge,
            color = memColor,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "%.1f/%.1f GB".format(usedGb, totalGb),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TemperatureMiniSection(state: DeviceStateSnapshot) {
    val tempColor = when {
        state.temperatureCelsius >= 50 -> Color(0xFFD32F2F)
        state.temperatureCelsius >= 42 -> Color(0xFFFFA000)
        state.temperatureCelsius >= 35 -> Color(0xFFFFD54F)
        else -> Color(0xFF388E3C)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "温度", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tempColor)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (state.temperatureCelsius > 0) "%.0f".format(state.temperatureCelsius) else "—",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (state.temperatureCelsius > 0) "℃" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppResourceCard(snapshot: AppResourceSnapshot, onClick: () -> Unit) {
    val cpuColor = when {
        snapshot.cpuPercent > 50 -> Color(0xFFD32F2F)
        snapshot.cpuPercent > 20 -> Color(0xFFFFA000)
        else -> Color(0xFF388E3C)
    }
    val memMb = snapshot.memoryBytes / 1_000_000f

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
                Column {
                    Text(text = snapshot.appName, style = MaterialTheme.typography.bodyLarge)
                    Text(text = snapshot.packageName, style = MaterialTheme.typography.labelSmall)
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LinearProgressIndicator(
                    progress = { (snapshot.cpuPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp),
                    color = cpuColor
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = "%.1f MB".format(memMb), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
