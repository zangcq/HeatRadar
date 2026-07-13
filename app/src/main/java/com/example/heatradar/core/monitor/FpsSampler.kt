package com.example.heatradar.core.monitor

import android.util.Log
import javax.inject.Inject

class FpsSampler @Inject constructor(
    private val shizukuManager: ShizukuServiceManager
) {
    companion object {
        private const val TAG = "FpsSampler"
        private const val MIN_SAMPLE_INTERVAL_MS = 2000L
    }

    private var lastSampleTime = 0L
    private var lastFps = 0f

    fun sample(): Float {
        val now = System.currentTimeMillis()
        if (now - lastSampleTime < MIN_SAMPLE_INTERVAL_MS) {
            return lastFps
        }
        lastSampleTime = now

        if (!shizukuManager.isAvailable() || !shizukuManager.isConnected()) {
            return lastFps
        }

        val fps = try {
            val output = shizukuManager.executeCommandWithRetry(
                "dumpsys SurfaceFlinger --latency",
                maxRetries = 2,
                retryDelayMs = 200L
            )
            if (!output.isNullOrBlank()) {
                parseFps(output)
            } else {
                lastFps
            }
        } catch (e: Exception) {
            Log.w(TAG, "FPS sample failed: ${e.message}")
            lastFps
        }

        lastFps = fps
        return fps
    }

    private fun parseFps(output: String): Float {
        return try {
            // SurfaceFlinger --latency output format:
            // <timestamp> <vsync_timestamp> <desired_timestamp>
            // First line is refresh period in nanoseconds
            val lines = output.lines().filter { it.isNotBlank() }
            if (lines.size < 2) return 0f

            val refreshPeriodNs = lines[0].trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                ?: return 0f
            if (refreshPeriodNs <= 0) return 0f

            val refreshPeriodMs = refreshPeriodNs / 1_000_000f
            val fps = 1000f / refreshPeriodMs
            fps.coerceIn(1f, 240f)
        } catch (e: Exception) {
            Log.w(TAG, "parseFps failed: ${e.message}")
            0f
        }
    }
}
