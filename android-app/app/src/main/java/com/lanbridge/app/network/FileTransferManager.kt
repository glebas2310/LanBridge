package com.lanbridge.app.network

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

class FileTransferManager(private val context: Context) {

    companion object {
        private const val TAG = "FileTransferManager"
        const val CHUNK_SIZE = 65536 // 64 KB
    }

    /**
     * Uploads a file stream directly from Android ContentResolver to Windows PC.
     */
    suspend fun uploadFile(
        fileUri: Uri,
        fileId: String,
        sessionToken: String,
        serverIp: String,
        serverPort: Int,
        certFingerprint: String,
        mimeType: String,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val fileSize = contentResolver.openFileDescriptor(fileUri, "r")?.use { it.statSize } ?: -1L

            val client = TlsCertificatePinner.createPinnedOkHttpClient(certFingerprint, timeoutSeconds = 60L)
            val digest = MessageDigest.getInstance("SHA-256")

            val requestBody = object : RequestBody() {
                override fun contentType(): MediaType? = (mimeType.ifEmpty { "application/octet-stream" }).toMediaTypeOrNull()

                override fun contentLength(): Long = fileSize

                override fun writeTo(sink: BufferedSink) {
                    val inputStream: InputStream = contentResolver.openInputStream(fileUri)
                        ?: throw IllegalStateException("Cannot open input stream for $fileUri")

                    inputStream.use { stream ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var bytesSent = 0L
                        var bytesRead: Int

                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            sink.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            bytesSent += bytesRead
                            onProgress(bytesSent, fileSize)
                        }
                    }
                }
            }

            val url = "https://$serverIp:$serverPort/api/files/upload?file_id=$fileId&token=$sessionToken"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            Log.d(TAG, "Uploading file to $url (size: $fileSize bytes)...")
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Upload failed with HTTP ${response.code}: ${response.message}"))
            }

            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Upload completed successfully. SHA-256: $computedSha256")
            Result.success(computedSha256)
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads a file stream directly from Windows PC to the Android Downloads folder.
     */
    suspend fun downloadFile(
        fileId: String,
        fileName: String,
        expectedSha256: String,
        expectedSize: Long,
        sessionToken: String,
        serverIp: String,
        serverPort: Int,
        certFingerprint: String,
        onProgress: (bytesReceived: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val client = TlsCertificatePinner.createPinnedOkHttpClient(certFingerprint, timeoutSeconds = 60L)
            val url = "https://$serverIp:$serverPort/api/files/download?file_id=$fileId&token=$sessionToken"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            Log.d(TAG, "Downloading file from $url...")
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed with HTTP ${response.code}: ${response.message}"))
            }

            val responseBody = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val totalBytes = if (responseBody.contentLength() > 0) responseBody.contentLength() else expectedSize

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir

            var targetFile = File(downloadsDir, fileName)
            var counter = 1
            val baseName = fileName.substringBeforeLast(".")
            val extension = if (fileName.contains(".")) "." + fileName.substringAfterLast(".") else ""

            while (targetFile.exists()) {
                targetFile = File(downloadsDir, "$baseName ($counter)$extension")
                counter++
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var bytesReceived = 0L

            FileOutputStream(targetFile).use { fileOut ->
                responseBody.byteStream().use { streamIn ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var bytesRead: Int

                    while (streamIn.read(buffer).also { bytesRead = it } != -1) {
                        fileOut.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        bytesReceived += bytesRead
                        onProgress(bytesReceived, totalBytes)
                    }
                }
            }

            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Download finished. Expected SHA-256: $expectedSha256, Actual: $actualSha256")

            if (expectedSha256.isNotEmpty() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                targetFile.delete()
                return@withContext Result.failure(
                    Exception("Checksum mismatch! Corrupted transfer. Expected: $expectedSha256, Actual: $actualSha256")
                )
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading file: ${e.message}", e)
            Result.failure(e)
        }
    }
}
