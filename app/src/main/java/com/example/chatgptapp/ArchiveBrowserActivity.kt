package com.example.chatgptapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.util.Date

class ArchiveBrowserActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshArchiveList()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "Archive Browser"
            textSize = 28f
        }, LinearLayout.LayoutParams(-1, -2))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listContainer) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        handler.post(refreshTask)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshTask)
        super.onStop()
    }

    private fun refreshArchiveList() {
        val selected = GroupedArchiveManager.loadSelection(this).toMutableSet()
        val archives = File(cacheDir, "media-archives").listFiles()
            ?.filter { it.isFile && it.extension.equals("zip", true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        listContainer.removeAllViews()
        if (archives.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "No ZIP archives in cache."
                setPadding(0, 32, 0, 32)
            })
            return
        }

        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        archives.forEach { archive ->
            val row = CheckBox(this).apply {
                text = "${archive.name}\n${formatSize(archive.length())}  •  ${dateFormat.format(Date(archive.lastModified()))}"
                gravity = Gravity.CENTER_VERTICAL
                isChecked = selected.contains(archive.name)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(archive.name) else selected.remove(archive.name)
                    GroupedArchiveManager.saveSelection(this@ArchiveBrowserActivity, selected)
                }
            }
            listContainer.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
