package com.example.chatgptapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var generateButton: Button

    private val permissionRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        generateButton = findViewById(R.id.generateReportButton)
        generateButton.setOnClickListener { generateReport() }
    }

    private fun generateReport() {
        if (!hasMediaPermission()) {
            requestMediaPermission()
            return
        }
        generateButton.isEnabled = false
        statusText.text = "Scanning media storage…"
        Thread {
            val count = scanAndWriteManifest()
            runOnUiThread {
                generateButton.isEnabled = true
                statusText.text = "Report generated locally. $count media files found."
            }
        }.start()
    }

    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            val images = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val videos = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            images || videos
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, permissionRequestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (hasMediaPermission()) {
                generateReport()
            } else {
                statusText.text = "Media permission was not granted."
            }
        }
    }

    private fun scanAndWriteManifest(): Int {
        val manifest = JSONArray()
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )

        for (collection in collections) {
            contentResolver.query(
                collection,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
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
        val output = File(directory, "media-manifest.json")
        output.writeText(manifest.toString(2), Charsets.UTF_8)
        return manifest.length()
    }
}
