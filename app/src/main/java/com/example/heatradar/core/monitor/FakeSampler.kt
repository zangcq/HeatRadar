package com.example.heatradar.core.monitor

import com.example.heatradar.core.database.AppInfoEntity
import com.example.heatradar.core.database.HeatRadarRepository
import com.example.heatradar.core.database.ResourceSampleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Generates fake resource samples and writes them into the local Room database.
 * In a real app this would be replaced by a UsageStats-based collector or a system monitor.
 */
@Singleton
class FakeSampler @Inject constructor(
    private val repository: HeatRadarRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    private val fakeApps = listOf(
        AppInfoEntity("com.example.browser", "浏览器"),
        AppInfoEntity("com.example.social", "社交应用"),
        AppInfoEntity("com.example.game", "游戏"),
        AppInfoEntity("com.example.music", "音乐"),
        AppInfoEntity("com.example.mail", "邮件"),
        AppInfoEntity("com.example.maps", "地图"),
        AppInfoEntity("com.example.video", "视频")
    )

    fun start() {
        if (started.getAndSet(true)) return

        scope.launch {
            repository.insertAppInfo(fakeApps)

            while (isActive) {
                val now = System.currentTimeMillis()
                fakeApps.forEach { app ->
                    val sample = ResourceSampleEntity(
                        packageName = app.packageName,
                        timestamp = now,
                        cpuPercent = Random.nextFloat() * 30f,
                        memoryBytes = 50_000_000L + Random.nextLong(200_000_000L)
                    )
                    repository.insertSample(sample)
                }
                delay(2_000)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }
}
