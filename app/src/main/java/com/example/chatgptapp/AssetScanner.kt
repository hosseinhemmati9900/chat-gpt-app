package com.example.chatgptapp

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** On-demand MediaStore scanner. All output stays in internal app storage. */
object AssetScanner {
    data class Asset(
        val name: String,
        val path: String,
        val size: Long,
        val mimeType: String,
        val timestamp: Long,
        val folder: String,
        val uri: String
    )

    private val collections = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )

    private const val MANIFEST_DIR = ".asset_manifest"
    private const val MANIFEST_NAME = "asset-manifest.json"

    fun manifestFile(context: Context): File = File(File(context.filesDir, MANIFEST_DIR), MANIFEST_NAME)

    fun scan(context: Context): List<Asset> {
        val assets = mutableListOf<Asset>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH
        )

        collections.forEach { collection ->
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val relativeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)

                while (cursor.moveToNext()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "Unnamed" else "Unnamed"
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    val timestamp = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) ?: "application/octet-stream" else "application/octet-stream"
                    val path = if (pathIndex >= 0) cursor.getString(pathIndex) ?: "" else ""
                    val relative = if (relativeIndex >= 0) cursor.getString(relativeIndex) ?: "" else ""
                    val folder = sourceFolder(path, relative)
                    val id = if (idIndex >= 0) cursor.getLong(idIndex) else -1L
                    if (id >= 0) {
                        val uri = Uri.withAppendedPath(collection, id).toString()
                        assets += Asset(name, path, size, mime, timestamp, folder, uri)
                    }
                }
            }
        }

        writeManifest(context, assets)
        return assets
    }

    fun readManifest(context: Context): List<Asset> {
        val file = manifestFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        Asset(
                            item.optString("filename"),
                            item.optString("path"),
                            item.optLong("size"),
                            item.optString("mimeType"),
                            item.optLong("timestamp"),
                            item.optString("sourceFolder", "Unknown"),
                            item.optString("contentUri")
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeManifest(context: Context, assets: List<Asset>) {
        val directory = File(context.filesDir, MANIFEST_DIR)
        if (!directory.exists() && !directory.mkdirs()) error("Unable to create manifest directory")
        val array = JSONArray()
        assets.forEach { asset ->
            array.put(JSONObject().apply {
                put("filename", asset.name)
                put("path", asset.path)
                put("size", asset.size)
                put("mimeType", asset.mimeType)
                put("timestamp", asset.timestamp)
                put("sourceFolder", asset.folder)
                put("contentUri", asset.uri)
            })
        }
        manifestFile(context).writeText(array.toString(2), Charsets.UTF_8)
    }

    private fun sourceFolder(path: String, relativePath: String): String {
        if (relativePath.isNotBlank()) {
            val clean = relativePath.trimEnd('/').trimEnd('\\')
            clean.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }?.let { return it }
        }
        val parent = File(path).parentFile?.name
        return parent?.takeIf { it.isNotBlank() } ?: "Unknown"
    }
}
