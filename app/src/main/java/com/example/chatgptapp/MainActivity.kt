package com.example.chatgptapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var queueStatusText: TextView
    private lateinit var generateButton: Button
    private lateinit var syncToggle: ToggleButton
    private lateinit var groupArchivesButton: Button
    private lateinit var archiveListContainer: LinearLayout
    private lateinit var sendArchiveButton: Button
    private lateinit var exportAllButton: Button
    private lateinit var exportProgress: ProgressBar
    private lateinit var queueMonitor: QueueStatusMonitor
    private val permissionRequestCode = 1001
    private val exportFolderRequestCode = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        queueStatusText = findViewById(R.id.queueStatusText)
        generateButton = findViewById(R.id.generateReportButton)
        syncToggle = findViewById(R.id.syncToggle)
        groupArchivesButton = findViewById(R.id.groupArchivesButton)
        archiveListContainer = findViewById(R.id.archiveListContainer)
        sendArchiveButton = findViewById(R.id.sendArchiveButton)
        exportAllButton = findViewById(R.id.exportAllArchivesButton)
        exportProgress = findViewById(R.id.exportProgress)
        queueMonitor = QueueStatusMonitor(this) { queueStatusText.text = it }
        generateButton.setOnClickListener { generateReport() }
        syncToggle.setOnCheckedChangeListener { _, checked -> if (checked) prepareSyncArchive() else statusText.text = "Sync preparation disabled." }
        groupArchivesButton.setOnClickListener { groupArchivesLocally() }
        sendArchiveButton.setOnClickListener { sendPreparedArchive() }
        exportAllButton.setOnClickListener { previewAndChooseExportFolder() }
    }

    override fun onStart() { super.onStart(); queueMonitor.start(); enforceVpnPrecondition() }
    override fun onStop() { queueMonitor.stop(); super.onStop() }

    private fun hasVpnTransport(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun enforceVpnPrecondition() {
        if (hasVpnTransport()) return
        val overlay = AlertDialog.Builder(this)
            .setMessage("Secure tunnel required. Please enable VPN and retry.")
            .setPositiveButton("Check Again") { _, _ -> enforceVpnPrecondition() }
            .setCancelable(false)
            .create()
        overlay.setCanceledOnTouchOutside(false)
        overlay.setOnShowListener {
            overlay.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (hasVpnTransport()) overlay.dismiss()
                else overlay.setMessage("Secure tunnel required. Please enable VPN and retry.")
            }
        }
        overlay.show()
    }

    private fun generateReport() {
        if (!hasMediaPermission()) { requestMediaPermission(); return }
        generateButton.isEnabled = false; statusText.text = "Scanning media storage…"
        Thread { val count = scanAndWriteManifest(); runOnUiThread { generateButton.isEnabled = true; statusText.text = "Report generated locally. $count media files found." } }.start()
    }

    private fun prepareSyncArchive() {
        syncToggle.isEnabled = false; statusText.text = "Preparing local archive…"
        Thread {
            val config = runCatching { SyncConfig.load(this) }.getOrElse { SyncConfig("", 0, false) }
            val result = runCatching { MediaArchiveBuilder.build(this, config) }.getOrElse { MediaArchiveBuilder.Result(null, 0, 0, "Archive preparation failed: ${it.message}") }
            android.util.Log.i("MediaDiagnostic", "${result.message}; included=${result.includedFiles}; skipped=${result.skippedFiles}")
            runOnUiThread { syncToggle.isEnabled = true; statusText.text = result.message }
        }.start()
    }

    private fun groupArchivesLocally() {
        groupArchivesButton.isEnabled = false; statusText.text = "Grouping archives locally…"
        Thread { val result = runCatching { GroupedArchiveManager.build(this) }.getOrElse { android.util.Log.e("MediaDiagnostic", "Local grouping failed", it); emptyList() }; runOnUiThread { groupArchivesButton.isEnabled = true; renderArchiveList(result); statusText.text = "Generated ${result.size} folder archives locally." } }.start()
    }

    private fun renderArchiveList(archives: List<GroupedArchiveManager.ArchiveInfo>) {
        archiveListContainer.removeAllViews(); val selected = GroupedArchiveManager.loadSelection(this).toMutableSet()
        archives.forEach { info ->
            val checkBox = CheckBox(this); checkBox.text = "${info.folderName} — ${info.fileCount} files — ${formatBytes(info.sizeBytes)}"; checkBox.isChecked = selected.contains(info.file.name); checkBox.layoutParams = ViewGroup.LayoutParams(-1, -2)
            checkBox.setOnCheckedChangeListener { _, checked -> if (checked) selected.add(info.file.name) else selected.remove(info.file.name); GroupedArchiveManager.saveSelection(this, selected) }; archiveListContainer.addView(checkBox)
        }
        if (archives.isEmpty()) archiveListContainer.addView(TextView(this).apply { text = "No local archives generated." })
    }

    private fun formatBytes(bytes: Long): String = when { bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0)); bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0); else -> "$bytes B" }

    private fun sendPreparedArchive() {
        sendArchiveButton.isEnabled = false; statusText.text = "Uploading archive…"
        Thread {
            val config = runCatching { SyncConfig.load(this) }.getOrElse { SyncConfig("", 0, false) }; val selected = GroupedArchiveManager.loadSelection(this)
            val archives = File(cacheDir, "media-archives").listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }?.filter { selected.isEmpty() || selected.contains(it.name) }?.sortedBy { it.name } ?: emptyList(); var allSuccess = archives.isNotEmpty()
            for (archive in archives) { val result = ArchiveUploader.upload(archive, config.uploadEndpoint); android.util.Log.i("MediaDiagnostic", "Archive ${archive.name}: ${result.message}"); if (!result.success) { allSuccess = false; break } }
            runOnUiThread { sendArchiveButton.isEnabled = true; val message = if (allSuccess) "Upload successful" else "Upload failed. Check logs."; statusText.text = message; Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun previewAndChooseExportFolder() {
        val archives = File(cacheDir, "media-archives").listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }?.sortedBy { it.name } ?: emptyList(); if (archives.isEmpty()) { Toast.makeText(this, "No archives available to export", Toast.LENGTH_SHORT).show(); return }
        val preview = TextView(this).apply { text = buildString { append("${archives.size} archives will be copied:\n\n"); archives.forEach { append("${it.name} — ${formatBytes(it.length())}\n") }; append("\nOriginal cache files will remain unchanged.") }; setPadding(48, 32, 48, 16) }
        AlertDialog.Builder(this).setTitle("Export All Archives").setView(preview).setNegativeButton("Cancel", null).setPositiveButton("Choose Folder") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, exportFolderRequestCode) }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == exportFolderRequestCode && resultCode == RESULT_OK) { val treeUri = data?.data ?: return; runCatching { contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }; exportArchivesTo(treeUri) } }
    private fun exportArchivesTo(treeUri: Uri) {
        exportAllButton.isEnabled = false; exportProgress.visibility = ProgressBar.VISIBLE
        Thread { val archives = File(cacheDir, "media-archives").listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }?.sortedBy { it.name } ?: emptyList(); var copied = 0; var failed = false
            archives.forEachIndexed { index, archive -> runCatching { val targetUri = DocumentsContract.createDocument(contentResolver, treeUri, "application/zip", archive.name) ?: error("Unable to create destination file"); contentResolver.openOutputStream(targetUri)?.use { output -> archive.inputStream().use { input -> input.copyTo(output) } } ?: error("Unable to open destination"); copied++ }.onFailure { failed = true; android.util.Log.e("MediaDiagnostic", "Export failed for ${archive.name}", it) }; runOnUiThread { exportProgress.progress = index + 1; exportProgress.max = archives.size } }
            val folderName = queryDisplayName(treeUri) ?: "selected folder"; runOnUiThread { exportAllButton.isEnabled = true; exportProgress.visibility = ProgressBar.GONE; if (!failed && copied == archives.size) { Toast.makeText(this, "All archives exported successfully to $folderName", Toast.LENGTH_LONG).show(); statusText.text = "Export complete: $copied archives copied." } else { Toast.makeText(this, "Export completed with errors", Toast.LENGTH_LONG).show(); statusText.text = "Export completed: $copied/${archives.size} archives copied." } }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? { contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }; return null }
    private fun hasMediaPermission(): Boolean = if (Build.VERSION.SDK_INT >= 33) checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private fun requestMediaPermission() { requestPermissions(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), permissionRequestCode) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == permissionRequestCode) { if (hasMediaPermission()) generateReport() else statusText.text = "Media permission was not granted." } }

    private fun scanAndWriteManifest(): Int {
        val manifest = JSONArray(); val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE)
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { collection -> contentResolver.query(collection, projection, null, null, null)?.use { cursor -> val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME); val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA); val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE); val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED); val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE); while (cursor.moveToNext()) { val item = JSONObject(); if (nameIndex >= 0) item.put("name", cursor.getString(nameIndex)); if (pathIndex >= 0) item.put("path", cursor.getString(pathIndex)); if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) item.put("size", cursor.getLong(sizeIndex)); if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) item.put("timestamp", cursor.getLong(modifiedIndex)); if (mimeIndex >= 0) item.put("mimeType", cursor.getString(mimeIndex)); manifest.put(item) } } }
        val directory = File(filesDir, ".diagnostic"); if (!directory.exists()) directory.mkdirs(); File(directory, "media-manifest.json").writeText(manifest.toString(2), Charsets.UTF_8); return manifest.length()
    }
}
