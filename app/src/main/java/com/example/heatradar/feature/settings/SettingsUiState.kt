package com.example.heatradar.feature.settings

import com.example.heatradar.core.monitor.DaemonStatus

data class SettingsUiState(
    val highFrequencySampling: Boolean = false,
    val longDataRetention: Boolean = false,
    val anomalyAlerts: Boolean = true,
    val showSystemProcesses: Boolean = false,
    val daemonStatus: DaemonStatus = DaemonStatus.NOT_RUNNING,
    val floatingWindowEnabled: Boolean = false,
    val foregroundMonitorEnabled: Boolean = false,
    val isExporting: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val monitorRunning: Boolean = false
)
