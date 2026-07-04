package com.example.heatradar.core.monitor

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class CommandService : ICommandService.Stub() {

    private val TAG = "CommandService"

    override fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            Log.i(TAG, "executeCommand: '$command' -> ${output.length} bytes")
            output
        } catch (e: Exception) {
            Log.e(TAG, "executeCommand failed: '$command'", e)
            ""
        }
    }
}
