package com.example.chatgptapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AssetArchiveTheme { AssetArchiveApp(this) } }
    }
}

private data class UiArchive(
    val file: File,
    val folderName: String,
    val size: Long,
    val fileCount: Int
)

@androidx.compose.runtime.Composable
private fun AssetArchiveApp(activity: Activity) {
    val context = activity.applicationContext
    var vpnAvailable by remember { mutableStateOf(hasVpnTransport(context)) }
    var status by remember { mutableStateOf("Ready") }
    var queueStatus by remember { mutableStateOf("Queue: RESUMED") }
    var assetCount by remember { mutableIntStateOf(0) }
    var archives by remember { mutableStateOf(emptyList<UiArchive>()) }
    var selected by remember { mutableStateOf(GroupedArchiveManager.loadSelection(context)) }
    var scanning by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportCompleted by remember { mutableIntStateOf(0) }
    var exportTotal by remember { mutableIntStateOf(0) }
    var showExportPreview by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        status = if (grants.values.any { it }) "Media permission granted. Tap Scan Media to begin." else "Media permission was not granted."
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val files = archives.map { it.file }.filter { it.isFile }
        if (files.isEmpty()) {
            Toast.makeText(activity, "No archives available to export", Toast.LENGTH_SHORT).show()
        } else {
            exporting = true
            exportCompleted = 0
            exportTotal = files.size
            Thread {
                val result = LocalArchiveExporter.export(activity, uri, files) { done, total ->
                    activity.runOnUiThread {
                        exportCompleted = done
                        exportTotal = total
                    }
                }
                activity.runOnUiThread {
                    exporting = false
                    if (result.failed == 0) {
                        Toast.makeText(activity, "All archives exported successfully", Toast.LENGTH_LONG).show()
                        status = "Export complete: ${result.copied} archives copied."
                    } else {
                        Toast.makeText(activity, "Export completed with ${result.failed} errors", Toast.LENGTH_LONG).show()
                        status = "Export complete: ${result.copied}/${result.total} archives copied."
                    }
                }
            }.start()
        }
    }

    val queueMonitor = remember { QueueStatusMonitor(context) { value -> queueStatus = value } }
    DisposableEffect(queueMonitor) {
        queueMonitor.start()
        onDispose { queueMonitor.stop() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            archives = loadUiArchives(context)
            selected = GroupedArchiveManager.loadSelection(context)
            kotlinx.coroutines.delay(10_000L)
        }
    }

    fun refreshArchives() {
        archives = loadUiArchives(context)
        selected = GroupedArchiveManager.loadSelection(context)
    }

    val requestPermissions = {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Asset Archive") }) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Internal diagnostic and local archiving", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text(queueStatus, style = MaterialTheme.typography.bodyMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (hasMediaPermission(activity)) {
                                scanning = true
                                status = "Scanning MediaStore…"
                                Thread {
                                    val result = runCatching { AssetScanner.scan(activity) }
                                    activity.runOnUiThread {
                                        scanning = false
                                        result.onSuccess {
                                            assetCount = it.size
                                            status = "Scan complete: ${it.size} media assets recorded locally."
                                        }.onFailure {
                                            status = "Scan failed: ${it.message ?: "Unknown error"}"
                                        }
                                    }
                                }.start()
                            } else {
                                requestPermissions()
                            }
                        },
                        enabled = !scanning && !archiving && !exporting,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (scanning) "Scanning…" else "Scan Media") }
                    OutlinedButton(
                        onClick = requestPermissions,
                        enabled = !scanning && !archiving && !exporting,
                        modifier = Modifier.weight(1f)
                    ) { Text("Permissions") }
                }

                Button(
                    onClick = {
                        archiving = true
                        status = "Building local ZIP reports…"
                        Thread {
                            val result = runCatching { GroupedArchiveManager.build(activity) }
                            activity.runOnUiThread {
                                archiving = false
                                result.onSuccess {
                                    refreshArchives()
                                    status = "Created ${it.size} local ZIP reports."
                                }.onFailure {
                                    status = "Archive build failed: ${it.message ?: "Unknown error"}"
                                }
                            }
                        }.start()
                    },
                    enabled = !scanning && !archiving && !exporting,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (archiving) "Building reports…" else "Build Folder Reports") }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Manifest", style = MaterialTheme.typography.titleSmall)
                        Text("$assetCount media assets scanned", style = MaterialTheme.typography.bodySmall)
                        Text(AssetScanner.manifestFile(context).relativeTo(context.filesDir).path, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Archive Reports", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${archives.size} total • ${selected.size} selected", style = MaterialTheme.typography.bodySmall)
                }

                if (archives.isEmpty()) {
                    Text("No ZIP reports in cache.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(archives, key = { it.file.absolutePath }) { archive ->
                            ArchiveRow(
                                archive = archive,
                                checked = selected.contains(archive.file.name),
                                onCheckedChange = { checked ->
                                    val next = selected.toMutableSet()
                                    if (checked) next.add(archive.file.name) else next.remove(archive.file.name)
                                    selected = next
                                    runCatching { GroupedArchiveManager.saveSelection(context, next) }
                                        .onFailure { status = "Selection save failed: ${it.message ?: "Unknown error"}" }
                                }
                            )
                        }
                    }
                }

                if (exporting) {
                    LinearProgressIndicator(
                        progress = { if (exportTotal == 0) 0f else exportCompleted.toFloat() / exportTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Exporting $exportCompleted of $exportTotal…", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = { showExportPreview = true },
                    enabled = archives.isNotEmpty() && !scanning && !archiving && !exporting,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export All Archives") }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (!vpnAvailable) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Secure tunnel required. Please enable VPN and retry.",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { vpnAvailable = hasVpnTransport(context) }) { Text("Check Again") }
                }
            }
        }
    }

    if (showExportPreview) {
        AlertDialog(
            onDismissRequest = { showExportPreview = false },
            title = { Text("Export All Archives") },
            text = {
                Column {
                    Text("${archives.size} ZIP reports will be copied. Originals remain in cache.")
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.height(260.dp)) {
                        items(archives) { archive ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(archive.file.name, Modifier.weight(1f))
                                Text(formatBytes(archive.size))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showExportPreview = false
                    exportLauncher.launch(null)
                }) { Text("Choose Folder") }
            },
            dismissButton = { OutlinedButton(onClick = { showExportPreview = false }) { Text("Cancel") } }
        )
    }
}

@androidx.compose.runtime.Composable
private fun ArchiveRow(archive: UiArchive, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(Modifier.weight(1f)) {
                Text(archive.file.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${archive.folderName} • ${formatBytes(archive.size)} • ${DateFormat.getDateTimeInstance().format(Date(archive.file.lastModified()))}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun loadUiArchives(context: Context): List<UiArchive> = GroupedArchiveManager.listArchives(context).map {
    UiArchive(it.file, it.folderName, it.sizeBytes, max(0, it.fileCount))
}

private fun hasMediaPermission(activity: Activity): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= 33) {
        activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else {
        activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun hasVpnTransport(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = connectivity.activeNetwork ?: return false
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
