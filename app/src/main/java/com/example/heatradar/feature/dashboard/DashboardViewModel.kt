package com.example.heatradar.feature.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heatradar.core.database.HeatRadarRepository
import com.example.heatradar.core.monitor.UsageStatsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: HeatRadarRepository,
    private val usageStatsProvider: UsageStatsProvider,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _hasUsagePermission = MutableStateFlow(usageStatsProvider.hasPermission())
    val hasUsagePermission: StateFlow<Boolean> = _hasUsagePermission.asStateFlow()

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

    fun refreshPermission() {
        viewModelScope.launch {
            _hasUsagePermission.value = usageStatsProvider.hasPermission()
        }
    }

    fun openUsageAccessSettings() {
        context.startActivity(usageStatsProvider.createPermissionIntent())
    }
}
