package com.example.heatradar.core.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heatradar.R
import com.example.heatradar.app.MainActivity
import com.example.heatradar.core.database.DeviceStateEntity
import com.example.heatradar.core.database.HeatRadarRepository
import com.example.heatradar.core.database.ResourceSampleEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class TopAppInfo(
    val packageName: String,
    val appName: String,
    val cpuPercent: Float,
    val memoryMb: Float
)

data class MonitorState(
    val cpuPercent: Float = 0f,
    val cpuFreqMhz: Long = 0L,
    val maxCpuFreqMhz: Long = 0L,
    val cpuFreqsMhz: List<Long> = emptyList(),
    val memPercent: Float = 0f,
    val memUsedMb: Long = 0L,
    val memTotalMb: Long = 0L,
    val memAvailableMb: Long = 0L,
    val memCachedMb: Long = 0L,
    val tempCelsius: Float = 0f,
    val batteryTempCelsius: Float = 0f,
    val gpuPercent: Float = 0f,
    val gpuFreqMhz: Long = 0L,
    val fps: Float = 0f,
    val currentMa: Long = 0L,
    val voltageV: Float = 0f,
    val powerMw: Long = 0L,
    val batteryStatus: String = "",
    val batteryCapacity: Int = 0,
    val netDownBps: Long = 0L,
    val netUpBps: Long = 0L,
    val allTemps: List<ThermalZone> = emptyList(),
    val topApps: List<TopAppInfo> = emptyList()
)

@AndroidEntryPoint
class MonitorService : Service() {

    @Inject
    lateinit var processScanner: ProcessScanner

    @Inject
    lateinit var deviceStateProvider: DeviceStateProvider

    @Inject
    lateinit var metricsHolder: SystemMetricsHolder

    @Inject
    lateinit var settingsManager: com.example.heatradar.core.common.SettingsManager

    @Inject
    lateinit var repository: HeatRadarRepository

    @Inject
    lateinit var foregroundAppProvider: ForegroundAppProvider

    @Inject
    lateinit var shizukuManager: ShizukuServiceManager

    private lateinit var windowManager: android.view.WindowManager
    private lateinit var serviceScope: CoroutineScope
    private var samplingJob: Job? = null
    private var floatingWindowManager: FloatingWindowManager? = null
    private var fpsSampler: FpsSampler? = null
    private var alertManager: AlertManager? = null
    private var isForegroundStarted = false
    private var dbSampleCount = 0

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitor_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.heatradar.ACTION_STOP_MONITOR"
        const val ACTION_HIDE_FLOATING = "com.example.heatradar.ACTION_HIDE_FLOATING"
        const val EXTRA_SHOW_FLOATING = "show_floating"

        private const val DB_WRITE_INTERVAL_SAMPLES = 3

        private val isRunning = AtomicBoolean(false)

        fun isServiceRunning(): Boolean = isRunning.get()

        private val _monitorState = MutableStateFlow(MonitorState())
        val monitorState: StateFlow<MonitorState> = _monitorState.asStateFlow()

        fun start(context: Context, showFloating: Boolean = false) {
            val intent = Intent(context, MonitorService::class.java).apply {
                putExtra(EXTRA_SHOW_FLOATING, showFloating)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        fun hideFloating(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply {
                action = ACTION_HIDE_FLOATING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun showFloating(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply {
                putExtra(EXTRA_SHOW_FLOATING, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        createNotificationChannel()
        fpsSampler = FpsSampler(shizukuManager)
        alertManager = AlertManager(this)
        floatingWindowManager = FloatingWindowManager(this, windowManager) {
            serviceScope.launch {
                settingsManager.setFloatingWindow(false)
            }
            hideFloatingWindow()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            hideFloatingWindow()
            stopSelf()
            return START_NOT_STICKY
        }

        ensureForeground()

        if (intent?.action == ACTION_HIDE_FLOATING) {
            hideFloatingWindow()
            return START_STICKY
        }

        if (samplingJob == null || samplingJob?.isActive != true) {
            startSamplingLoop()
        }

        val showFloating = intent?.getBooleanExtra(EXTRA_SHOW_FLOATING, false) ?: false
        if (showFloating && hasOverlayPermission()) {
            showFloatingWindow()
        }

        return START_STICKY
    }

    private fun ensureForeground() {
        if (!isForegroundStarted) {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            isForegroundStarted = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning.set(false)
        samplingJob?.cancel()
        serviceScope.cancel()
        hideFloatingWindow()
        floatingWindowManager = null
        isForegroundStarted = false
        _monitorState.value = MonitorState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun showFloatingWindow() {
        if (hasOverlayPermission()) {
            floatingWindowManager?.show()
        }
    }

    fun hideFloatingWindow() {
        floatingWindowManager?.hide()
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.monitor_notification_channel_desc)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(R.string.monitor_notification_text))
            .setSmallIcon(R.drawable.ic_notification_monitor)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.stop_monitor), stopPendingIntent)
            .build()
    }

    private fun startSamplingLoop() {
        samplingJob?.cancel()
        samplingJob = serviceScope.launch {
            var lastState = MonitorState()
            while (isActive) {
                try {
                    val processes = processScanner.scanAllProcesses(showSystemProcesses = false)
                    val deviceState = deviceStateProvider.getDeviceState()

                    val topApps = processes.take(3).map { proc ->
                        TopAppInfo(
                            packageName = proc.packageName,
                            appName = proc.appName,
                            cpuPercent = proc.cpuPercent,
                            memoryMb = proc.memoryKb / 1024f
                        )
                    }

                    val snapshot = metricsHolder.getSnapshot()
                    val fps = fpsSampler?.sample() ?: 0f
                    val p = deviceState.power
                    val n = deviceState.network
                    val newState = MonitorState(
                        cpuPercent = deviceState.cpuUsagePercent,
                        cpuFreqMhz = deviceState.totalCpuFreqMhz,
                        maxCpuFreqMhz = deviceState.maxCpuFreqMhz,
                        cpuFreqsMhz = deviceState.cpuFreqsMhz,
                        memPercent = deviceState.memoryUsagePercent,
                        memUsedMb = deviceState.usedMemoryBytes / (1024 * 1024),
                        memTotalMb = deviceState.totalMemoryBytes / (1024 * 1024),
                        memAvailableMb = deviceState.availableMemoryBytes / (1024 * 1024),
                        memCachedMb = snapshot.memory.cachedKb / 1024,
                        tempCelsius = deviceState.temperatureCelsius,
                        batteryTempCelsius = deviceState.batteryTempCelsius,
                        gpuPercent = deviceState.gpuBusyPercent,
                        gpuFreqMhz = deviceState.gpuFreqMhz,
                        fps = fps,
                        currentMa = p.currentMa,
                        voltageV = p.voltageV,
                        powerMw = p.powerMw,
                        batteryStatus = p.status,
                        batteryCapacity = p.capacity,
                        netDownBps = n.downBps,
                        netUpBps = n.upBps,
                        allTemps = deviceState.allTemps,
                        topApps = topApps
                    )

                    val triggered = alertManager?.checkAlerts(newState) ?: emptyList()
                    if (triggered.isNotEmpty()) {
                        val level = alertManager?.getActiveAlertLevel() ?: AlertLevel.NORMAL
                        floatingWindowManager?.setAlertLevel(level)
                    } else if ((alertManager?.hasActiveAlerts() == false) && floatingWindowManager != null) {
                        floatingWindowManager?.setAlertLevel(AlertLevel.NORMAL)
                    }

                    if (stateChanged(lastState, newState)) {
                        _monitorState.value = newState
                        lastState = newState
                    }

                    dbSampleCount++
                    if (dbSampleCount >= DB_WRITE_INTERVAL_SAMPLES) {
                        dbSampleCount = 0
                        writeToDatabase(processes, deviceState)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Sampling error", e)
                }
                delay(2000)
            }
        }
    }

    private fun stateChanged(old: MonitorState, new: MonitorState): Boolean {
        if (kotlin.math.abs(old.cpuPercent - new.cpuPercent) > 0.5f) return true
        if (kotlin.math.abs(old.memPercent - new.memPercent) > 0.3f) return true
        if (kotlin.math.abs(old.tempCelsius - new.tempCelsius) > 0.5f) return true
        if (kotlin.math.abs(old.fps - new.fps) > 0.5f) return true
        if (old.powerMw != new.powerMw) return true
        if (old.topApps.size != new.topApps.size) return true
        for (i in old.topApps.indices) {
            if (old.topApps[i].packageName != new.topApps[i].packageName ||
                kotlin.math.abs(old.topApps[i].cpuPercent - new.topApps[i].cpuPercent) > 0.5f) {
                return true
            }
        }
        return false
    }

    private suspend fun writeToDatabase(processes: List<RunningProcess>, deviceState: DeviceState) {
        try {
            val now = System.currentTimeMillis()
            val samples = processes.map { proc ->
                ResourceSampleEntity(
                    packageName = proc.packageName,
                    timestamp = now,
                    cpuPercent = proc.cpuPercent.coerceIn(0f, 100f),
                    memoryBytes = proc.memoryKb * 1024L,
                    activeMinutes = proc.foregroundMinutes
                )
            }
            repository.insertAllSamples(samples)

            val foreground = foregroundAppProvider.getForegroundPackageName()
            repository.insertDeviceState(DeviceStateEntity(
                timestamp = now,
                cpuFreqMhz = deviceState.totalCpuFreqMhz,
                maxCpuFreqMhz = deviceState.maxCpuFreqMhz,
                cpuUsagePercent = deviceState.cpuUsagePercent,
                temperatureCelsius = deviceState.temperatureCelsius,
                totalMemoryBytes = deviceState.totalMemoryBytes,
                usedMemoryBytes = deviceState.usedMemoryBytes,
                memoryUsagePercent = deviceState.memoryUsagePercent,
                foregroundPackageName = foreground
            ))
        } catch (e: Exception) {
            Log.e(TAG, "writeToDatabase failed", e)
        }
    }
}
