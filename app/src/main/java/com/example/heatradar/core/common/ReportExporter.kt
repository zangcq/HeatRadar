package com.example.heatradar.core.common

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.heatradar.core.database.DeviceStateDao
import com.example.heatradar.core.database.HeatRadarRepository
import com.example.heatradar.core.database.ResourceSampleDao
import com.example.heatradar.core.database.SampleAggregate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HeatRadarRepository,
    private val sampleDao: ResourceSampleDao,
    private val deviceStateDao: DeviceStateDao
) {
    private val reportsDir: File
        get() = File(context.filesDir, "reports").also { it.mkdirs() }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    suspend fun exportHtmlReport(hours: Int = 24): Uri? {
        val since = System.currentTimeMillis() - hours * 60 * 60 * 1000L
        val aggregates = sampleDao.getAggregatedSince(since)
        val deviceStates = deviceStateDao.getStatesSince(since)
        val avgCpu = deviceStateDao.getAvgCpuSince(since) ?: 0f
        val maxCpu = deviceStateDao.getMaxCpuSince(since) ?: 0f
        val avgTemp = deviceStateDao.getAvgTempSince(since) ?: 0f
        val maxTemp = deviceStateDao.getMaxTempSince(since) ?: 0f
        val avgMem = deviceStateDao.getAvgMemSince(since) ?: 0f

        val fileName = "HeatRadar_Report_${fileDateFormat.format(Date())}.html"
        val file = File(reportsDir, fileName)

        FileWriter(file).use { writer ->
            writer.write(buildHtml(aggregates, avgCpu, maxCpu, avgTemp, maxTemp, avgMem, hours, deviceStates))
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    suspend fun exportCsvReport(hours: Int = 24): Uri? {
        val since = System.currentTimeMillis() - hours * 60 * 60 * 1000L
        val aggregates = sampleDao.getAggregatedSince(since)

        val fileName = "HeatRadar_Data_${fileDateFormat.format(Date())}.csv"
        val file = File(reportsDir, fileName)

        FileWriter(file).use { writer ->
            writer.write("App Package,App Name,Avg CPU%,Max CPU%,Avg Memory(MB),Max Memory(MB),Active Min\n")
            aggregates.forEach { a ->
                val name = a.appName ?: a.packageName.substringAfterLast(".")
                writer.write("${escapeCsv(a.packageName)},${escapeCsv(name)},${"%.1f".format(a.avgCpu)},${"%.1f".format(a.maxCpu)},${a.avgMem / (1024 * 1024)},${a.maxMem / (1024 * 1024)},${a.activeMinutes}\n")
            }
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun buildHtml(
        aggregates: List<SampleAggregate>,
        avgCpu: Float, maxCpu: Float,
        avgTemp: Float, maxTemp: Float,
        avgMem: Float,
        hours: Int,
        deviceStates: List<com.example.heatradar.core.database.DeviceStateEntity>
    ): String {
        val now = dateFormat.format(Date())
        val topByCpu = aggregates.sortedByDescending { it.maxCpu }.take(20)
        val topByMem = aggregates.sortedByDescending { it.maxMem }.take(20)

        val cpuSparkline = buildSparklineSvg(deviceStates.map { it.cpuUsagePercent }, 600, 60, "#F44336")
        val tempSparkline = buildSparklineSvg(deviceStates.map { it.temperatureCelsius }, 600, 60, "#FF9800")
        val memSparkline = buildSparklineSvg(deviceStates.map { it.memoryUsagePercent }, 600, 60, "#2196F3")

        return """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>HeatRadar 资源监控报告</title>
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background:#f5f5f5; color:#333; padding:20px; max-width:800px; margin:0 auto; }
.header { background:linear-gradient(135deg,#1A237E,#0D47A1); color:white; padding:24px; border-radius:12px; margin-bottom:20px; }
.header h1 { font-size:24px; margin-bottom:8px; }
.header p { opacity:0.8; font-size:14px; }
.stats { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; margin-bottom:20px; }
.stat-card { background:white; padding:16px; border-radius:8px; text-align:center; box-shadow:0 2px 8px rgba(0,0,0,0.08); }
.stat-card .label { font-size:12px; color:#888; margin-bottom:4px; }
.stat-card .value { font-size:24px; font-weight:bold; }
.stat-card .sub { font-size:11px; color:#aaa; margin-top:2px; }
.cpu-color { color:#F44336; } .mem-color { color:#2196F3; } .temp-color { color:#FF9800; }
.section { background:white; padding:20px; border-radius:12px; margin-bottom:16px; box-shadow:0 2px 8px rgba(0,0,0,0.08); }
.section h2 { font-size:18px; margin-bottom:16px; padding-bottom:8px; border-bottom:2px solid #f0f0f0; }
.chart { margin-bottom:16px; }
.chart-title { font-size:13px; color:#666; margin-bottom:4px; }
table { width:100%; border-collapse:collapse; font-size:13px; }
th, td { padding:10px 8px; text-align:left; border-bottom:1px solid #f0f0f0; }
th { background:#fafafa; font-weight:600; color:#555; font-size:12px; }
.bar-cell { width:120px; }
.bar-bg { height:8px; background:#f0f0f0; border-radius:4px; overflow:hidden; }
.bar-fill { height:100%; border-radius:4px; transition:width 0.3s; }
.high { background:#F44336; } .med { background:#FF9800; } .low { background:#4CAF50; }
.rank { width:30px; text-align:center; color:#aaa; font-weight:bold; }
.app-name { font-weight:500; }
.app-pkg { font-size:11px; color:#aaa; }
.footer { text-align:center; color:#aaa; font-size:12px; padding:20px; }
</style>
</head>
<body>
<div class="header">
  <h1>🔥 HeatRadar 资源监控报告</h1>
  <p>报告生成时间：$now | 统计范围：最近 $hours 小时</p>
</div>

<div class="stats">
  <div class="stat-card">
    <div class="label">CPU 使用率</div>
    <div class="value cpu-color">${"%.0f".format(avgCpu)}%</div>
    <div class="sub">峰值 ${"%.0f".format(maxCpu)}%</div>
  </div>
  <div class="stat-card">
    <div class="label">内存使用率</div>
    <div class="value mem-color">${"%.0f".format(avgMem)}%</div>
    <div class="sub">平均占用</div>
  </div>
  <div class="stat-card">
    <div class="label">设备温度</div>
    <div class="value temp-color">${"%.0f".format(avgTemp)}°C</div>
    <div class="sub">峰值 ${"%.0f".format(maxTemp)}°C</div>
  </div>
</div>

<div class="section">
  <h2>📈 设备状态趋势</h2>
  <div class="chart">
    <div class="chart-title">CPU 使用率 (%)</div>
    $cpuSparkline
  </div>
  <div class="chart">
    <div class="chart-title">内存使用率 (%)</div>
    $memSparkline
  </div>
  <div class="chart">
    <div class="chart-title">设备温度 (°C)</div>
    $tempSparkline
  </div>
</div>

<div class="section">
  <h2>🔥 CPU 占用 Top 20</h2>
  <table>
    <tr><th class="rank">#</th><th>应用</th><th>CPU 峰值</th><th>CPU 均值</th><th class="bar-cell">峰值占比</th></tr>
    ${topByCpu.mapIndexed { i, a -> """<tr>
      <td class="rank">${i+1}</td>
      <td><div class="app-name">${escapeHtml(a.appName ?: a.packageName.substringAfterLast("."))}</div><div class="app-pkg">${escapeHtml(a.packageName)}</div></td>
      <td class="cpu-color"><b>${"%.1f".format(a.maxCpu)}%</b></td>
      <td>${"%.1f".format(a.avgCpu)}%</td>
      <td class="bar-cell"><div class="bar-bg"><div class="bar-fill ${barClass(a.maxCpu)}" style="width:${(a.maxCpu.coerceAtMost(100f)).toInt()}%"></div></div></td>
    </tr>"""}.joinToString("\n")}
  </table>
</div>

<div class="section">
  <h2>💾 内存占用 Top 20</h2>
  <table>
    <tr><th class="rank">#</th><th>应用</th><th>内存峰值</th><th>内存均值</th><th class="bar-cell">峰值占比</th></tr>
    ${topByMem.mapIndexed { i, a -> """<tr>
      <td class="rank">${i+1}</td>
      <td><div class="app-name">${escapeHtml(a.appName ?: a.packageName.substringAfterLast("."))}</div><div class="app-pkg">${escapeHtml(a.packageName)}</div></td>
      <td class="mem-color"><b>${a.maxMem / (1024*1024)} MB</b></td>
      <td>${a.avgMem / (1024*1024)} MB</td>
      <td class="bar-cell"><div class="bar-bg"><div class="bar-fill med" style="width:${((a.maxMem.toFloat() / (8L*1024*1024*1024)) * 100).coerceIn(0f,100f).toInt()}%"></div></div></td>
    </tr>"""}.joinToString("\n")}
  </table>
</div>

<div class="footer">Generated by HeatRadar v2.0</div>
</body>
</html>"""
    }

    private fun buildSparklineSvg(values: List<Float>, width: Int, height: Int, color: String): String {
        if (values.isEmpty()) return "<i>暂无数据</i>"
        val max = values.maxOrNull() ?: 1f
        val min = values.minOrNull() ?: 0f
        val range = (max - min).coerceAtLeast(1f)
        val step = width.toFloat() / (values.size - 1).coerceAtLeast(1)
        val points = values.mapIndexed { i, v ->
            val x = i * step
            val y = height - ((v - min) / range) * (height - 4) - 2
            "${"%.1f".format(x)},${"%.1f".format(y)}"
        }.joinToString(" ")
        val areaPoints = "0,${height} $points ${width},${height}"
        return """<svg viewBox="0 0 $width $height" width="100%" height="${height}px" preserveAspectRatio="none">
  <polygon points="$areaPoints" fill="$color" fill-opacity="0.1"/>
  <polyline points="$points" fill="none" stroke="$color" stroke-width="2" stroke-linejoin="round"/>
</svg>"""
    }

    private fun barClass(value: Float): String = when {
        value >= 50f -> "high"
        value >= 20f -> "med"
        else -> "low"
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun escapeCsv(text: String): String {
        return if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else text
    }
}
