package com.lunara.music.service.download

import android.content.Context
import android.os.Environment
import android.util.Log
import com.lunara.music.data.models.Song
import com.lunara.music.database.LunaraDatabase
import com.lunara.music.service.innertube.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val songId: String,
    val progress: Float, // 0.0 to 1.0
    val isDone: Boolean = false,
    val error: String? = null
)

class DownloadManager(private val context: Context) {
    private val TAG = "DownloadManager"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val db = LunaraDatabase.getDatabase(context)
    private val _downloadStates = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadProgress>> = _downloadStates.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Boolean>()

    private fun getDownloadsDir(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "music_downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun downloadSong(song: Song) = withContext(Dispatchers.IO) {
        if (activeJobs[song.id] == true) return@withContext
        activeJobs[song.id] = true

        updateProgress(song.id, 0.05f)

        try {
            // First ensure song is in database
            db.songDao().insertOrUpdateSong(song.toEntity())

            // Resolve stream URL
            updateProgress(song.id, 0.15f)
            val streamUrl = song.streamUrl ?: StreamResolver.resolveStreamUrl(song.id)

            if (streamUrl.isNullOrBlank()) {
                updateProgress(song.id, 0f, error = "Could not resolve audio stream")
                activeJobs.remove(song.id)
                return@withContext
            }

            val req = Request.Builder()
                .url(streamUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val outputFile = File(getDownloadsDir(), "${song.id}.m4a")

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    updateProgress(song.id, 0f, error = "HTTP ${resp.code}")
                    activeJobs.remove(song.id)
                    return@withContext
                }

                val body = resp.body ?: throw IllegalStateException("Empty body")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val p = 0.2f + (downloadedBytes.toFloat() / totalBytes) * 0.8f
                                updateProgress(song.id, p)
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Save to DB
            db.songDao().updateDownloadStatus(
                id = song.id,
                isDownloaded = true,
                filePath = outputFile.absolutePath,
                fileSize = outputFile.length()
            )

            updateProgress(song.id, 1.0f, isDone = true)
            Log.d(TAG, "Song ${song.title} downloaded successfully (${outputFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download song ${song.id}: ${e.message}", e)
            updateProgress(song.id, 0f, error = e.localizedMessage)
        } finally {
            activeJobs.remove(song.id)
        }
    }

    suspend fun deleteDownload(songId: String) = withContext(Dispatchers.IO) {
        val file = File(getDownloadsDir(), "$songId.m4a")
        if (file.exists()) {
            file.delete()
        }
        db.songDao().removeDownload(songId)
        val current = _downloadStates.value.toMutableMap()
        current.remove(songId)
        _downloadStates.value = current
    }

    suspend fun getTotalDownloadedSize(): Long = withContext(Dispatchers.IO) {
        var total = 0L
        getDownloadsDir().listFiles()?.forEach { file ->
            if (file.isFile) total += file.length()
        }
        total
    }

    suspend fun clearAllDownloads() = withContext(Dispatchers.IO) {
        getDownloadsDir().listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        val downloaded = db.songDao().getLikedSongsList() // cleanup downloaded flags
        downloaded.forEach {
            if (it.isDownloaded) db.songDao().removeDownload(it.id)
        }
        _downloadStates.value = emptyMap()
    }

    private fun updateProgress(songId: String, progress: Float, isDone: Boolean = false, error: String? = null) {
        val current = _downloadStates.value.toMutableMap()
        current[songId] = DownloadProgress(songId, progress, isDone, error)
        _downloadStates.value = current
    }
}
