package com.example.chatgptapp

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local-only archive builder and selection persistence. */
object GroupedArchiveManager {
    data class ArchiveInfo(
        val folderName: String,
        val fileCount: Int,
        val sizeBytes: Long,
        val file: File
    )

    private const val CACHE_DIR = "asset-archives"
    private const val SELECTION_FILE = "selected_reports.txt"

    fun archiveDirectory(context: Context): File = File(context.cacheDir, CACHE_DIR)

    fun build(context: Context): List<ArchiveInfo> {
        val assets = AssetScanner.readManifest(context)
        if (assets.isEmpty()) return emptyList()
        val directory = archiveDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) error("Unable to create archive cache directory")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return assets.groupBy { it.folder.ifBlank { "Unknown" } }.mapNotNull { (folder, group) ->
            val safeFolder = folder.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "Unknown" }
            val archive = File(directory, "${safeFolder}_$timestamp.zip")
            runCatching {
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    group.forEachIndexed { index, asset ->
                        val entryName = uniqueEntryName(asset.name, index)
                        zip.putNextEntry(ZipEntry(entryName))
                        openAsset(context, asset).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                ArchiveInfo(folder, group.size, archive.length(), archive)
            }.onFailure {
                archive.delete()
                android.util.Log.e("AssetArchive", "Failed to archive folder $folder", it)
            }.getOrNull()
        }
    }

    fun listArchives(context: Context): List<ArchiveInfo> = archiveDirectory(context).listFiles()
        ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() }
        ?.map { file ->
            val folder = file.name.substringBeforeLast('_').replace('_', ' ')
            ArchiveInfo(folder, -1, file.length(), file)
        }
        ?: emptyList()

    fun selectionFile(context: Context): File = File(context.filesDir, SELECTION_FILE)

    fun loadSelection(context: Context): Set<String> = runCatching {
        val file = selectionFile(context)
        if (!file.exists()) emptySet() else file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrElse { emptySet() }

    fun saveSelection(context: Context, selectedNames: Set<String>) {
        val file = selectionFile(context)
        val ordered = selectedNames.filter { it.isNotBlank() }.sorted()
        file.writeText(if (ordered.isEmpty()) "" else ordered.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun openAsset(context: Context, asset: AssetScanner.Asset) = when {
        asset.uri.isNotBlank() -> context.contentResolver.openInputStream(Uri.parse(asset.uri))
            ?: error("Unable to open media URI: ${asset.uri}")
        asset.path.isNotBlank() -> File(asset.path).inputStream()
        else -> error("Asset has no readable source: ${asset.name}")
    }

    private fun uniqueEntryName(name: String, index: Int): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "asset-$index" }
        return "media/${index.toString().padStart(5, '0')}-$clean"
    }
}
