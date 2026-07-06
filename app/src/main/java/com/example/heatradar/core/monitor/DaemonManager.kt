package com.example.heatradar.core.monitor

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DaemonStatus(
    val isRunning: Boolean,
    val pid: Int = -1,
    val lastUpdateMs: Long = 0,
    val statusText: String = "unknown",
    val outputFileAgeMs: Long = Long.MAX_VALUE
) {
    val isHealthy: Boolean
        get() = isRunning && outputFileAgeMs < 10_000L

    companion object {
        val NOT_RUNNING = DaemonStatus(isRunning = false, statusText = "not_running")
    }
}

@Singleton
class DaemonManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "DaemonManager"
        private const val DAEMON_SCRIPT_NAME = "heat_daemon.sh"
        private const val OUTPUT_DIR_RELATIVE = "files"
        private const val OUTPUT_FILE = "top_output.txt"
        private const val PID_FILE = "daemon.pid"
        private const val STATUS_FILE = "daemon.status"
        private const val STOP_FILE = "daemon.stop"
        private const val SCRIPT_VERSION = 1
    }

    private val externalDir: File?
        get() = context.getExternalFilesDir(null)

    private val topOutputFile: File?
        get() = externalDir?.let { File(it, OUTPUT_FILE) }

    private val pidFile: File?
        get() = externalDir?.let { File(it, PID_FILE) }

    private val statusFile: File?
        get() = externalDir?.let { File(it, STATUS_FILE) }

    private val stopFile: File?
        get() = externalDir?.let { File(it, STOP_FILE) }

    private fun ds(): String = "$"

    val adbStartCommand: String
        get() {
            val scriptPath = getScriptDeployPath()
            return "adb shell \"sh -c 'cp $scriptPath /data/local/tmp/$DAEMON_SCRIPT_NAME 2>/dev/null; chmod 755 /data/local/tmp/$DAEMON_SCRIPT_NAME 2>/dev/null; nohup sh /data/local/tmp/$DAEMON_SCRIPT_NAME >/dev/null 2>&1 &'\""
        }

    val adbStopCommand: String
        get() {
            val pidPath = "/sdcard/Android/data/${context.packageName}/files/$PID_FILE"
            val scriptPath = getScriptDeployPath()
            val d = ds()
            return "adb shell \"sh -c 'PID=${d}(cat $pidPath 2>/dev/null); if [ -n ${d}PID ]; then kill ${d}PID 2>/dev/null; fi; pkill -f heat_daemon.sh 2>/dev/null; rm -f $scriptPath $pidPath /data/local/tmp/$DAEMON_SCRIPT_NAME 2>/dev/null; echo done'\""
        }

    fun isScriptDeployed(): Boolean {
        val script = File(getScriptDeployPath())
        return script.exists() && script.length() > 50
    }

    fun deployScriptFromAssets(): Boolean {
        return try {
            val dir = externalDir
            if (dir == null) {
                Log.e(TAG, "deployScriptFromAssets: external files dir is null")
                return false
            }
            if (!dir.exists()) dir.mkdirs()

            val outFile = File(dir, DAEMON_SCRIPT_NAME)
            context.assets.open(DAEMON_SCRIPT_NAME).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outFile.setReadable(true, false)
            Log.i(TAG, "deployScriptFromAssets: script deployed to ${outFile.absolutePath}, size=${outFile.length()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "deployScriptFromAssets failed", e)
            false
        }
    }

    fun getDaemonStatus(): DaemonStatus {
        return try {
            val dir = externalDir ?: return DaemonStatus.NOT_RUNNING
            if (!dir.exists()) return DaemonStatus.NOT_RUNNING

            val pidF = pidFile ?: return DaemonStatus.NOT_RUNNING
            val statusF = statusFile ?: return DaemonStatus.NOT_RUNNING
            val outputF = topOutputFile ?: return DaemonStatus.NOT_RUNNING

            var pid = -1
            var statusText = "unknown"
            var lastUpdate = 0L

            if (pidF.exists()) {
                pid = pidF.readText().trim().toIntOrNull() ?: -1
            }

            if (statusF.exists()) {
                val statusContent = statusF.readText().trim()
                val parts = statusContent.split(" ")
                for (part in parts) {
                    val kv = part.split("=", limit = 2)
                    if (kv.size == 2) {
                        when (kv[0]) {
                            "pid" -> { if (pid < 0) pid = kv[1].toIntOrNull() ?: pid }
                            "time" -> lastUpdate = kv[1].toLongOrNull()?.times(1000) ?: lastUpdate
                            "status" -> statusText = kv[1]
                        }
                    }
                }
            }

            val outputAge = if (outputF.exists()) {
                System.currentTimeMillis() - outputF.lastModified()
            } else Long.MAX_VALUE

            val isRunning = pid > 0 && (statusText == "running" || outputAge < 10_000L)

            DaemonStatus(
                isRunning = isRunning,
                pid = pid,
                lastUpdateMs = lastUpdate,
                statusText = statusText,
                outputFileAgeMs = outputAge
            )
        } catch (e: Exception) {
            Log.e(TAG, "getDaemonStatus failed", e)
            DaemonStatus.NOT_RUNNING
        }
    }

    fun isTopOutputAvailable(): Boolean {
        val file = topOutputFile ?: return false
        if (!file.exists() || !file.canRead()) return false
        val age = System.currentTimeMillis() - file.lastModified()
        return age <= 15_000L && file.length() > 100
    }

    fun readTopOutput(): String? {
        return try {
            val file = topOutputFile ?: return null
            if (!file.exists() || !file.canRead()) return null
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "readTopOutput failed", e)
            null
        }
    }

    fun requestStop() {
        try {
            val stopF = stopFile ?: return
            stopF.writeText("stop")
            Log.i(TAG, "requestStop: stop file written")
        } catch (e: Exception) {
            Log.e(TAG, "requestStop failed", e)
        }
    }

    private fun getScriptDeployPath(): String {
        return "/sdcard/Android/data/${context.packageName}/files/$DAEMON_SCRIPT_NAME"
    }

    fun ensureScriptReady(): String {
        if (!isScriptDeployed()) {
            deployScriptFromAssets()
        }
        return getScriptDeployPath()
    }
}
