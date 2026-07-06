package com.example.heatradar.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heatradar.core.common.SettingsManager
import com.example.heatradar.core.database.HeatRadarRepository
import com.example.heatradar.core.monitor.DaemonManager
import com.example.heatradar.core.monitor.DaemonStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
    private val repository: HeatRadarRepository,
    private val daemonManager: DaemonManager
) : ViewModel() {

    private val _daemonStatus = MutableStateFlow(DaemonStatus.NOT_RUNNING)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsManager.settings,
        _daemonStatus
    ) { settings, daemonStatus ->
        SettingsUiState(
            highFrequencySampling = settings.highFrequencySampling,
            longDataRetention = settings.longDataRetention,
            anomalyAlerts = settings.anomalyAlerts,
            showSystemProcesses = settings.showSystemProcesses,
            daemonStatus = daemonStatus
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    private var daemonPollJob: Job? = null

    init {
        startDaemonPolling()
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

    override fun onCleared() {
        super.onCleared()
        daemonPollJob?.cancel()
    }
}
