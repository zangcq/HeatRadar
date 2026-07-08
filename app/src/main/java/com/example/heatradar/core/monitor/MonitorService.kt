package com.example.heatradar.core.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.heatradar.R
import com.example.heatradar.app.MainActivity
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
    val memPercent: Float = 0f,
    val memUsedMb: Long = 0L,
    val memTotalMb: Long = 0L,
    val tempCelsius: Float = 0f,
    val topApps: List<TopAppInfo> = emptyList()
)

@AndroidEntryPoint
class MonitorService : Service() {

    @Inject
    lateinit var processScanner: ProcessScanner

    @Inject
    lateinit var deviceStateProvider: DeviceStateProvider

    @Inject
    lateinit var settingsManager: com.example.heatradar.core.common.SettingsManager

    private lateinit var windowManager: android.view.WindowManager
    private lateinit var serviceScope: CoroutineScope
    private var samplingJob: Job? = null
    private var floatingWindowManager: FloatingWindowManager? = null

    companion object {
        private const val CHANNEL_ID = "monitor_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.heatradar.ACTION_STOP_MONITOR"
        const val ACTION_HIDE_FLOATING = "com.example.heatradar.ACTION_HIDE_FLOATING"
        const val EXTRA_SHOW_FLOATING = "show_floating"

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
            val intent = Intent(context, MonitorService::class.java)
            context.stopService(intent)
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

        fun isServiceRunning(): Boolean {
            return _monitorState.value.topApps.isNotEmpty() || _monitorState.value.cpuPercent > 0f
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        createNotificationChannel()
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

        if (intent?.action == ACTION_HIDE_FLOATING) {
            hideFloatingWindow()
            return START_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        if (samplingJob == null || samplingJob?.isActive != true) {
            startSamplingLoop()
        }

        val showFloating = intent?.getBooleanExtra(EXTRA_SHOW_FLOATING, false) ?: false
        if (showFloating && hasOverlayPermission()) {
            showFloatingWindow()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        samplingJob?.cancel()
        serviceScope.cancel()
        hideFloatingWindow()
        floatingWindowManager = null
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

                    _monitorState.value = MonitorState(
                        cpuPercent = deviceState.cpuUsagePercent,
                        cpuFreqMhz = deviceState.totalCpuFreqMhz,
                        memPercent = deviceState.memoryUsagePercent,
                        memUsedMb = deviceState.usedMemoryBytes / (1024 * 1024),
                        memTotalMb = deviceState.totalMemoryBytes / (1024 * 1024),
                        tempCelsius = deviceState.temperatureCelsius,
                        topApps = topApps
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(2000)
            }
        }
    }
}
