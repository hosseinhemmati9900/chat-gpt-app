package com.example.chatgptapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-only visual monitor for queue_control.txt. It does not control transfers. */
class QueueStatusMonitor(private val context: Context, private val onStatusChanged: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val controlFile = File(context.filesDir, "queue_control.txt")
    private val task = object : Runnable {
        override fun run() { readStatus(); handler.postDelayed(this, 60_000L) }
    }

    fun start() { stop(); readStatus(); handler.postDelayed(task, 60_000L) }
    fun stop() { handler.removeCallbacks(task) }

    private fun readStatus() {
        val raw = if (controlFile.exists()) runCatching { controlFile.readText(Charsets.UTF_8).trim() }.getOrDefault("") else ""
        val status = when (raw.lineSequence().firstOrNull()?.trim()?.uppercase(Locale.US)) {
            "PAUSE" -> "PAUSED"
            "RESUME" -> "RESUMED"
            else -> "RESUMED"
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        if (raw.isBlank()) android.util.Log.i("MediaDiagnostic", "$timestamp Control file missing, defaulting to RESUMED")
        else android.util.Log.i("MediaDiagnostic", "$timestamp Queue: $status")
        onStatusChanged("Queue: $status")
    }
}
