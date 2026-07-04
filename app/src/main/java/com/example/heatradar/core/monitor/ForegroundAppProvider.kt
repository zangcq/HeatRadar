package com.example.heatradar.core.monitor

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundAppProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getForegroundPackageName(): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningTasks = am.getRunningTasks(1)
        if (runningTasks.isNotEmpty()) {
            return runningTasks[0].topActivity?.packageName
        }
        return null
    }
}
