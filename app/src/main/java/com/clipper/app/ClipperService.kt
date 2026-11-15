package com.clipper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ClipperService : Service() {

    companion object {
        const val CHANNEL_ID = "video_clipper_progress"
        const val NOTIF_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = ClipJobBus.pendingConfig
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotif = buildNotification("Menyiapkan pemotongan video...", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, initialNotif)
        }

        serviceScope.launch {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoClipper::RenderLock")
            wakeLock.acquire(30 * 60 * 1000L)
            try {
                processAllSegments(config)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun processAllSegments(config: ClipJobConfig) {
        val timestamp = System.currentTimeMillis()
        val total = config.segments.size

        try {
            updateProgress("Memeriksa sumber & subtitle...", 5)

            val rawSubtitleContent: String? = when {
                !config.manualSrtContent.isNullOrBlank() -> config.manualSrtContent
                config.useAutoYoutubeSub && !config.youtubePageUrl.isNullOrBlank() -> {
                    updateProgress("Mengambil auto-subtitle YouTube...", 10)
                    YoutubeExtractor.fetchYoutubeSubtitles(this, config.youtubePageUrl, cacheDir)
                }
                else -> null
            }

            val cachedLocalInput: File? = if (config.localOrDirectUri != null && !config.isDirectStream) {
                copyUriToTempFile(config.localOrDirectUri, "src_local_$timestamp.mp4")
            } else {
                null
            }

            for ((idx, seg) in config.segments.withIndex()) {
                val part = idx + 1
                val basePct = (idx * 100) / total
                val stepSpan = 100 / total

                val tempSrtFile = File(cacheDir, "sub_${timestamp}_p$part.srt")
                val hasClipSrt = if (!rawSubtitleContent.isNullOrBlank()) {
                    SubtitleUtils.sliceSrtForSegment(
                        rawSubtitle = rawSubtitleContent,
                        segmentStartMs = seg.startMs,
                        segmentEndMs = seg.endMs,
                        outputSrtFile = tempSrtFile
                    )
                } else {
                    false
                }

                val finalHookText = buildString {
                    append(config.hookTitle.trim())
                    if (config.autoPartNumber && total > 1) {
                        if (isNotEmpty()) append(" — ")
                        append("Part ${seg.partNumber}")
                    }
                }

                val finalOutputFile = File(cacheDir, "out_${timestamp}_part$part.mp4")
                if (finalOutputFile.exists()) finalOutputFile.delete()

                if (!config.youtubePageUrl.isNullOrBlank()) {
                    val rawYtFile = File(cacheDir, "yt_raw_${timestamp}_p$part.mp4")
                    YoutubeExtractor.downloadSegment(
                        context = this,
                        pageUrl = config.youtubePageUrl,
                        startMs = seg.startMs,
                        endMs = seg.endMs,
                        resolution = config.resolution,
                        outputFile = rawYtFile
                    ) { dlPct ->
                        val overall = basePct + (dlPct * stepSpan / 200)
                        updateProgress("Mengunduh klip $part/$total ($dlPct%)...", overall)
                    }

                    updateProgress("Merender layout & teks klip $part/$total...", basePct + (stepSpan / 2))
                    MediaProcessor.renderClip(
                        context = this,
                        inputPath = rawYtFile.absolutePath,
                        outputPath = finalOutputFile.absolutePath,
                        trimStartMs = null,
                        trimDurationMs = null,
                        layout = config.layout,
                        hookText = finalHookText,
                        hookStyle = config.hookStyle,
                        watermarkText = config.watermarkText,
                        watermarkPos = config.watermarkPos,
                        srtFile = if (hasClipSrt) tempSrtFile else null
                    )
                    if (rawYtFile.exists()) rawYtFile.delete()
                } else {
                    val inputPath = cachedLocalInput?.absolutePath
                        ?: config.localOrDirectUri?.toString()
                        ?: throw IllegalStateException("Sumber video tidak valid.")

                    updateProgress("Merender klip $part/$total...", basePct + (stepSpan / 2))
                    MediaProcessor.renderClip(
                        context = this,
                        inputPath = inputPath,
                        outputPath = finalOutputFile.absolutePath,
                        trimStartMs = seg.startMs,
                        trimDurationMs = (seg.endMs - seg.startMs),
                        layout = config.layout,
                        hookText = finalHookText,
                        hookStyle = config.hookStyle,
                        watermarkText = config.watermarkText,
                        watermarkPos = config.watermarkPos,
                        srtFile = if (hasClipSrt) tempSrtFile else null
                    )
                }

                saveToGallery(finalOutputFile, "Clip_${timestamp}_Part$part.mp4")
                if (finalOutputFile.exists()) finalOutputFile.delete()
                if (tempSrtFile.exists()) tempSrtFile.delete()
            }

            cachedLocalInput?.delete()
            ClipJobBus.state.value = ClipProgressState.Completed(total)
            showCompletedNotification(total)
        } catch (e: Exception) {
            ClipJobBus.state.value = ClipProgressState.Error(e.message ?: "Terjadi kesalahan saat merender.")
        }
    }

    private suspend fun copyUriToTempFile(uri: Uri, fileName: String): File = withContext(Dispatchers.IO) {
        val tempFile = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Gagal membuka file video dari galeri.")
        tempFile
    }

    private suspend fun saveToGallery(sourceFile: File, displayName: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoClipper")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = contentResolver.insert(collection, values) ?: return@withContext

        contentResolver.openOutputStream(itemUri)?.use { out ->
            sourceFile.inputStream().use { input -> input.copyTo(out) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(itemUri, values, null, null)
        }
    }

    private fun updateProgress(message: String, percent: Int) {
        ClipJobBus.state.value = ClipProgressState.Running(message, percent)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(message, percent))
    }

    private fun buildNotification(text: String, percent: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Video Clipper Pro")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, percent <= 0)
            .build()
    }

    private fun showCompletedNotification(count: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Pemotongan Selesai!")
            .setContentText("$count klip disimpan ke Movies/VideoClipper")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID + 1, notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proses Video Clipper",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
