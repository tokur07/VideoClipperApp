package com.clipper.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class ExtractedVideoMeta(
    val title: String,
    val durationMs: Long,
    val maxWidth: Int,
    val maxHeight: Int,
    val previewStreamUrl: String?,
    val httpHeaders: Map<String, String>,
    val hasSubtitles: Boolean
)

object YoutubeExtractor {

    @Volatile
    private var initialized = false

    suspend fun ensureInitialized(context: Context) = withContext(Dispatchers.IO) {
        if (!initialized) {
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            initialized = true
        }
    }

    suspend fun updateEngine(context: Context): String = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
        "Status update yt-dlp: $status"
    }

    suspend fun fetchMetadata(context: Context, pageUrl: String): ExtractedVideoMeta =
        withContext(Dispatchers.IO) {
            ensureInitialized(context)
            val request = YoutubeDLRequest(pageUrl).apply {
                addOption("-f", "bestvideo+bestaudio/best")
            }
            val info = YoutubeDL.getInstance().getInfo(request)

            val bestVideoFmt = info.requestedFormats?.firstOrNull {
                !it.vcodec.isNullOrBlank() && it.vcodec != "none"
            }
            val w = bestVideoFmt?.width?.takeIf { it > 0 } ?: info.width
            val h = bestVideoFmt?.height?.takeIf { it > 0 } ?: info.height

            val previewFormat = info.formats?.lastOrNull { fmt ->
                !fmt.url.isNullOrBlank() &&
                        fmt.ext == "mp4" &&
                        !fmt.vcodec.isNullOrBlank() && fmt.vcodec != "none" &&
                        !fmt.acodec.isNullOrBlank() && fmt.acodec != "none"
            }
            val previewUrl = previewFormat?.url ?: info.url

            val hasSubs = (info.subtitles?.isNotEmpty() == true) ||
                    (info.automaticCaptions?.isNotEmpty() == true)

            ExtractedVideoMeta(
                title = info.title ?: "Video Online",
                durationMs = info.duration.toLong() * 1000L,
                maxWidth = w,
                maxHeight = h,
                previewStreamUrl = previewUrl,
                httpHeaders = info.httpHeaders ?: emptyMap(),
                hasSubtitles = hasSubs
            )
        }

    suspend fun downloadSegment(
        context: Context,
        pageUrl: String,
        startMs: Long,
        endMs: Long,
        resolution: ResolutionOption,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        if (outputFile.exists()) outputFile.delete()

        val h = resolution.maxHeight
        val formatSelector =
            "bestvideo[height<=$h][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=$h]+bestaudio/best[height<=$h]/best"
        val sectionArg = String.format(
            Locale.US,
            "*%.2f-%.2f",
            startMs / 1000.0,
            endMs / 1000.0
        )

        val request = YoutubeDLRequest(pageUrl).apply {
            addOption("-f", formatSelector)
            addOption("--download-sections", sectionArg)
            addOption("--force-keyframes-at-cuts")
            addOption("--merge-output-format", "mp4")
            addOption("-o", outputFile.absolutePath)
        }

        YoutubeDL.getInstance().execute(request) { progress, _, _ ->
            if (progress >= 0f) {
                onProgress(progress.toInt().coerceIn(0, 100))
            }
        }

        if (outputFile.exists()) {
            outputFile
        } else {
            val baseName = outputFile.nameWithoutExtension
            outputFile.parentFile?.listFiles()?.firstOrNull { it.name.startsWith(baseName) }
                ?: throw IllegalStateException("File segmen YouTube gagal dibuat oleh FFmpeg.")
        }
    }

    suspend fun fetchYoutubeSubtitles(
        context: Context,
        pageUrl: String,
        cacheDir: File
    ): String? = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val prefix = "ytsub_${System.currentTimeMillis()}"
        val outTemplate = File(cacheDir, "$prefix.%(ext)s").absolutePath

        return@withContext try {
            val request = YoutubeDLRequest(pageUrl).apply {
                addOption("--skip-download")
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-langs", "id,en")
                addOption("--convert-subs", "srt")
                addOption("-o", outTemplate)
            }
            YoutubeDL.getInstance().execute(request)

            val subFile = cacheDir.listFiles()?.firstOrNull {
                it.name.startsWith(prefix) && (it.extension == "srt" || it.extension == "vtt")
            }
            val content = subFile?.readText(Charsets.UTF_8)
            cacheDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
            content
        } catch (_: Exception) {
            null
        }
    }
}
