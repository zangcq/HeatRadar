package com.example.heatradar.core.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.heatradar.core.database.AppInfoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getInstalledApps(): List<AppInfoEntity> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val now = System.currentTimeMillis()

        return packages.map { appInfo ->
            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                appInfo.packageName.substringAfterLast(".")
            }
            AppInfoEntity(
                packageName = appInfo.packageName,
                appName = appName,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
    }

    fun getAppInfo(packageName: String): AppInfoEntity? {
        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(appInfo).toString()
            AppInfoEntity(
                packageName = packageName,
                appName = appName,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
