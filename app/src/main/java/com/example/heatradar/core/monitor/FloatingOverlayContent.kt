package com.example.heatradar.core.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

@Composable
fun MonitorTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF4FC3F7),
        secondary = Color(0xFF81C784),
        tertiary = Color(0xFFFFB74D),
        background = Color(0xEE12121A),
        surface = Color(0xCC1A1A2A),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalTextStyle provides TextStyle(
                fontFamily = null,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp
            )
        ) {
            content()
        }
    }
}

@Composable
fun FloatingOverlayContent(
    state: MonitorState,
    isCollapsed: Boolean = false,
    alertLevel: AlertLevel = AlertLevel.NORMAL,
    onClose: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onCollapse: () -> Unit = {},
    onExpand: () -> Unit = {}
) {
    MonitorTheme {
        if (isCollapsed) {
            CollapsedBubble(state = state, alertLevel = alertLevel, onDrag = onDrag, onDragEnd = onDragEnd, onClick = onExpand)
        } else {
            ExpandedPanel(state = state, alertLevel = alertLevel, onClose = onClose, onCollapse = onCollapse, onDrag = onDrag, onDragEnd = onDragEnd)
        }
    }
}

private fun getCpuColor(percent: Float): Color {
    return when {
        percent >= 80f -> Color(0xFFEF5350)
        percent >= 60f -> Color(0xFFFFB74D)
        percent >= 30f -> Color(0xFFFFEE58)
        else -> Color(0xFF66BB6A)
    }
}

private fun getTempColor(temp: Float): Color {
    return when {
        temp >= 45f -> Color(0xFFEF5350)
        temp >= 38f -> Color(0xFFFFB74D)
        temp >= 30f -> Color(0xFFFFEE58)
        else -> Color(0xFF66BB6A)
    }
}

@Composable
private fun CollapsedBubble(
    state: MonitorState,
    alertLevel: AlertLevel,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit
) {
    var dragStarted by remember { mutableStateOf(false) }
    val bgColor = when (alertLevel) {
        AlertLevel.CRITICAL -> Color(0xFFEF5350)
        AlertLevel.WARNING -> Color(0xFFFFB74D)
        AlertLevel.NORMAL -> getCpuColor(state.cpuPercent)
    }
    val borderColor = when (alertLevel) {
        AlertLevel.CRITICAL -> Color(0xFFEF5350).copy(alpha = 0.8f)
        AlertLevel.WARNING -> Color(0xFFFFB74D).copy(alpha = 0.6f)
        AlertLevel.NORMAL -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xD91A1A2A))
            .alpha(0.9f)
            .then(
                if (alertLevel != AlertLevel.NORMAL) {
                    Modifier.background(borderColor.copy(alpha = 0.15f))
                } else Modifier
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragStarted = true },
                    onDrag = { _, dragAmount -> onDrag(dragAmount.x, dragAmount.y) },
                    onDragEnd = { dragStarted = false; onDragEnd() },
                    onDragCancel = { dragStarted = false; onDragEnd() }
                )
            }
            .clickable(enabled = !dragStarted) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = String.format("%.0f%%", state.cpuPercent),
                color = bgColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 14.sp
            )
            Text(
                text = "CPU",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 8.sp,
                lineHeight = 8.sp
            )
        }
    }
}

private const val HISTORY_SIZE = 40

private class HistoryBuffer(val size: Int) {
    val cpu = ArrayDeque<Float>(size)
    val mem = ArrayDeque<Float>(size)
    val gpu = ArrayDeque<Float>(size)
    val temp = ArrayDeque<Float>(size)

    fun add(cpuVal: Float, memVal: Float, gpuVal: Float, tempVal: Float) {
        addTo(cpu, cpuVal)
        addTo(mem, memVal)
        addTo(gpu, gpuVal)
        addTo(temp, tempVal)
    }

    private fun addTo(q: ArrayDeque<Float>, v: Float) {
        if (q.size >= size) q.removeFirst()
        q.addLast(v)
    }
}

@Composable
private fun MiniSparkline(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    maxValue: Float = 100f
) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val step = w / (data.size - 1).toFloat()
        val path = Path()
        var first = true
        data.forEachIndexed { i, v ->
            val ratio = (v / maxValue).coerceIn(0f, 1f)
            val x = i * step
            val y = h - ratio * h
            if (first) { path.moveTo(x, y); first = false }
            else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )
        if (data.isNotEmpty()) {
            val lastRatio = (data.last() / maxValue).coerceIn(0f, 1f)
            drawCircle(
                color = color,
                radius = 2.5.dp.toPx(),
                center = Offset((data.size - 1) * step, h - lastRatio * h)
            )
        }
    }
}

@Composable
private fun ExpandedPanel(
    state: MonitorState,
    alertLevel: AlertLevel,
    onClose: () -> Unit,
    onCollapse: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
) {
    var currentPage by remember { mutableIntStateOf(0) }
    val history = remember { HistoryBuffer(HISTORY_SIZE) }

    LaunchedEffect(state.cpuPercent, state.memPercent, state.gpuPercent, state.tempCelsius, state.powerMw) {
        history.add(state.cpuPercent, state.memPercent, state.gpuPercent, state.tempCelsius)
    }

    val borderColor = when (alertLevel) {
        AlertLevel.CRITICAL -> Color(0xFFEF5350)
        AlertLevel.WARNING -> Color(0xFFFFB74D)
        AlertLevel.NORMAL -> Color.Transparent
    }

    Surface(
        modifier = Modifier.width(260.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xEE12121A),
        shadowElevation = 8.dp,
        border = if (alertLevel != AlertLevel.NORMAL) {
            androidx.compose.foundation.BorderStroke(1.5.dp, borderColor.copy(alpha = 0.7f))
        } else null
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() }
                        ) { _, dragAmount ->
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "HeatRadar",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { onCollapse() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("—", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("概览", "CPU", "温度/功耗", "内存").forEachIndexed { idx, label ->
                    val selected = currentPage == idx
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFF4FC3F7).copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { currentPage = idx }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 10.sp,
                            color = if (selected) Color(0xFF4FC3F7) else Color.White.copy(alpha = 0.6f),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            when (currentPage) {
                0 -> OverviewPage(state, history)
                1 -> CpuPage(state, history)
                2 -> TempPowerPage(state, history)
                3 -> MemoryPage(state, history)
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, valueColor: Color = Color.White, valueSize: Int = 14) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        Text(text = value, color = valueColor, fontSize = valueSize.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetricWithChart(
    label: String,
    value: String,
    color: Color,
    historyData: List<Float>,
    maxValue: Float = 100f,
    unit: String = ""
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(text = unit, color = color.copy(alpha = 0.7f), fontSize = 10.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        MiniSparkline(
            data = historyData,
            color = color,
            modifier = Modifier.fillMaxWidth().height(26.dp),
            maxValue = maxValue
        )
    }
}

@Composable
private fun OverviewPage(state: MonitorState, history: HistoryBuffer) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MetricWithChart(
            label = "CPU 使用率",
            value = String.format("%.0f", state.cpuPercent),
            color = getCpuColor(state.cpuPercent),
            historyData = history.cpu.toList(),
            unit = "%"
        )
        MetricWithChart(
            label = "内存使用",
            value = "${state.memUsedMb}",
            color = when {
                state.memPercent >= 80f -> Color(0xFFEF5350)
                state.memPercent >= 60f -> Color(0xFFFFB74D)
                else -> Color(0xFF66BB6A)
            },
            historyData = history.mem.toList(),
            unit = "MB"
        )
        if (state.gpuFreqMhz > 0 || state.gpuPercent > 0f) {
            MetricRow(
                label = "GPU",
                value = "${state.gpuFreqMhz}MHz  ${String.format("%.0f", state.gpuPercent)}%",
                valueColor = if (state.gpuPercent >= 70f) Color(0xFFFFB74D) else Color(0xFF4FC3F7)
            )
        }
        if (state.fps > 0f) {
            MetricRow(
                label = "FPS",
                value = String.format("%.0f", state.fps),
                valueColor = if (state.fps >= 55f) Color(0xFF66BB6A) else if (state.fps >= 30f) Color(0xFFFFB74D) else Color(0xFFEF5350)
            )
        }
        if (state.powerMw > 0) {
            val powerColor = when {
                state.powerMw >= 10000 -> Color(0xFFEF5350)
                state.powerMw >= 6000 -> Color(0xFFFFB74D)
                else -> Color(0xFF66BB6A)
            }
            MetricRow(
                label = "功耗",
                value = "${state.powerMw}mW",
                valueColor = powerColor
            )
        }
        MetricRow(
            label = "温度",
            value = String.format("%.0f℃", state.tempCelsius),
            valueColor = getTempColor(state.tempCelsius)
        )
        if (state.topApps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Top 应用", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            state.topApps.take(3).forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = app.appName.take(12),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Text(
                        text = String.format("%.0f%%  %.0fMB", app.cpuPercent, app.memoryMb),
                        color = getCpuColor(app.cpuPercent),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CpuPage(state: MonitorState, history: HistoryBuffer) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MetricWithChart(
            label = "CPU 总使用率",
            value = String.format("%.1f", state.cpuPercent),
            color = getCpuColor(state.cpuPercent),
            historyData = history.cpu.toList(),
            unit = "%"
        )
        MetricRow(
            label = "平均频率",
            value = "${state.cpuFreqMhz} MHz",
            valueColor = Color(0xFF4FC3F7)
        )
        if (state.maxCpuFreqMhz > 0) {
            MetricRow(
                label = "最高频率",
                value = "${state.maxCpuFreqMhz} MHz",
                valueColor = Color(0xFF81C784)
            )
        }
        MetricRow(
            label = "核心数",
            value = "${state.cpuFreqsMhz.size}",
            valueColor = Color.White
        )
        if (state.cpuFreqsMhz.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("各核心频率", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Spacer(modifier = Modifier.height(4.dp))
            CpuFreqBars(freqs = state.cpuFreqsMhz, maxFreq = max(state.maxCpuFreqMhz, state.cpuFreqsMhz.maxOrNull() ?: 0L))
        }
    }
}

@Composable
private fun CpuFreqBars(freqs: List<Long>, maxFreq: Long) {
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
                ratio >= 0.8f -> Color(0xFFEF5350)
                ratio >= 0.5f -> Color(0xFFFFB74D)
                ratio >= 0.2f -> Color(0xFFFFEE58)
                else -> Color(0xFF66BB6A)
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

@Composable
private fun TempPowerPage(state: MonitorState, history: HistoryBuffer) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MetricWithChart(
            label = "CPU 温度",
            value = String.format("%.0f", state.tempCelsius),
            color = getTempColor(state.tempCelsius),
            historyData = history.temp.toList(),
            maxValue = 60f,
            unit = "℃"
        )
        if (state.batteryTempCelsius > 0) {
            MetricRow(
                label = "电池温度",
                value = "${state.batteryTempCelsius}℃",
                valueColor = getTempColor(state.batteryTempCelsius.toFloat())
            )
        }
        if (state.powerMw > 0) {
            val powerColor = when {
                state.powerMw >= 10000 -> Color(0xFFEF5350)
                state.powerMw >= 6000 -> Color(0xFFFFB74D)
                else -> Color(0xFF66BB6A)
            }
            MetricRow(label = "功耗", value = "${state.powerMw} mW", valueColor = powerColor)
            MetricRow(label = "电流", value = "${state.currentMa} mA", valueColor = Color(0xFF4FC3F7))
            MetricRow(label = "电压", value = "${String.format("%.2f", state.voltageV)} V", valueColor = Color(0xFF81C784))
            if (state.batteryCapacity > 0) {
                MetricRow(label = "电量", value = "${state.batteryCapacity}%", valueColor = Color(0xFF4FC3F7))
            }
            if (state.batteryStatus.isNotEmpty()) {
                val statusColor = if (state.batteryStatus.equals("Charging", ignoreCase = true))
                    Color(0xFF66BB6A) else Color(0xFFFFB74D)
                MetricRow(label = "状态", value = state.batteryStatus, valueColor = statusColor)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (state.gpuFreqMhz > 0 || state.gpuPercent > 0f) {
            MetricRow(
                label = "GPU",
                value = "${state.gpuFreqMhz}MHz  ${String.format("%.0f", state.gpuPercent)}%",
                valueColor = if (state.gpuPercent >= 70f) Color(0xFFFFB74D) else Color(0xFF4FC3F7)
            )
        }
        if (state.allTemps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("各传感器温度", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Spacer(modifier = Modifier.height(2.dp))
            val shown = state.allTemps.filter { it.tempCelsius in 1..99 }.take(8)
            val cols = 2
            val rows = (shown.size + cols - 1) / cols
            Column {
                for (r in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (c in 0 until cols) {
                            val idx = r * cols + c
                            if (idx < shown.size) {
                                val t = shown[idx]
                                Column(
                                    modifier = Modifier.weight(1f).padding(vertical = 1.dp)
                                ) {
                                    Text(
                                        text = t.type.take(10),
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "${t.tempCelsius}℃",
                                        color = getTempColor(t.tempCelsius.toFloat()),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryPage(state: MonitorState, history: HistoryBuffer) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MetricWithChart(
            label = "内存使用率",
            value = String.format("%.0f", state.memPercent),
            color = when {
                state.memPercent >= 80f -> Color(0xFFEF5350)
                state.memPercent >= 60f -> Color(0xFFFFB74D)
                else -> Color(0xFF66BB6A)
            },
            historyData = history.mem.toList(),
            unit = "%"
        )
        MetricRow(label = "已用", value = "${state.memUsedMb} MB", valueColor = Color(0xFFFFB74D))
        MetricRow(label = "可用", value = "${state.memAvailableMb} MB", valueColor = Color(0xFF66BB6A))
        MetricRow(label = "总计", value = "${state.memTotalMb} MB", valueColor = Color.White)
        if (state.memCachedMb > 0) {
            MetricRow(label = "缓存", value = "${state.memCachedMb} MB", valueColor = Color(0xFF4FC3F7))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("Top 应用 (内存)", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Spacer(modifier = Modifier.height(2.dp))
        state.topApps
            .sortedByDescending { it.memoryMb }
            .take(5)
            .forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = app.appName.take(12),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Text(
                        text = String.format("%.0fMB", app.memoryMb),
                        color = Color(0xFF4FC3F7),
                        fontSize = 10.sp
                    )
                }
            }
    }
}
