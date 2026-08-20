package com.example.chatgptapp

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ArchiveUploader {
    data class Result(val success: Boolean, val message: String)

    fun upload(archive: File, endpoint: String): Result {
        if (!archive.isFile) return Result(false, "Archive does not exist")
        val url = runCatching { URL(endpoint) }.getOrElse { return Result(false, "Invalid endpoint") }
        if (url.protocol != "https") return Result(false, "Endpoint must use HTTPS")

        var lastError = "Upload failed"
        repeat(3) { attempt ->
            try {
                val boundary = "----ChatGptApp${UUID.randomUUID()}"
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    doOutput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }
                connection.outputStream.use { output ->
                    output.write("--$boundary\r\n".toByteArray())
                    output.write("Content-Disposition: form-data; name=\"file\"; filename=\"${archive.name}\"\r\n".toByteArray())
                    output.write("Content-Type: application/zip\r\n\r\n".toByteArray())
                    archive.inputStream().use { it.copyTo(output) }
                    output.write("\r\n--$boundary--\r\n".toByteArray())
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    connection.disconnect()
                    return Result(true, "Upload successful")
                }
                lastError = "HTTP $code"
                connection.disconnect()
                return Result(false, "Upload failed: $lastError")
            } catch (e: Exception) {
                lastError = "attempt=${attempt + 1}: ${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.w("MediaDiagnostic", "Upload network failure; $lastError")
            }
        }
        return Result(false, "Upload failed after 3 attempts: $lastError")
    }
}
