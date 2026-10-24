package com.example.floatingassistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [PERMANENT FIX] Do not remove this!
 * FlightRecorder — High-Capacity Local Black Box Event Logger for GuidanceApp.
 *
 * Continuously records all actions, state transitions, clicks, HUD updates,
 * scout searches, and recovery decisions to an internal file:
 *   context.filesDir/flight_recorder.log
 *
 * Pull from device via adb:
 *   adb shell "run-as com.example.floatingassistant cat files/flight_recorder.log"
 *   adb pull /sdcard/Android/data/com.example.floatingassistant/files/flight_recorder.log
 *
 * Capacity:
 *   Configured with a high limit (15 MB) for deep debugging during active testing sessions.
 *   Rotates to flight_recorder.log.old when exceeding 15 MB.
 *   Asynchronous & non-blocking on Dispatchers.IO.
 */
object FlightRecorder {

    private const val TAG = "FlightRecorder"
    const val LOG_FILE_NAME = "flight_recorder.log"
    const val OLD_LOG_FILE_NAME = "flight_recorder.log.old"
    // [PERMANENT FIX] High capacity (15 MB) for extensive user debugging sessions
    private const val MAX_FILE_SIZE_BYTES = 15L * 1024L * 1024L 

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logChannel = Channel<String>(capacity = Channel.UNLIMITED)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    @Volatile private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        val targetDir = context.applicationContext.filesDir
        logFile = File(targetDir, LOG_FILE_NAME)
        isInitialized = true

        scope.launch {
            startWorker()
        }

        record("SYSTEM", "=== FlightRecorder Initialized (PID=${android.os.Process.myPid()}) ===")
    }

    private suspend fun startWorker() {
        val buffer = mutableListOf<String>()
        var lastFlushTime = System.currentTimeMillis()

        for (entry in logChannel) {
            buffer.add(entry)
            val now = System.currentTimeMillis()
            // Flush immediately if buffer reaches 10 items or after 250ms
            if (buffer.size >= 10 || (now - lastFlushTime) >= 250L) {
                flushBuffer(buffer)
                buffer.clear()
                lastFlushTime = now
            }
        }
    }

    private fun flushBuffer(buffer: List<String>) {
        val file = logFile ?: return
        if (buffer.isEmpty()) return

        try {
            if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                val oldFile = File(file.parentFile, OLD_LOG_FILE_NAME)
                if (oldFile.exists()) oldFile.delete()
                file.renameTo(oldFile)
                Log.i(TAG, "FlightRecorder rotated log file (> 15 MB limit)")
            }

            FileWriter(file, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    for (line in buffer) {
                        pw.println(line)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing to flight recorder: ${e.message}")
        }
    }

    fun record(tag: String, message: String, details: String? = null) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val line = if (!details.isNullOrBlank()) {
            "[$timestamp] [$tag] $message | $details"
        } else {
            "[$timestamp] [$tag] $message"
        }

        Log.d("[FlightRecorder]", line)

        if (isInitialized) {
            logChannel.trySend(line)
        }
    }

    fun recordError(tag: String, message: String, throwable: Throwable? = null) {
        val errSnippet = throwable?.let { " [Exception: ${it.javaClass.simpleName} - ${it.message}]" } ?: ""
        record("ERROR/$tag", "$message$errSnippet")
    }
}
