package com.example.heatradar.core.monitor

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuServiceManager @Inject constructor() {

    private val TAG = "ShizukuServiceManager"

    private var commandService: ICommandService? = null
    private var isBinding = false

    fun isAvailable(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun isConnected(): Boolean {
        return commandService != null
    }

    fun needsPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (needsPermission() && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    @Synchronized
    fun ensureBound(context: android.content.Context) {
        if (commandService != null || isBinding) return
        if (!isAvailable()) return

        isBinding = true
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, CommandService::class.java)
            )
                .daemon(false)
                .processNameSuffix("cmd")
                .debuggable(true)
                .version(1)

            Shizuku.bindUserService(args, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    commandService = ICommandService.Stub.asInterface(service)
                    isBinding = false
                    Log.i(TAG, "Shizuku CommandService connected")
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    commandService = null
                    isBinding = false
                    Log.w(TAG, "Shizuku CommandService disconnected")
                }
            })
        } catch (e: Exception) {
            isBinding = false
            Log.e(TAG, "bindUserService failed", e)
        }
    }

    fun executeCommand(command: String): String? {
        val service = commandService ?: return null
        return try {
            service.executeCommand(command)
        } catch (e: Exception) {
            Log.e(TAG, "executeCommand failed: '$command'", e)
            null
        }
    }

    fun executeCommandWithRetry(command: String, maxRetries: Int = 5, retryDelayMs: Long = 300L): String? {
        repeat(maxRetries) { attempt ->
            val service = commandService
            if (service != null) {
                val result = try {
                    service.executeCommand(command)
                } catch (e: Exception) {
                    Log.e(TAG, "executeCommand attempt ${attempt + 1} failed: '$command'", e)
                    null
                }
                if (!result.isNullOrBlank()) return result
            }
            if (attempt < maxRetries - 1) {
                Thread.sleep(retryDelayMs)
            }
        }
        return null
    }
}
