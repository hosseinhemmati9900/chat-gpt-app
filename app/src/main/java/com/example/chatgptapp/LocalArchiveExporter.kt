package com.example.chatgptapp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

object LocalArchiveExporter {
    data class Result(val copied: Int, val total: Int, val failed: Int)

    fun export(
        context: Context,
        destinationTree: Uri,
        archives: List<File>,
        onProgress: (completed: Int, total: Int) -> Unit
    ): Result {
        var copied = 0
        var failed = 0
        archives.forEachIndexed { index, archive ->
            runCatching {
                val target = DocumentsContract.createDocument(
                    context.contentResolver,
                    destinationTree,
                    "application/zip",
                    archive.name
                ) ?: error("Unable to create ${archive.name}")
                context.contentResolver.openOutputStream(target)?.use { output ->
                    archive.inputStream().buffered().use { input -> input.copyTo(output) }
                } ?: error("Unable to open destination for ${archive.name}")
                copied++
            }.onFailure {
                failed++
                android.util.Log.e("AssetArchive", "Export failed: ${archive.name}", it)
            }
            onProgress(index + 1, archives.size)
        }
        return Result(copied, archives.size, failed)
    }
}
