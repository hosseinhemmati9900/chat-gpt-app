package com.example.chatgptapp

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var generateButton: Button
    private lateinit var syncToggle: ToggleButton
    private lateinit var groupArchivesButton: Button
    private lateinit var archiveListContainer: LinearLayout
    private lateinit var sendArchiveButton: Button
    private val permissionRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        generateButton = findViewById(R.id.generateReportButton)
        syncToggle = findViewById(R.id.syncToggle)
        groupArchivesButton = findViewById(R.id.groupArchivesButton)
        archiveListContainer = findViewById(R.id.archiveListContainer)
        sendArchiveButton = findViewById(R.id.sendArchiveButton)
        generateButton.setOnClickListener { generateReport() }
        syncToggle.setOnCheckedChangeListener { _, checked -> if (checked) prepareSyncArchive() else statusText.text = "Sync preparation disabled." }
        groupArchivesButton.setOnClickListener { groupArchivesLocally() }
        sendArchiveButton.setOnClickListener { sendPreparedArchive() }
    }

    private fun generateReport() {
        if (!hasMediaPermission()) { requestMediaPermission(); return }
        generateButton.isEnabled = false
        statusText.text = "Scanning media storage…"
        Thread {
            val count = scanAndWriteManifest()
            runOnUiThread { generateButton.isEnabled = true; statusText.text = "Report generated locally. $count media files found." }
        }.start()
    }

    private fun prepareSyncArchive() {
        syncToggle.isEnabled = false
        statusText.text = "Preparing local archive…"
        Thread {
            val config = runCatching { SyncConfig.load(this) }.getOrElse { SyncConfig("", 0, false) }
            val result = runCatching { MediaArchiveBuilder.build(this, config) }
                .getOrElse { MediaArchiveBuilder.Result(null, 0, 0, "Archive preparation failed: ${it.message}") }
            android.util.Log.i("MediaDiagnostic", "${result.message}; included=${result.includedFiles}; skipped=${result.skippedFiles}")
            runOnUiThread { syncToggle.isEnabled = true; statusText.text = result.message }
        }.start()
    }

    private fun groupArchivesLocally() {
        groupArchivesButton.isEnabled = false
        statusText.text = "Grouping archives locally…"
        Thread {
            val result = runCatching { GroupedArchiveManager.build(this) }.getOrElse {
                android.util.Log.e("MediaDiagnostic", "Local grouping failed", it)
                emptyList()
            }
            runOnUiThread {
                groupArchivesButton.isEnabled = true
                renderArchiveList(result)
                statusText.text = "Generated ${result.size} folder archives locally."
            }
        }.start()
    }

    private fun renderArchiveList(archives: List<GroupedArchiveManager.ArchiveInfo>) {
        archiveListContainer.removeAllViews()
        val selected = GroupedArchiveManager.loadSelection(this).toMutableSet()
        archives.forEach { info ->
            val checkBox = CheckBox(this)
            checkBox.text = "${info.folderName} — ${info.fileCount} files — ${formatBytes(info.sizeBytes)}"
            checkBox.isChecked = selected.contains(info.file.name)
            checkBox.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            checkBox.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(info.file.name) else selected.remove(info.file.name)
                GroupedArchiveManager.saveSelection(this, selected)
            }
            archiveListContainer.addView(checkBox)
        }
        if (archives.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No local archives generated."
            archiveListContainer.addView(empty)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun sendPreparedArchive() {
        sendArchiveButton.isEnabled = false
        statusText.text = "Uploading archive…"
        Thread {
            val config = runCatching { SyncConfig.load(this) }.getOrElse { SyncConfig("", 0, false) }
            val selected = GroupedArchiveManager.loadSelection(this)
            val archives = File(cacheDir, "media-archives").listFiles()
                ?.filter { it.isFile && it.extension.equals("zip", true) }
                ?.filter { selected.isEmpty() || selected.contains(it.name) }
                ?.sortedBy { it.name }
                ?: emptyList()
            var allSuccess = archives.isNotEmpty()
            for (archive in archives) {
                val result = ArchiveUploader.upload(archive, config.uploadEndpoint)
                android.util.Log.i("MediaDiagnostic", "Archive ${archive.name}: ${result.message}")
                if (!result.success) { allSuccess = false; break }
            }
            runOnUiThread {
                sendArchiveButton.isEnabled = true
                val message = if (allSuccess) "Upload successful" else "Upload failed. Check logs."
                statusText.text = message
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun hasMediaPermission(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestMediaPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        requestPermissions(permissions, permissionRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) { if (hasMediaPermission()) generateReport() else statusText.text = "Media permission was not granted." }
    }

    private fun scanAndWriteManifest(): Int {
        val manifest = JSONArray()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE)
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection ->
            contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val item = JSONObject()
                    if (nameIndex >= 0) item.put("name", cursor.getString(nameIndex))
                    if (pathIndex >= 0) item.put("path", cursor.getString(pathIndex))
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) item.put("size", cursor.getLong(sizeIndex))
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) item.put("timestamp", cursor.getLong(modifiedIndex))
                    if (mimeIndex >= 0) item.put("mimeType", cursor.getString(mimeIndex))
                    manifest.put(item)
                }
            }
        }
        val directory = File(filesDir, ".diagnostic")
        if (!directory.exists()) directory.mkdirs()
        File(directory, "media-manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
        return manifest.length()
    }
}
