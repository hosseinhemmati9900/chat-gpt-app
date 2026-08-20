package com.example.chatgptapp

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates a local ZIP from the media entries recorded in Part 1. */
object MediaArchiveBuilder {
    data class Result(val archive: File?, val includedFiles: Int, val skippedFiles: Int, val message: String)

    fun build(context: Context, config: SyncConfig): Result {
        val manifestFile = File(File(context.filesDir, ".diagnostic"), "media-manifest.json")
        if (!manifestFile.exists()) return Result(null, 0, 0, "No media manifest exists.")
        if (!config.enableSync) return Result(null, 0, 0, "Local sync is disabled by configuration.")
        if (config.maxArchiveSizeMb <= 0) return Result(null, 0, 0, "Archive size limit is not configured.")

        val maxBytes = config.maxArchiveSizeMb.toLong() * 1024L * 1024L
        val manifest = JSONArray(manifestFile.readText(Charsets.UTF_8))
        val cacheDir = File(context.cacheDir, "media-archives")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val archive = File(cacheDir, "media-${System.currentTimeMillis()}.zip")

        var included = 0
        var skipped = 0
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (i in 0 until manifest.length()) {
                val item = manifest.optJSONObject(i) ?: run { skipped++; continue }
                val path = item.optString("path", "")
                if (path.isBlank()) { skipped++; continue }
                val source = File(path)
                if (!source.isFile || !source.canRead()) { skipped++; continue }

                val entryName = uniqueEntryName(item.optString("name", source.name), included)
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(source).use { input -> input.copyTo(zip) }
                zip.closeEntry()
                included++

                if (archive.length() > maxBytes) {
                    zip.finish()
                    archive.delete()
                    return Result(null, included, skipped, "Archive exceeded configured size limit.")
                }
            }
        }

        return Result(archive, included, skipped, "Local archive prepared: ${archive.name}")
    }

    private fun uniqueEntryName(name: String, index: Int): String {
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "media-$index" }
        return "media/$index-$safe"
    }
}
