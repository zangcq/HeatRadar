package com.example.heatradar.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heatradar.core.common.ReportExporter
import com.example.heatradar.core.common.SettingsManager
import com.example.heatradar.core.database.HeatRadarRepository
import com.example.heatradar.core.monitor.DaemonManager
import com.example.heatradar.core.monitor.DaemonStatus
import com.example.heatradar.core.monitor.MonitorService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: SettingsManager,
    private val repository: HeatRadarRepository,
    private val daemonManager: DaemonManager,
    private val reportExporter: ReportExporter
) : ViewModel() {

    private val _daemonStatus = MutableStateFlow(DaemonStatus.NOT_RUNNING)
    private val _isExporting = MutableStateFlow(false)
    private val _exportUri = MutableStateFlow<Uri?>(null)
    val exportUri: StateFlow<Uri?> = _exportUri

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsManager.settings,
        _daemonStatus,
        _isExporting,
        MonitorService.monitorState
    ) { settings, daemonStatus, exporting, monitorState ->
        SettingsUiState(
            highFrequencySampling = settings.highFrequencySampling,
            longDataRetention = settings.longDataRetention,
            anomalyAlerts = settings.anomalyAlerts,
            showSystemProcesses = settings.showSystemProcesses,
            daemonStatus = daemonStatus,
            floatingWindowEnabled = settings.floatingWindowEnabled,
            foregroundMonitorEnabled = settings.foregroundMonitorEnabled,
            isExporting = exporting,
            hasOverlayPermission = hasOverlayPermission(),
            hasNotificationPermission = hasNotificationPermission(),
            monitorRunning = monitorState.topApps.isNotEmpty() || monitorState.cpuPercent > 0f
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    private var daemonPollJob: Job? = null

    init {
        startDaemonPolling()
        restoreMonitorIfNeeded()
    }

    private fun restoreMonitorIfNeeded() {
        viewModelScope.launch {
            delay(500)
            val settings = settingsManager.settings.first()
            if (settings.foregroundMonitorEnabled) {
                val showFloating = settings.floatingWindowEnabled && hasOverlayPermission()
                MonitorService.start(context, showFloating = showFloating)
            }
        }
    }

    fun onResume() {
        viewModelScope.launch {
            delay(200)
            val settings = settingsManager.settings.first()
            val hasPerm = hasOverlayPermission()
            val running = MonitorService.monitorState.value.topApps.isNotEmpty() ||
                    MonitorService.monitorState.value.cpuPercent > 0f

            if (settings.floatingWindowEnabled && hasPerm && !running) {
                settingsManager.setForegroundMonitor(true)
                MonitorService.start(context, showFloating = true)
            } else if (settings.floatingWindowEnabled && hasPerm && running) {
                MonitorService.showFloating(context)
            } else if (!hasPerm && settings.floatingWindowEnabled) {
                settingsManager.setFloatingWindow(false)
                if (settings.foregroundMonitorEnabled) {
                    MonitorService.start(context, showFloating = false)
                }
            }
        }
    }

    private fun startDaemonPolling() {
        daemonPollJob?.cancel()
        daemonPollJob = viewModelScope.launch {
            while (isActive) {
                _daemonStatus.value = daemonManager.getDaemonStatus()
                delay(3000)
            }
        }
    }

    fun toggleHighFrequency() {
        viewModelScope.launch {
            val current = uiState.value.highFrequencySampling
            settingsManager.setHighFrequency(!current)
        }
    }

    fun toggleRetention() {
        viewModelScope.launch {
            val current = uiState.value.longDataRetention
            settingsManager.setLongRetention(!current)
        }
    }

    fun toggleAlerts() {
        viewModelScope.launch {
            val current = uiState.value.anomalyAlerts
            settingsManager.setAnomalyAlerts(!current)
        }
    }

    fun toggleShowSystemProcesses() {
        viewModelScope.launch {
            val current = uiState.value.showSystemProcesses
            settingsManager.setShowSystemProcesses(!current)
        }
    }

    fun stopDaemon() {
        viewModelScope.launch {
            daemonManager.requestStop()
            delay(500)
            _daemonStatus.value = daemonManager.getDaemonStatus()
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAllData()
        }
    }

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun toggleFloatingWindow() {
        viewModelScope.launch {
            val current = uiState.value.floatingWindowEnabled
            val newValue = !current

            if (newValue) {
                if (!hasOverlayPermission()) {
                    requestOverlayPermission()
                    return@launch
                }
                settingsManager.setFloatingWindow(true)
                val running = MonitorService.monitorState.value.topApps.isNotEmpty() ||
                        MonitorService.monitorState.value.cpuPercent > 0f
                if (!running) {
                    settingsManager.setForegroundMonitor(true)
                    MonitorService.start(context, showFloating = true)
                } else {
                    MonitorService.showFloating(context)
                }
            } else {
                settingsManager.setFloatingWindow(false)
                MonitorService.hideFloating(context)
            }
        }
    }

    fun toggleForegroundMonitor() {
        viewModelScope.launch {
            val current = uiState.value.foregroundMonitorEnabled
            val newValue = !current
            settingsManager.setForegroundMonitor(newValue)

            if (newValue) {
                val showFloating = uiState.value.floatingWindowEnabled && hasOverlayPermission()
                MonitorService.start(context, showFloating = showFloating)
            } else {
                settingsManager.setFloatingWindow(false)
                MonitorService.stop(context)
            }
        }
    }

    fun exportHtmlReport() {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val uri = reportExporter.exportHtmlReport()
                _exportUri.value = uri
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun exportCsvReport() {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val uri = reportExporter.exportCsvReport()
                _exportUri.value = uri
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun clearExportUri() {
        _exportUri.value = null
    }

    override fun onCleared() {
        super.onCleared()
        daemonPollJob?.cancel()
    }
}
