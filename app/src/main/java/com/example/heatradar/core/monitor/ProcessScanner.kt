package com.example.heatradar.core.monitor

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class RunningProcess(
    val pid: Int,
    val packageName: String,
    val appName: String,
    val memoryKb: Long,
    val cpuPercent: Float,
    val isVisibleToSystem: Boolean = false,
    val foregroundMinutes: Long = 0,
    val dataSource: String = "unknown"
)

@Singleton
class ProcessScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuServiceManager
) {

    private val TAG = "ProcessScanner"

    private val pm = context.packageManager
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private var appNameCache = mutableMapOf<String, String>()

    fun scanAllProcesses(): List<RunningProcess> {
        val t0 = System.currentTimeMillis()

        shizukuManager.ensureBound(context)
        val shizukuReady = shizukuManager.isAvailable()
        Log.i(TAG, "scanAllProcesses: shizukuReady=$shizukuReady")

        val results = if (shizukuReady) {
            val shizukuResult = scanViaShizuku()
            if (shizukuResult.isNotEmpty()) shizukuResult else scanViaUsageStats()
        } else {
            if (shizukuManager.needsPermission()) {
                shizukuManager.requestPermission()
            }
            // 优先尝试读取 ADB 后台 top 进程写入的文件（开发/调试方案，可获取真实 CPU 与内存）
            val topFileResult = scanViaTopFile()
            if (topFileResult.isNotEmpty()) topFileResult else scanViaUsageStats()
        }

        if (results.isEmpty()) {
            addOwnProcess(results)
        }

        val t1 = System.currentTimeMillis()
        Log.i(TAG, "scanAllProcesses: ${results.size} apps in ${t1 - t0}ms (source=${results.values.firstOrNull()?.dataSource})")
        if (results.isNotEmpty()) {
            val topByCpu = results.values.sortedByDescending { it.cpuPercent }.take(8)
            Log.i(TAG, "  Top by CPU:")
            topByCpu.forEach {
                Log.i(TAG, "    ${it.packageName} cpu=%.1f%% mem=%dKB pid=%d [%s]".format(
                    it.cpuPercent, it.memoryKb, it.pid, it.dataSource))
            }
        }

        return results.values.sortedByDescending { it.cpuPercent * 1024f + it.memoryKb }
    }

    private fun scanViaShizuku(): MutableMap<String, RunningProcess> {
        val results = mutableMapOf<String, RunningProcess>()
        try {
            val output = shizukuManager.executeCommandWithRetry("top -n 1 -b -q -o PID,USER,%CPU,RSS,NAME")
            if (output.isNullOrBlank()) {
                Log.w(TAG, "scanViaShizuku: empty output")
                return results
            }

            Log.i(TAG, "scanViaShizuku: top output length=${output.length}")

            val knownPackages = pm.getInstalledApplications(0)
                .map { it.packageName }.toHashSet()

            for (line in output.lines()) {
                val parsed = parseTopLine(line) ?: continue
                if (parsed.cpuPercent <= 0f && parsed.memoryKb <= 0L) continue

                val pkg = parsed.name
                if (!pkg.contains(".")) continue
                val basePkg = pkg.substringBefore(':')
                if (basePkg !in knownPackages) continue

                val existing = results[basePkg]
                if (existing == null || parsed.memoryKb > existing.memoryKb) {
                    results[basePkg] = RunningProcess(
                        pid = parsed.pid,
                        packageName = basePkg,
                        appName = getAppName(basePkg),
                        memoryKb = parsed.memoryKb,
                        cpuPercent = parsed.cpuPercent,
                        isVisibleToSystem = true,
                        dataSource = "shizuku"
                    )
                }
            }

            Log.i(TAG, "scanViaShizuku: parsed ${results.size} app processes")
        } catch (e: Exception) {
            Log.e(TAG, "scanViaShizuku failed", e)
        }
        return results
    }

    /**
     * 读取 ADB 后台 top 进程写入的文件（开发/调试方案）。
     *
     * 通过 `adb shell` 启动后台进程：
     *   nohup sh -c 'while true; do top -n 1 -b -q -o PID,USER,%CPU,RSS,NAME \
     *     > /sdcard/Android/data/<pkg>/files/top_output.txt 2>&1; sleep 3; done' >/dev/null 2>&1 &
     *
     * 该进程以 shell(uid 2000) 身份运行，可读取所有进程的真实 CPU 与内存数据，
     * 应用本身只需读取自身外部文件目录下的文件即可，无需特殊权限。
     *
     * 局限：设备重启后后台进程会消失，需重新通过 adb 启动。
     * 长期方案建议安装 Shizuku 应用（scanViaShizuku）。
     */
    private fun scanViaTopFile(): MutableMap<String, RunningProcess> {
        val results = mutableMapOf<String, RunningProcess>()
        try {
            val topFile = File(context.getExternalFilesDir(null), "top_output.txt")
            if (!topFile.exists()) {
                Log.d(TAG, "scanViaTopFile: file not found")
                return results
            }
            if (!topFile.canRead()) {
                Log.w(TAG, "scanViaTopFile: file exists but not readable")
                return results
            }

            // 检查文件新鲜度：超过 30 秒未更新视为后台进程已停止
            val ageMs = System.currentTimeMillis() - topFile.lastModified()
            if (ageMs > 30_000L) {
                Log.w(TAG, "scanViaTopFile: file stale (age=${ageMs}ms), background top process may be dead")
                return results
            }

            val output = topFile.readText()
            if (output.isBlank()) {
                Log.w(TAG, "scanViaTopFile: empty file")
                return results
            }

            val knownPackages = pm.getInstalledApplications(0)
                .map { it.packageName }.toHashSet()

            for (line in output.lines()) {
                val parsed = parseTopLine(line) ?: continue
                if (parsed.cpuPercent <= 0f && parsed.memoryKb <= 0L) continue

                val pkg = parsed.name
                if (!pkg.contains(".")) continue
                val basePkg = pkg.substringBefore(':')
                if (basePkg !in knownPackages) continue

                val existing = results[basePkg]
                if (existing == null || parsed.memoryKb > existing.memoryKb) {
                    results[basePkg] = RunningProcess(
                        pid = parsed.pid,
                        packageName = basePkg,
                        appName = getAppName(basePkg),
                        memoryKb = parsed.memoryKb,
                        cpuPercent = parsed.cpuPercent,
                        isVisibleToSystem = true,
                        dataSource = "topfile"
                    )
                }
            }

            Log.i(TAG, "scanViaTopFile: parsed ${results.size} app processes (file age=${ageMs}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "scanViaTopFile failed", e)
        }
        return results
    }

    private data class TopEntry(
        val pid: Int,
        val user: String,
        val cpuPercent: Float,
        val memoryKb: Long,
        val name: String
    )

    private fun parseTopLine(line: String): TopEntry? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("PID")) return null
        if (trimmed.startsWith("Load") || trimmed.startsWith("CPU")) return null

        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 5) return null

        val pid = parts[0].toIntOrNull() ?: return null
        val user = parts[1]
        val cpuPercent = parts[2].toFloatOrNull() ?: return null
        val rssStr = parts[3]
        val name = parts.subList(4, parts.size).joinToString(" ").trim()

        if (name.startsWith("[") && name.endsWith("]")) return null

        val memoryKb = parseRssToKb(rssStr)

        return TopEntry(pid, user, cpuPercent, memoryKb, name)
    }

    private fun parseRssToKb(rss: String): Long {
        return try {
            when {
                rss.endsWith("G") -> (rss.dropLast(1).toFloat() * 1024 * 1024).toLong()
                rss.endsWith("M") -> (rss.dropLast(1).toFloat() * 1024).toLong()
                rss.endsWith("K") -> rss.dropLast(1).toLongOrNull() ?: 0L
                else -> rss.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) { 0L }
    }

    private fun scanViaUsageStats(): MutableMap<String, RunningProcess> {
        val results = mutableMapOf<String, RunningProcess>()

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val hasUsageAccess = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED

        val runningProcs = am.runningAppProcesses ?: emptyList()
        val visiblePids = runningProcs.map { it.pid }.toIntArray()
        val pidMemMap = mutableMapOf<Int, Long>()

        if (visiblePids.isNotEmpty()) {
            try {
                val memInfos = am.getProcessMemoryInfo(visiblePids)
                visiblePids.forEachIndexed { idx, pid ->
                    if (idx in memInfos.indices) {
                        pidMemMap[pid] = memInfos[idx].totalPss.toLong()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getProcessMemoryInfo failed", e)
            }
        }

        for (proc in runningProcs) {
            val pkg = proc.pkgList?.firstOrNull() ?: proc.processName ?: continue
            if (!pkg.contains(".")) continue
            val basePkg = pkg.substringBefore(':')
            results[basePkg] = RunningProcess(
                pid = proc.pid,
                packageName = basePkg,
                appName = getAppName(basePkg),
                memoryKb = pidMemMap[proc.pid] ?: 0L,
                cpuPercent = 0f,
                isVisibleToSystem = true,
                dataSource = "system"
            )
        }

        if (hasUsageAccess) {
            val recentStats = getRecentUsageStats()
            for (stat in recentStats) {
                val pkg = stat.packageName
                if (results.containsKey(pkg)) {
                    val existing = results[pkg]!!
                    results[pkg] = existing.copy(foregroundMinutes = stat.totalTimeInForeground / 60000L)
                    continue
                }
                if (!isInstalledApp(pkg)) continue

                val fgMinutes = stat.totalTimeInForeground / 60000L
                val lastUsedAgo = System.currentTimeMillis() - stat.lastTimeUsed
                if (lastUsedAgo < 24 * 60 * 60 * 1000) {
                    val isSystem = isSystemApp(pkg)
                    if (!isSystem || fgMinutes > 0 || lastUsedAgo < 2 * 60 * 60 * 1000) {
                        results[pkg] = RunningProcess(
                            pid = 0,
                            packageName = pkg,
                            appName = getAppName(pkg),
                            memoryKb = 0L,
                            cpuPercent = 0f,
                            isVisibleToSystem = false,
                            foregroundMinutes = fgMinutes,
                            dataSource = "usagestats"
                        )
                    }
                }
            }
        }

        return results
    }

    private fun getRecentUsageStats(): List<UsageStats> {
        return try {
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - 7 * 24 * 60 * 60 * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, beginTime, endTime)
                ?: return emptyList()
            stats.filter { it.packageName.contains(".") && it.lastTimeUsed > beginTime }
                .distinctBy { it.packageName }
                .sortedByDescending { it.lastTimeUsed }
                .take(100)
        } catch (e: Exception) {
            Log.e(TAG, "getRecentUsageStats failed", e)
            emptyList()
        }
    }

    private fun isSystemApp(pkg: String): Boolean {
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) { false }
    }

    private fun isInstalledApp(pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) { false }
    }

    private fun addOwnProcess(results: MutableMap<String, RunningProcess>) {
        try {
            val myPid = Process.myPid()
            val myPackage = context.packageName
            val memInfos = am.getProcessMemoryInfo(intArrayOf(myPid))
            val memKb = memInfos.firstOrNull()?.totalPss?.toLong() ?: 0L
            results[myPackage] = RunningProcess(
                pid = myPid,
                packageName = myPackage,
                appName = getAppName(myPackage),
                memoryKb = memKb,
                cpuPercent = 0f,
                isVisibleToSystem = true,
                dataSource = "self"
            )
        } catch (e: Exception) {
            Log.e(TAG, "addOwnProcess failed", e)
        }
    }

    private fun getAppName(packageName: String): String {
        return appNameCache.getOrPut(packageName) {
            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName.substringAfterLast(".")
            }
        }
    }
}
