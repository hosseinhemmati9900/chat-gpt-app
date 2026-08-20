package com.example.chatgptapp

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Reads sync settings strictly from the app's internal storage. */
data class SyncConfig(
    val uploadEndpoint: String,
    val maxArchiveSizeMb: Int,
    val enableSync: Boolean
) {
    companion object {
        private const val FILE_NAME = "sync-settings.json"

        fun load(context: Context): SyncConfig {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                return SyncConfig("", 0, false)
            }
            val json = JSONObject(file.readText(Charsets.UTF_8))
            return SyncConfig(
                uploadEndpoint = json.optString("upload_endpoint", ""),
                maxArchiveSizeMb = json.optInt("max_archive_size_mb", 0),
                enableSync = json.optBoolean("enable_sync", false)
            )
        }
    }
}
