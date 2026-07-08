package com.example.heatradar.core.monitor

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MonitorTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFFBB86FC),
        secondary = Color(0xFF03DAC6),
        background = Color(0xFF1A1A2A),
        surface = Color(0xFF1A1A2A),
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White
    )
    CompositionLocalProvider(
        LocalContentColor provides colorScheme.onSurface,
        LocalTextStyle provides TextStyle.Default
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

@Composable
fun FloatingOverlayContent(
    state: MonitorState,
    minWidth: Int,
    maxWidth: Int,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    MonitorTheme {
        Surface(
            modifier = modifier
                .padding(8.dp)
                .width(260.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xE61A1A2A)
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "HeatRadar",
                        color = Color(0xFFFF6D00),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        label = "CPU",
                        value = String.format("%.0f%%", state.cpuPercent),
                        color = getCpuColor(state.cpuPercent)
                    )
                    StatItem(
                        label = "MEM",
                        value = String.format("%.0f%%", state.memPercent),
                        color = Color(0xFFFF9800)
                    )
                    StatItem(
                        label = "TEMP",
                        value = String.format("%.0f°C", state.tempCelsius),
                        color = getTempColor(state.tempCelsius)
                    )
                }

                if (state.topApps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column {
                        state.topApps.take(3).forEach { app ->
                            TopAppRow(app = app)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TopAppRow(app: TopAppInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = app.appName.take(10),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            modifier = Modifier.width(70.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .background(
                    color = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(3.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (app.cpuPercent / 100f).coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(
                        color = getCpuColor(app.cpuPercent),
                        shape = RoundedCornerShape(3.dp)
                    )
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = String.format("%.0fMB", app.memoryMb),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp
        )
    }
}

private fun getCpuColor(percent: Float): Color {
    return when {
        percent < 30f -> Color(0xFF4CAF50)
        percent < 70f -> Color(0xFFFFEB3B)
        else -> Color(0xFFF44336)
    }
}

private fun getTempColor(temp: Float): Color {
    return when {
        temp < 40f -> Color(0xFF4CAF50)
        temp < 50f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
}
