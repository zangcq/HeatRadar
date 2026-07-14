package com.example.heatradar.core.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.clickable
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

private fun getMemColor(percent: Float): Color {
    return when {
        percent >= 90f -> Color(0xFFEF5350)
        percent >= 75f -> Color(0xFFFFB74D)
        else -> Color(0xFF66BB6A)
    }
}

private fun alertBorderColor(level: AlertLevel): Color = when (level) {
    AlertLevel.CRITICAL -> Color(0xFFEF5350)
    AlertLevel.WARNING -> Color(0xFFFFB74D)
    AlertLevel.NORMAL -> Color.Transparent
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
    val cpuColor = getCpuColor(state.cpuPercent)
    val alertDotColor = when (alertLevel) {
        AlertLevel.CRITICAL -> Color(0xFFEF5350)
        AlertLevel.WARNING -> Color(0xFFFFB74D)
        AlertLevel.NORMAL -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xD91A1A2A))
            .alpha(0.9f)
            .then(
                if (alertLevel != AlertLevel.NORMAL) Modifier.background(
                    when (alertLevel) {
                        AlertLevel.CRITICAL -> Color(0x33EF5350)
                        AlertLevel.WARNING -> Color(0x33FFB74D)
                        else -> Color.Transparent
                    }
                ) else Modifier
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
                color = cpuColor,
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
        if (alertLevel != AlertLevel.NORMAL) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(alertDotColor)
            )
        }
    }
}

private const val HISTORY_SIZE = 30

private class TinyHistory(val size: Int) {
    val cpu = ArrayDeque<Float>(size)
    val mem = ArrayDeque<Float>(size)
    val temp = ArrayDeque<Float>(size)

    fun add(c: Float, m: Float, t: Float) {
        addTo(cpu, c); addTo(mem, m); addTo(temp, t)
    }
    private fun addTo(q: ArrayDeque<Float>, v: Float) {
        if (q.size >= size) q.removeFirst()
        q.addLast(v)
    }
}

@Composable
private fun TinySpark(data: List<Float>, color: Color, modifier: Modifier = Modifier, max: Float = 100f) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas
        val step = size.width / (data.size - 1).toFloat()
        val path = Path()
        data.forEachIndexed { i, v ->
            val ratio = (v / max).coerceIn(0f, 1f)
            val x = i * step
            val y = size.height - ratio * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun MetricCell(label: String, value: String, sub: String = "", color: Color, sparkData: List<Float> = emptyList(), max: Float = 100f) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 15.sp)
        if (sub.isNotEmpty()) {
            Text(sub, color = Color.White.copy(alpha = 0.5f), fontSize = 8.sp, lineHeight = 8.sp)
        }
        if (sparkData.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            TinySpark(sparkData, color, modifier = Modifier.width(36.dp).height(12.dp), max = max)
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
    val history = remember { TinyHistory(HISTORY_SIZE) }
    androidx.compose.runtime.LaunchedEffect(state.cpuPercent, state.memPercent, state.tempCelsius) {
        history.add(state.cpuPercent, state.memPercent, state.tempCelsius)
    }

    Surface(
        modifier = Modifier.width(200.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xEE12121A),
        shadowElevation = 6.dp,
        border = if (alertLevel != AlertLevel.NORMAL) {
            androidx.compose.foundation.BorderStroke(1.5.dp, alertBorderColor(alertLevel).copy(alpha = 0.75f))
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
                        ) { _, dragAmount -> onDrag(dragAmount.x, dragAmount.y) }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (alertLevel == AlertLevel.CRITICAL) {
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF5350))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("告警", color = Color(0xFFEF5350), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else if (alertLevel == AlertLevel.WARNING) {
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFFB74D))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("注意", color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("HeatRadar", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row {
                    Box(
                        modifier = Modifier.size(26.dp).clip(CircleShape).clickable { onCollapse() },
                        contentAlignment = Alignment.Center
                    ) { Text("—", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp) }
                    Spacer(modifier = Modifier.width(2.dp))
                    Box(
                        modifier = Modifier.size(26.dp).clip(CircleShape).clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) { Text("×", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp) }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.Top
            ) {
                MetricCell(
                    label = "CPU",
                    value = String.format("%.0f%%", state.cpuPercent),
                    color = getCpuColor(state.cpuPercent),
                    sparkData = history.cpu.toList()
                )
                MetricCell(
                    label = "内存",
                    value = String.format("%.0f%%", state.memPercent),
                    color = getMemColor(state.memPercent),
                    sparkData = history.mem.toList()
                )
                MetricCell(
                    label = "温度",
                    value = String.format("%.0f°", state.tempCelsius),
                    color = getTempColor(state.tempCelsius),
                    sparkData = history.temp.toList(),
                    max = 60f
                )
            }

            val topApps = state.topApps.take(3)
            if (topApps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    topApps.forEachIndexed { idx, top ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${idx + 1}. ${top.appName.take(10)}",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                            Text(
                                text = String.format("%.0f%%", top.cpuPercent),
                                color = getCpuColor(top.cpuPercent),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
