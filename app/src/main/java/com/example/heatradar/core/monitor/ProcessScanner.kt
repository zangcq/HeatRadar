package com.example.heatradar.core.monitor

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
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
    private val daemonManager: DaemonManager,
    private val metricsHolder: SystemMetricsHolder
) {

    companion object {
        private const val TAG = "ProcessScanner"
        private const val PACKAGE_CACHE_TTL_MS = 30 * 60 * 1000L
    }

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
    private var logCount = 0
    private var lastTopOutputLogged: Long = 0

    fun getCurrentDataSource(): String = currentDataSource

    fun scanAllProcesses(showSystemProcesses: Boolean = false): List<RunningProcess> {
        val t0 = System.currentTimeMillis()

        daemonManager.ensureScriptReady()

        val daemonReady = daemonManager.isTopOutputAvailable()
        shizukuManager.ensureBound(context)
        val shizukuReady = shizukuManager.isAvailable() && shizukuManager.isConnected()

        if (logCount < 3 || logCount % 15 == 0) {
            Log.i(TAG, "scanAllProcesses: daemonReady=$daemonReady shizukuReady=$shizukuReady showSystem=$showSystemProcesses")
        }

        val results = when {
            daemonReady -> {
                val daemonResult = scanViaDaemon()
                if (daemonResult.isNotEmpty()) daemonResult else {
                    if (shizukuReady) scanViaShizuku() else {
                        metricsHolder.sampleCpuFromProc()
                        scanViaUsageStats()
                    }
                }
            }
            shizukuReady -> {
                val shizukuResult = scanViaShizuku()
                if (shizukuResult.isNotEmpty()) shizukuResult else {
                    metricsHolder.sampleCpuFromProc()
                    scanViaUsageStats()
                }
            }
            else -> {
                if (shizukuManager.needsPermission()) {
                    shizukuManager.requestPermission()
                }
                metricsHolder.sampleCpuFromProc()
                scanViaUsageStats()
            }
        }

        val filtered = if (showSystemProcesses) results else filterOutSystemProcesses(results)

        if (filtered.isEmpty()) {
            addOwnProcess(filtered)
        }

        val t1 = System.currentTimeMillis()
        logCount++
        if (logCount < 5 || logCount % 10 == 0) {
            val cpuSnapshot = metricsHolder.getSnapshot()
            Log.i(TAG, "scanAllProcesses: ${filtered.size} apps in ${t1 - t0}ms (source=$currentDataSource) cpu=%.1f%% avail=%s".format(
                cpuSnapshot.totalCpuPercent, cpuSnapshot.available))
            if (filtered.isNotEmpty() && logCount < 5) {
                val topByCpu = filtered.values.sortedByDescending { it.cpuPercent }.take(5)
                topByCpu.forEach {
                    Log.i(TAG, "  ${it.packageName} cpu=%.1f%% mem=%dKB".format(it.cpuPercent, it.memoryKb))
                }
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
        val effectiveCores = metricsHolder.getCpuCoreCount().coerceAtLeast(1)
        val normalized = rawPercent / effectiveCores.toFloat()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                ) == AppOpsManager.MODE_ALLOWED
            } else false
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
                return results
            }

            parseTopCpuHeader(output)

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
                return results
            }

            val status = daemonManager.getDaemonStatus()
            val now = System.currentTimeMillis()
            if (now - lastTopOutputLogged > 30_000L || logCount < 3) {
                Log.d(TAG, "scanViaDaemon: output length=${output.length}, pid=${status.pid}, age=${status.outputFileAgeMs}ms")
                Log.d(TAG, "scanViaDaemon: first 300 chars: ${output.take(300)}")
                lastTopOutputLogged = now
            }
            parseTopCpuHeader(output)

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
        } catch (e: Exception) {
            Log.e(TAG, "scanViaDaemon failed", e)
        }
        return results
    }

    private fun parseTopCpuHeader(output: String): SystemMetricsSnapshot? {
        return try {
            var totalCpu = 0f
            var cores = cpuCoreCount
            var cpuFreqs: List<Long> = emptyList()
            var temps: List<ThermalZone> = emptyList()
            var memory = MemoryInfo()
            var gpu = GpuInfo()
            var power = PowerInfo()
            var foundCpu = false

            for (line in output.lines()) {
                val trimmed = line.trim()

                val hrCpuMatch = Regex("^HR_CPU\\s+total=(\\d+(?:\\.\\d+)?)%\\s+cores=(\\d+)").find(trimmed)
                if (hrCpuMatch != null) {
                    totalCpu = hrCpuMatch.groupValues[1].toFloatOrNull() ?: 0f
                    cores = (hrCpuMatch.groupValues[2].toIntOrNull() ?: cpuCoreCount).coerceAtLeast(1)
                    foundCpu = true
                    continue
                }

                if (trimmed.startsWith("HR_TEMP")) {
                    temps = parseTemps(trimmed.removePrefix("HR_TEMP").trim())
                    continue
                }

                if (trimmed.startsWith("HR_FREQ")) {
                    cpuFreqs = parseFreqs(trimmed.removePrefix("HR_FREQ").trim())
                    continue
                }

                if (trimmed.startsWith("HR_MEM")) {
                    memory = parseMemory(trimmed.removePrefix("HR_MEM").trim())
                    continue
                }

                if (trimmed.startsWith("HR_GPU")) {
                    gpu = parseGpu(trimmed.removePrefix("HR_GPU").trim())
                    continue
                }

                if (trimmed.startsWith("HR_POWER")) {
                    power = parsePower(trimmed.removePrefix("HR_POWER").trim())
                    continue
                }
            }

            if (foundCpu) {
                if (logCount < 5) {
                    Log.i(TAG, "parseTopCpuHeader: cpu=%.1f%% cores=%d temps=%d freqs=%d memTotal=%dMB gpu=%.0f%% power=%dmW".format(
                        totalCpu, cores, temps.size, cpuFreqs.size, memory.totalMb, gpu.gpuBusyPercent, power.powerMw))
                }
                metricsHolder.updateFromDaemon(
                    totalCpu = totalCpu,
                    cores = cores,
                    cpuFreqsKhz = cpuFreqs,
                    temps = temps,
                    memory = memory,
                    gpu = gpu,
                    power = power
                )
                metricsHolder.getSnapshot()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseTopCpuHeader failed", e)
            null
        }
    }

    private fun parseTemps(text: String): List<ThermalZone> {
        val zones = mutableListOf<ThermalZone>()
        val regex = Regex("([\\w\\-]+)=(-?\\d+)")
        for (match in regex.findAll(text)) {
            val type = match.groupValues[1]
            val temp = match.groupValues[2].toIntOrNull() ?: continue
            if (temp in -40..200) {
                zones.add(ThermalZone(type, temp))
            }
        }
        return zones
    }

    private fun parseFreqs(text: String): List<Long> {
        return text.trim().split(Regex("\\s+"))
            .mapNotNull { it.toLongOrNull() }
            .filter { it > 0 }
    }

    private fun parseMemory(text: String): MemoryInfo {
        val map = mutableMapOf<String, Long>()
        val regex = Regex("(\\w+)=(\\d+)")
        for (match in regex.findAll(text)) {
            map[match.groupValues[1]] = match.groupValues[2].toLongOrNull() ?: 0L
        }
        return MemoryInfo(
            memTotalKb = map["MemTotal"] ?: 0L,
            memFreeKb = map["MemFree"] ?: 0L,
            memAvailableKb = map["MemAvailable"] ?: 0L,
            cachedKb = map["Cached"] ?: 0L,
            buffersKb = map["Buffers"] ?: 0L,
            swapTotalKb = map["SwapTotal"] ?: 0L,
            swapFreeKb = map["SwapFree"] ?: 0L,
            swapCachedKb = map["SwapCached"] ?: 0L
        )
    }

    private fun parseGpu(text: String): GpuInfo {
        var busy = 0f
        var clk = 0L
        val regex = Regex("(\\w+)=([\\d.]+)")
        for (match in regex.findAll(text)) {
            when (match.groupValues[1]) {
                "gpu_busy" -> busy = match.groupValues[2].toFloatOrNull() ?: 0f
                "gpu_clk" -> clk = match.groupValues[2].toLongOrNull() ?: 0L
            }
        }
        return GpuInfo(gpuBusyPercent = busy.coerceIn(0f, 100f), gpuClkHz = clk)
    }

    private fun parsePower(text: String): PowerInfo {
        var current = 0L
        var voltage = 0L
        var power = 0L
        var status = ""
        var health = ""
        var capacity = 0
        val regex = Regex("(\\w+)=(-?[\\d.]+)")
        for (match in regex.findAll(text)) {
            val key = match.groupValues[1]
            val raw = match.groupValues[2]
            when (key) {
                "current" -> current = raw.toLongOrNull() ?: 0L
                "voltage" -> voltage = raw.toLongOrNull() ?: 0L
                "power" -> power = raw.toLongOrNull() ?: 0L
                "status" -> status = raw
                "health" -> health = raw
                "capacity" -> capacity = raw.toIntOrNull() ?: 0
            }
        }
        return PowerInfo(
            currentUa = current,
            voltageUv = voltage,
            powerMw = power,
            status = status,
            health = health,
            capacity = capacity
        )
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
        if (trimmed.startsWith("Load") || trimmed.startsWith("CPU") ||
            trimmed.startsWith("Tasks") || trimmed.startsWith("Mem") ||
            trimmed.startsWith("Swap")) return null
        if (trimmed.startsWith("HR_CPU") || trimmed.startsWith("HR_TEMP") ||
            trimmed.startsWith("HR_FREQ") || trimmed.startsWith("HR_MEM") ||
            trimmed.startsWith("HR_GPU") || trimmed.startsWith("HR_POWER")) return null
        if (trimmed.contains("%cpu") || trimmed.contains("cpu%")) return null

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
