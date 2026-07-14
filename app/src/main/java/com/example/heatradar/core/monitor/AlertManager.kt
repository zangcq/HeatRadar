package com.example.heatradar.core.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heatradar.R

enum class AlertType {
    CPU_HIGH, CPU_CRITICAL,
    MEM_HIGH, MEM_CRITICAL,
    TEMP_HIGH, TEMP_CRITICAL,
    POWER_HIGH
}

data class AlertThresholds(
    val cpuWarning: Float = 70f,
    val cpuCritical: Float = 90f,
    val memWarning: Float = 85f,
    val memCritical: Float = 95f,
    val tempWarning: Float = 47f,
    val tempCritical: Float = 53f,
    val powerWarning: Long = 8000L,
    val durationSeconds: Int = 10
)

class AlertManager(private val context: Context) {

    companion object {
        private const val TAG = "AlertManager"
        private const val ALERT_CHANNEL_ID = "heatradar_alerts"
        private const val NOTIFICATION_BASE_ID = 2000
    }

    private val thresholds = AlertThresholds()
    private val alertStates = mutableMapOf<AlertType, AlertState>()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private data class AlertState(
        var triggeredAt: Long = 0L,
        var lastNotifiedAt: Long = 0L,
        var isActive: Boolean = false
    )

    init {
        createAlertChannel()
    }

    fun checkAlerts(state: MonitorState): List<AlertType> {
        val now = System.currentTimeMillis()
        val triggered = mutableListOf<AlertType>()

        checkCpu(state, now, triggered)
        checkMemory(state, now, triggered)
        checkTemp(state, now, triggered)
        checkPower(state, now, triggered)

        return triggered
    }

    fun hasActiveAlerts(): Boolean = alertStates.values.any { it.isActive }

    fun getActiveAlertLevel(): AlertLevel {
        val hasCritical = alertStates.any { (type, state) ->
            state.isActive && (type == AlertType.CPU_CRITICAL || type == AlertType.MEM_CRITICAL || type == AlertType.TEMP_CRITICAL)
        }
        if (hasCritical) return AlertLevel.CRITICAL

        val hasWarning = alertStates.any { (_, state) -> state.isActive }
        if (hasWarning) return AlertLevel.WARNING

        return AlertLevel.NORMAL
    }

    private fun checkCpu(state: MonitorState, now: Long, triggered: MutableList<AlertType>) {
        when {
            state.cpuPercent >= thresholds.cpuCritical -> {
                if (triggerAlert(AlertType.CPU_CRITICAL, now)) {
                    triggered.add(AlertType.CPU_CRITICAL)
                    notifyAlert(AlertType.CPU_CRITICAL, "CPU 使用率 ${state.cpuPercent.toInt()}%", "CPU 严重过载，请检查异常进程")
                }
            }
            state.cpuPercent >= thresholds.cpuWarning -> {
                if (triggerAlert(AlertType.CPU_HIGH, now)) {
                    triggered.add(AlertType.CPU_HIGH)
                }
            }
            else -> clearAlert(AlertType.CPU_CRITICAL, now)
        }
        if (state.cpuPercent < thresholds.cpuWarning) {
            clearAlert(AlertType.CPU_HIGH, now)
        }
    }

    private fun checkMemory(state: MonitorState, now: Long, triggered: MutableList<AlertType>) {
        when {
            state.memPercent >= thresholds.memCritical -> {
                if (triggerAlert(AlertType.MEM_CRITICAL, now)) {
                    triggered.add(AlertType.MEM_CRITICAL)
                    notifyAlert(AlertType.MEM_CRITICAL, "内存使用率 ${state.memPercent.toInt()}%", "内存严重不足，系统可能变卡")
                }
            }
            state.memPercent >= thresholds.memWarning -> {
                if (triggerAlert(AlertType.MEM_HIGH, now)) {
                    triggered.add(AlertType.MEM_HIGH)
                }
            }
            else -> clearAlert(AlertType.MEM_CRITICAL, now)
        }
        if (state.memPercent < thresholds.memWarning) {
            clearAlert(AlertType.MEM_HIGH, now)
        }
    }

    private fun checkTemp(state: MonitorState, now: Long, triggered: MutableList<AlertType>) {
        when {
            state.tempCelsius >= thresholds.tempCritical -> {
                if (triggerAlert(AlertType.TEMP_CRITICAL, now)) {
                    triggered.add(AlertType.TEMP_CRITICAL)
                    notifyAlert(AlertType.TEMP_CRITICAL, "CPU 温度 ${state.tempCelsius.toInt()}℃", "设备过热，建议停止使用或降温")
                }
            }
            state.tempCelsius >= thresholds.tempWarning -> {
                if (triggerAlert(AlertType.TEMP_HIGH, now)) {
                    triggered.add(AlertType.TEMP_HIGH)
                }
            }
            else -> clearAlert(AlertType.TEMP_CRITICAL, now)
        }
        if (state.tempCelsius < thresholds.tempWarning) {
            clearAlert(AlertType.TEMP_HIGH, now)
        }
    }

    private fun checkPower(state: MonitorState, now: Long, triggered: MutableList<AlertType>) {
        if (state.powerMw >= thresholds.powerWarning && state.batteryStatus.equals("Discharging", ignoreCase = true)) {
            if (triggerAlert(AlertType.POWER_HIGH, now)) {
                triggered.add(AlertType.POWER_HIGH)
            }
        } else {
            clearAlert(AlertType.POWER_HIGH, now)
        }
    }

    private fun triggerAlert(type: AlertType, now: Long): Boolean {
        val state = alertStates.getOrPut(type) { AlertState() }
        if (!state.isActive) {
            state.triggeredAt = now
            state.isActive = true
            return true
        }
        return false
    }

    private fun clearAlert(type: AlertType, now: Long) {
        val state = alertStates[type] ?: return
        if (state.isActive) {
            state.isActive = false
            Log.i(TAG, "Alert cleared: $type")
        }
    }

    private fun notifyAlert(type: AlertType, title: String, message: String) {
        val now = System.currentTimeMillis()
        val state = alertStates[type] ?: return
        if (now - state.lastNotifiedAt < 30_000L) return
        state.lastNotifiedAt = now

        val importance = when (type) {
            AlertType.CPU_CRITICAL, AlertType.MEM_CRITICAL, AlertType.TEMP_CRITICAL ->
                NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_monitor)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(importance)
            .setAutoCancel(true)
            .build()

        val id = NOTIFICATION_BASE_ID + type.ordinal
        notificationManager.notify(id, notification)
        Log.i(TAG, "Notification sent: $type - $title")
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "HeatRadar 告警",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "CPU、内存、温度、功耗异常告警"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}

enum class AlertLevel {
    NORMAL, WARNING, CRITICAL
}
