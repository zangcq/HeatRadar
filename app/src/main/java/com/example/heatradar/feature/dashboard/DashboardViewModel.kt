package com.example.heatradar.feature.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heatradar.core.database.HeatRadarRepository
import com.example.heatradar.core.monitor.DaemonManager
import com.example.heatradar.core.monitor.DaemonStatus
import com.example.heatradar.core.monitor.ProcessScanner
import com.example.heatradar.core.monitor.ShizukuServiceManager
import com.example.heatradar.core.monitor.UsageStatsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: HeatRadarRepository,
    private val usageStatsProvider: UsageStatsProvider,
    private val processScanner: ProcessScanner,
    private val shizukuManager: ShizukuServiceManager,
    private val daemonManager: DaemonManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _hasUsagePermission = MutableStateFlow(usageStatsProvider.hasPermission())
    val hasUsagePermission: StateFlow<Boolean> = _hasUsagePermission.asStateFlow()

    private val _dataSource = MutableStateFlow(processScanner.getCurrentDataSource())
    val dataSource: StateFlow<String> = _dataSource.asStateFlow()

    private val _daemonStatus = MutableStateFlow(DaemonStatus.NOT_RUNNING)
    val daemonStatus: StateFlow<DaemonStatus> = _daemonStatus.asStateFlow()

    private val _scriptDeployed = MutableStateFlow(false)
    val scriptDeployed: StateFlow<Boolean> = _scriptDeployed.asStateFlow()

    val daemonAdbCommand: String
        get() = daemonManager.adbStartCommand

    val daemonStopCommand: String
        get() = daemonManager.adbStopCommand

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observeLatestSamples(),
        repository.observeLatestDeviceState()
    ) { samples, deviceState ->
        DashboardUiState(
            cpuTop = samples.sortedByDescending { it.cpuPercent }.take(10),
            memoryTop = samples.sortedByDescending { it.memoryBytes }.take(5),
            deviceState = deviceState,
            isLoading = false
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DashboardUiState()
        )

    private var daemonPollJob: Job? = null

    init {
        refreshDataSource()
        _scriptDeployed.value = daemonManager.isScriptDeployed()
        startDaemonPolling()
    }

    private fun startDaemonPolling() {
        daemonPollJob?.cancel()
        daemonPollJob = viewModelScope.launch {
            while (isActive) {
                val status = daemonManager.getDaemonStatus()
                _daemonStatus.value = status
                _dataSource.value = processScanner.getCurrentDataSource()
                delay(3000)
            }
        }
    }

    fun refreshPermission() {
        viewModelScope.launch {
            _hasUsagePermission.value = usageStatsProvider.hasPermission()
            refreshDataSource()
        }
    }

    fun refreshDataSource() {
        _dataSource.value = processScanner.getCurrentDataSource()
        _daemonStatus.value = daemonManager.getDaemonStatus()
    }

    fun deployDaemonScript() {
        viewModelScope.launch {
            val success = daemonManager.deployScriptFromAssets()
            _scriptDeployed.value = success
        }
    }

    fun copyDaemonCommand() {
        val cmd = daemonManager.adbStartCommand
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", cmd))
    }

    fun copyStopCommand() {
        val cmd = daemonManager.adbStopCommand
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB Stop Command", cmd))
    }

    fun openUsageAccessSettings() {
        context.startActivity(usageStatsProvider.createPermissionIntent())
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    override fun onCleared() {
        super.onCleared()
        daemonPollJob?.cancel()
    }
}
