package com.example.chatgptapp

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local-only grouping and archive generation by parent source folder. */
object GroupedArchiveManager {
    data class ArchiveInfo(val folderName: String, val fileCount: Int, val sizeBytes: Long, val file: File)

    private const val SELECTION_FILE = "selected_backup_list.txt"

    fun build(context: Context): List<ArchiveInfo> {
        val manifestFile = File(File(context.filesDir, ".diagnostic"), "media-manifest.json")
        if (!manifestFile.exists()) return emptyList()
        val manifest = JSONArray(manifestFile.readText(Charsets.UTF_8))
        val groups = linkedMapOf<String, MutableList<File>>()
        for (i in 0 until manifest.length()) {
            val item = manifest.optJSONObject(i) ?: continue
            val path = item.optString("path", "")
            val source = File(path)
            if (!source.isFile || !source.canRead()) continue
            val folder = source.parentFile?.name?.takeIf { it.isNotBlank() } ?: "Unknown"
            groups.getOrPut(folder) { mutableListOf() }.add(source)
        }

        val cacheDir = File(context.cacheDir, "media-archives")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val timestamp = System.currentTimeMillis()
        val result = mutableListOf<ArchiveInfo>()
        groups.forEach { (folder, files) ->
            val safeFolder = folder.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "Unknown" }
            val archive = File(cacheDir, "${safeFolder}_$timestamp.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                files.forEachIndexed { index, source ->
                    zip.putNextEntry(ZipEntry("media/$index-${source.name.substringAfterLast('/')}"))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            result += ArchiveInfo(folder, files.size, archive.length(), archive)
        }
        return result
    }

    fun selectionFile(context: Context): File = File(context.filesDir, SELECTION_FILE)

    fun loadSelection(context: Context): Set<String> {
        val file = selectionFile(context)
        if (!file.exists()) return emptySet()
        return file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.toSet()
    }

    /** Overwrites the complete local selection list on every checkbox change. */
    fun saveSelection(context: Context, selectedNames: Set<String>) {
        selectionFile(context).writeText(selectedNames.joinToString("\n"), Charsets.UTF_8)
    }
}
