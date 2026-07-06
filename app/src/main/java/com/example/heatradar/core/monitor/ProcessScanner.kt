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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

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
    private val shizukuManager: ShizukuServiceManager,
    private val daemonManager: DaemonManager
) {

    companion object {
        private const val PACKAGE_CACHE_TTL_MS = 5 * 60 * 1000L
    }

    private val TAG = "ProcessScanner"

    private val pm = context.packageManager
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val cpuCoreCount: Int by lazy {
        Runtime.getRuntime().availableProcessors()
    }

    private var appNameCache = mutableMapOf<String, String>()
    private var cachedKnownPackages: HashSet<String>? = null
    private var cachedKnownPackagesTime: Long = 0L

    private var currentDataSource: String = "unknown"

    fun getCurrentDataSource(): String = currentDataSource

    fun scanAllProcesses(showSystemProcesses: Boolean = false): List<RunningProcess> {
        val t0 = System.currentTimeMillis()

        daemonManager.ensureScriptReady()

        val daemonReady = daemonManager.isTopOutputAvailable()
        shizukuManager.ensureBound(context)
        val shizukuReady = shizukuManager.isAvailable() && shizukuManager.isConnected()
        Log.i(TAG, "scanAllProcesses: daemonReady=$daemonReady shizukuReady=$shizukuReady showSystem=$showSystemProcesses")

        val results = when {
            daemonReady -> {
                val daemonResult = scanViaDaemon()
                if (daemonResult.isNotEmpty()) daemonResult else {
                    if (shizukuReady) scanViaShizuku() else scanViaUsageStats()
                }
            }
            shizukuReady -> {
                val shizukuResult = scanViaShizuku()
                if (shizukuResult.isNotEmpty()) shizukuResult else scanViaUsageStats()
            }
            else -> {
                if (shizukuManager.needsPermission()) {
                    shizukuManager.requestPermission()
                }
                scanViaUsageStats()
            }
        }

        val filtered = if (showSystemProcesses) results else filterOutSystemProcesses(results)

        if (filtered.isEmpty()) {
            addOwnProcess(filtered)
        }

        val t1 = System.currentTimeMillis()
        Log.i(TAG, "scanAllProcesses: ${filtered.size} apps in ${t1 - t0}ms (source=$currentDataSource)")
        if (filtered.isNotEmpty()) {
            val topByCpu = filtered.values.sortedByDescending { it.cpuPercent }.take(8)
            Log.i(TAG, "  Top by CPU:")
            topByCpu.forEach {
                Log.i(TAG, "    ${it.packageName} cpu=%.1f%% mem=%dKB pid=%d [%s]".format(
                    it.cpuPercent, it.memoryKb, it.pid, it.dataSource))
            }
        }

        return filtered.values.sortedByDescending { it.cpuPercent * 1024f + it.memoryKb }
    }

    private fun filterOutSystemProcesses(results: MutableMap<String, RunningProcess>): MutableMap<String, RunningProcess> {
        val filtered = mutableMapOf<String, RunningProcess>()
        for ((pkg, proc) in results) {
            if (isSystemApp(pkg) && proc.cpuPercent < 1f && proc.memoryKb < 50 * 1024) {
                continue
            }
            filtered[pkg] = proc
        }
        return filtered
    }

    private fun normalizeCpuPercent(rawPercent: Float): Float {
        val normalized = rawPercent / cpuCoreCount.toFloat()
        return max(0f, min(100f, normalized))
    }

    private fun getKnownPackages(): HashSet<String> {
        val now = System.currentTimeMillis()
        val cached = cachedKnownPackages
        if (cached != null && now - cachedKnownPackagesTime < PACKAGE_CACHE_TTL_MS) {
            return cached
        }
        val packages = pm.getInstalledApplications(0)
            .map { it.packageName }.toHashSet()
        cachedKnownPackages = packages
        cachedKnownPackagesTime = now
        return packages
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun scanViaShizuku(): MutableMap<String, RunningProcess> {
        val results = mutableMapOf<String, RunningProcess>()
        val cpuSums = mutableMapOf<String, Float>()
        val memMaxs = mutableMapOf<String, Long>()
        val pidMaps = mutableMapOf<String, Int>()
        try {
            val output = shizukuManager.executeCommandWithRetry("top -n 1 -b -q -o PID,USER,%CPU,RSS,NAME")
            if (output.isNullOrBlank()) {
                Log.w(TAG, "scanViaShizuku: empty output")
                return results
            }

            Log.i(TAG, "scanViaShizuku: top output length=${output.length}")

            val knownPackages = getKnownPackages()

            for (line in output.lines()) {
                val parsed = parseTopLine(line) ?: continue
                if (parsed.cpuPercent <= 0f && parsed.memoryKb <= 0L) continue

                val pkg = parsed.name
                if (!pkg.contains(".")) continue
                val basePkg = pkg.substringBefore(':')
                if (basePkg !in knownPackages) continue

                val normalizedCpu = normalizeCpuPercent(parsed.cpuPercent)
                cpuSums[basePkg] = (cpuSums[basePkg] ?: 0f) + normalizedCpu
                val existingMem = memMaxs[basePkg] ?: 0L
                if (parsed.memoryKb > existingMem) {
                    memMaxs[basePkg] = parsed.memoryKb
                    pidMaps[basePkg] = parsed.pid
                }
            }

            for (basePkg in cpuSums.keys) {
                results[basePkg] = RunningProcess(
                    pid = pidMaps[basePkg] ?: 0,
                    packageName = basePkg,
                    appName = getAppName(basePkg),
                    memoryKb = memMaxs[basePkg] ?: 0L,
                    cpuPercent = (cpuSums[basePkg] ?: 0f).coerceIn(0f, 100f),
                    isVisibleToSystem = true,
                    dataSource = "shizuku"
                )
            }

            if (results.isNotEmpty()) {
                currentDataSource = "shizuku"
            }
            Log.i(TAG, "scanViaShizuku: parsed ${results.size} app processes")
        } catch (e: Exception) {
            Log.e(TAG, "scanViaShizuku failed", e)
        }
        return results
    }

    private fun scanViaDaemon(): MutableMap<String, RunningProcess> {
        val results = mutableMapOf<String, RunningProcess>()
        val cpuSums = mutableMapOf<String, Float>()
        val memMaxs = mutableMapOf<String, Long>()
        val pidMaps = mutableMapOf<String, Int>()
        try {
            val output = daemonManager.readTopOutput()
            if (output.isNullOrBlank()) {
                Log.w(TAG, "scanViaDaemon: empty output")
                return results
            }

            val status = daemonManager.getDaemonStatus()
            Log.i(TAG, "scanViaDaemon: output length=${output.length}, pid=${status.pid}, age=${status.outputFileAgeMs}ms")

            val knownPackages = getKnownPackages()

            for (line in output.lines()) {
                val parsed = parseTopLine(line) ?: continue
                if (parsed.cpuPercent <= 0f && parsed.memoryKb <= 0L) continue

                val pkg = parsed.name
                if (!pkg.contains(".")) continue
                val basePkg = pkg.substringBefore(':')
                if (basePkg !in knownPackages) continue

                val normalizedCpu = normalizeCpuPercent(parsed.cpuPercent)
                cpuSums[basePkg] = (cpuSums[basePkg] ?: 0f) + normalizedCpu
                val existingMem = memMaxs[basePkg] ?: 0L
                if (parsed.memoryKb > existingMem) {
                    memMaxs[basePkg] = parsed.memoryKb
                    pidMaps[basePkg] = parsed.pid
                }
            }

            for (basePkg in cpuSums.keys) {
                results[basePkg] = RunningProcess(
                    pid = pidMaps[basePkg] ?: 0,
                    packageName = basePkg,
                    appName = getAppName(basePkg),
                    memoryKb = memMaxs[basePkg] ?: 0L,
                    cpuPercent = (cpuSums[basePkg] ?: 0f).coerceIn(0f, 100f),
                    isVisibleToSystem = true,
                    dataSource = "daemon"
                )
            }

            if (results.isNotEmpty()) {
                currentDataSource = "daemon"
            }
            Log.i(TAG, "scanViaDaemon: parsed ${results.size} app processes")
        } catch (e: Exception) {
            Log.e(TAG, "scanViaDaemon failed", e)
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

        val hasUsageAccess = hasUsageStatsPermission()

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

        currentDataSource = if (hasUsageAccess) "usagestats" else "system"

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
            currentDataSource = "self"
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
