package com.example.heatradar.core.monitor

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.example.heatradar.core.database.AppInfoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageStatsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun createPermissionIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun getRecentApps(hours: Int = 1): List<UsageStats> {
        if (!hasPermission()) return emptyList()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - hours * 60 * 60 * 1000L

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, beginTime, endTime)
            ?: return emptyList()

        return stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.lastTimeUsed }
    }

    fun getRecentAppPackageNames(hours: Int = 1): List<String> {
        return getRecentApps(hours).map { it.packageName }.distinct()
    }

    fun getForegroundTimeMinutes(packageName: String, hours: Int = 1): Long {
        return getRecentApps(hours)
            .filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground } / 60000L
    }
}
