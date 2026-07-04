package com.example.heatradar.core.monitor

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemorySampler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class ProcessMemoryInfo(
        val pid: Int,
        val packageName: String,
        val memoryKb: Long,
        val isForeground: Boolean
    )

    fun sampleRunningApps(): List<ProcessMemoryInfo> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningApps = am.runningAppProcesses ?: return emptyList()

        val pids = runningApps.map { it.pid }.toIntArray()
        val memoryInfos = am.getProcessMemoryInfo(pids)

        return runningApps.zip(memoryInfos).mapNotNull { (processInfo, memInfo) ->
            val packageName = processInfo.pkgList?.firstOrNull() ?: return@mapNotNull null
            val totalKb = memInfo.totalPss.toLong()
            val isForeground = processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            ProcessMemoryInfo(
                pid = processInfo.pid,
                packageName = packageName,
                memoryKb = totalKb,
                isForeground = isForeground
            )
        }
    }

    fun getMemoryForPackage(packageName: String): Long? {
        return sampleRunningApps()
            .firstOrNull { it.packageName == packageName }
            ?.memoryKb
    }
}
