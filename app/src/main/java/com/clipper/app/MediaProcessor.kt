package com.clipper.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.ceil

object MediaProcessor {

    private data class SrtCue(
        val startSec: Double,
        val endSec: Double,
        val text: String
    )

    private fun resolveFfmpegBinary(context: Context): String {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val nativeSoBin = File(nativeLibDir, "libffmpeg.so")
        val unpackedBin = File(
            context.noBackupFilesDir,
            "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"
        )

        return when {
            nativeSoBin.exists() -> nativeSoBin.absolutePath
            unpackedBin.exists() && unpackedBin.canExecute() -> unpackedBin.absolutePath
            else -> throw IllegalStateException("Binary FFmpeg tidak ditemukan di $nativeLibDir")
        }
    }

    private fun resolveLdLibraryPath(context: Context): String {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val ytDlBaseDir = File(context.noBackupFilesDir, "youtubedl-android")

        val ffmpegLibDir = ytDlBaseDir.walkTopDown()
            .firstOrNull { it.name == "libavdevice.so" }
            ?.parentFile
            ?.absolutePath

        val pythonLibDir = File(ytDlBaseDir, "packages/python/usr/lib")
            .takeIf { it.exists() }
            ?.absolutePath

        return listOfNotNull(ffmpegLibDir, pythonLibDir, nativeLibDir)
            .distinct()
            .joinToString(":")
    }

    private fun getVideoDurationSec(path: String): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            durMs / 1000.0
        } catch (_: Exception) {
            0.0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun parseSrtCues(srtFile: File): List<SrtCue> {
        if (!srtFile.exists()) return emptyList()
        val raw = srtFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val blocks = raw.split(Regex("\\n\\s*\\n"))
        val result = mutableListOf<SrtCue>()

        for (block in blocks) {
            val lines = block.trim().lines().filter { it.isNotBlank() }
            val timeIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeIdx == -1) continue

            val parts = lines[timeIdx].split("-->")
            if (parts.size < 2) continue

            val startMs = SubtitleUtils.parseTimeToMs(parts[0].trim()) ?: continue
            val endMs = SubtitleUtils.parseTimeToMs(parts[1].trim()) ?: continue
            val text = lines.drop(timeIdx + 1).joinToString(" ").trim()
            if (text.isNotEmpty() && endMs > startMs) {
                result.add(SrtCue(startMs / 1000.0, endMs / 1000.0, text))
            }
        }
        return result
    }

    private fun drawStaticLayer(
        canvas: Canvas,
        width: Int,
        height: Int,
        hookText: String,
        hookStyle: HookStyle,
        watermarkText: String,
        watermarkPos: WatermarkPos
    ) {
        if (hookText.isNotBlank()) {
            val maxTextWidth = (width * 0.84f).toInt()
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 32f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = if (hookStyle == HookStyle.OUTLINE_WHITE) Color.WHITE else Color.BLACK
            }

            val staticLayout = StaticLayout.Builder
                .obtain(hookText.trim(), 0, hookText.trim().length, textPaint, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(6f, 1.0f)
                .build()

            val topY = height * 0.10f
            var maxLineW = 0f
            for (i in 0 until staticLayout.lineCount) {
                maxLineW = maxOf(maxLineW, staticLayout.getLineWidth(i))
            }

            if (hookStyle == HookStyle.YELLOW_BOX || hookStyle == HookStyle.WHITE_BOX) {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hookStyle == HookStyle.YELLOW_BOX) Color.YELLOW else Color.WHITE
                    style = Paint.Style.FILL
                }
                val padX = 20f
                val padY = 12f
                val left = (width - maxLineW) / 2f - padX
                val right = (width + maxLineW) / 2f + padX
                val rect = RectF(left, topY - padY, right, topY + staticLayout.height + padY)
                canvas.drawRoundRect(rect, 14f, 14f, bgPaint)
            }

            canvas.save()
            canvas.translate((width - maxTextWidth) / 2f, topY)

            if (hookStyle == HookStyle.OUTLINE_WHITE) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeWidth = 6f
                textPaint.color = Color.BLACK
                staticLayout.draw(canvas)

                textPaint.style = Paint.Style.FILL
                textPaint.color = Color.WHITE
                staticLayout.draw(canvas)
            } else {
                staticLayout.draw(canvas)
            }
            canvas.restore()
        }

        if (watermarkText.isNotBlank()) {
            val wm = watermarkText.trim()
            val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val yPos = when (watermarkPos) {
                WatermarkPos.TOP_SAFE -> height * 0.065f
                WatermarkPos.CENTER -> height * 0.50f
                WatermarkPos.BOTTOM_SAFE -> height * 0.77f
            }

            wmPaint.style = Paint.Style.STROKE
            wmPaint.strokeWidth = 4f
            wmPaint.color = Color.argb(175, 0, 0, 0)
            canvas.drawText(wm, width / 2f, yPos, wmPaint)

            wmPaint.style = Paint.Style.FILL
            wmPaint.color = Color.argb(190, 255, 255, 255)
            canvas.drawText(wm, width / 2f, yPos, wmPaint)
        }
    }

    private fun drawSubtitleOnCanvas(canvas: Canvas, width: Int, height: Int, cueText: String) {
        val maxTextWidth = (width * 0.82f).toInt()
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.WHITE
        }

        val staticLayout = StaticLayout.Builder
            .obtain(cueText, 0, cueText.length, textPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(4f, 1.0f)
            .build()

        val bottomY = height * 0.81f
        val topY = bottomY - staticLayout.height

        canvas.save()
        canvas.translate((width - maxTextWidth) / 2f, topY)

        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = 6f
        textPaint.color = Color.BLACK
        staticLayout.draw(canvas)

        textPaint.style = Paint.Style.FILL
        textPaint.color = Color.WHITE
        staticLayout.draw(canvas)

        canvas.restore()
    }

    suspend fun renderClip(
        context: Context,
        inputPath: String,
        outputPath: String,
        trimStartMs: Long?,
        trimDurationMs: Long?,
        layout: AspectLayout,
        hookText: String,
        hookStyle: HookStyle,
        watermarkText: String,
        watermarkPos: WatermarkPos,
        srtFile: File?,
        onRenderProgress: ((Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        YoutubeExtractor.ensureInitialized(context)

        val ffmpegBin = resolveFfmpegBinary(context)
        val ldLibraryPath = resolveLdLibraryPath(context)

        val expectedDurationSec = if (trimDurationMs != null && trimDurationMs > 0L) {
            trimDurationMs / 1000.0
        } else {
            getVideoDurationSec(inputPath).coerceAtLeast(1.0)
        }

        val cues = if (srtFile != null && srtFile.exists()) parseSrtCues(srtFile) else emptyList()
        val hasAnyText = hookText.isNotBlank() || watermarkText.isNotBlank() || cues.isNotEmpty()

        val cmd = mutableListOf<String>()

        if (trimStartMs != null && trimDurationMs != null) {
            cmd.add("-ss")
            cmd.add(String.format(Locale.US, "%.3f", trimStartMs / 1000.0))
        }

        cmd.add("-i")
        cmd.add(inputPath)

        if (trimDurationMs != null) {
            cmd.add("-t")
            cmd.add(String.format(Locale.US, "%.3f", trimDurationMs / 1000.0))
        }

        // Input 1: Aliran overlay teks tunggal (4 fps cukup untuk transisi subtitle, hemat RAM & CPU)
        val ovW = 720
        val ovH = 1280
        val ovFps = 4
        if (hasAnyText) {
            cmd.addAll(
                listOf(
                    "-f", "rawvideo",
                    "-pix_fmt", "rgba",
                    "-s", "${ovW}x${ovH}",
                    "-r", "$ovFps",
                    "-i", "pipe:0"
                )
            )
        }

        val filterSteps = mutableListOf<String>()
        when (layout) {
            AspectLayout.BLUR_BG_9_16 -> {
                filterSteps.add("[0:v]scale=180:320:force_original_aspect_ratio=increase,crop=180:320,boxblur=6:2,scale=720:1280[bg]")
                filterSteps.add("[0:v]scale=720:-2:force_original_aspect_ratio=decrease[fg]")
                filterSteps.add("[bg][fg]overlay=(W-w)/2:(H-h)/2[v0]")
            }
            AspectLayout.CROP_9_16 -> {
                filterSteps.add("[0:v]scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280[v0]")
            }
            AspectLayout.ORIGINAL -> {
                filterSteps.add("[0:v]scale=trunc(iw/2)*2:trunc(ih/2)*2[v0]")
            }
        }

        val finalVideoPad = if (hasAnyText) {
            filterSteps.add("[1:v][v0]scale2ref=w=iw:h=ih[ov_s][base]")
            filterSteps.add("[base][ov_s]overlay=0:0:shortest=1[vout]")
            "[vout]"
        } else {
            "[v0]"
        }

        cmd.add("-filter_complex")
        cmd.add(filterSteps.joinToString(";"))
        cmd.add("-map")
        cmd.add(finalVideoPad)
        cmd.add("-map")
        cmd.add("0:a?")

        cmd.addAll(
            listOf(
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "23",
                "-threads", "0",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                "-y",
                outputPath
            )
        )

        val pb = ProcessBuilder(listOf(ffmpegBin) + cmd).apply {
            environment()["LD_LIBRARY_PATH"] = ldLibraryPath
            redirectErrorStream(true)
        }
        val process = pb.start()

        // Thread pengumpan frame overlay RGBA ke stdin FFmpeg (mendukung ratusan baris subtitle tanpa batas)
        val feederThread = if (hasAnyText) {
            Thread {
                val bitmap = Bitmap.createBitmap(ovW, ovH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val buffer = ByteBuffer.allocate(ovW * ovH * 4)
                val totalFrames = ceil(expectedDurationSec * ovFps).toInt() + ovFps
                var lastActiveCueIndex = -2
                var cachedFrameBytes: ByteArray? = null

                try {
                    process.outputStream.use { stdin ->
                        for (frameIdx in 0 until totalFrames) {
                            val tSec = frameIdx.toDouble() / ovFps
                            val activeIdx = cues.indexOfFirst { tSec >= it.startSec && tSec <= it.endSec }

                            if (activeIdx != lastActiveCueIndex || cachedFrameBytes == null) {
                                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                                drawStaticLayer(canvas, ovW, ovH, hookText, hookStyle, watermarkText, watermarkPos)
                                if (activeIdx >= 0) {
                                    drawSubtitleOnCanvas(canvas, ovW, ovH, cues[activeIdx].text)
                                }
                                buffer.rewind()
                                bitmap.copyPixelsToBuffer(buffer)
                                cachedFrameBytes = buffer.array().clone()
                                lastActiveCueIndex = activeIdx
                            }
                            stdin.write(cachedFrameBytes)
                        }
                        stdin.flush()
                    }
                } catch (_: Exception) {
                    // Pipa tertutup normal saat FFmpeg selesai
                } finally {
                    bitmap.recycle()
                }
            }.apply { start() }
        } else {
            null
        }

        val tailLines = ArrayDeque<String>(20)
        val timeRegex = Regex("time=(\\d+):(\\d+):(\\d+\\.\\d+)")

        process.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (tailLines.size >= 20) tailLines.removeFirst()
                tailLines.addLast(line)

                val match = timeRegex.find(line)
                if (match != null && expectedDurationSec > 0.0) {
                    val h = match.groupValues[1].toDoubleOrNull() ?: 0.0
                    val m = match.groupValues[2].toDoubleOrNull() ?: 0.0
                    val s = match.groupValues[3].toDoubleOrNull() ?: 0.0
                    val curSec = h * 3600.0 + m * 60.0 + s
                    val pct = ((curSec / expectedDurationSec) * 100.0).toInt().coerceIn(0, 99)
                    onRenderProgress?.invoke(pct)
                }
            }
        }

        val exitCode = process.waitFor()
        feederThread?.join(2000)

        if (exitCode != 0) {
            throw RuntimeException("FFmpeg gagal (kode $exitCode): ${tailLines.joinToString("\n").takeLast(350)}")
        }
        onRenderProgress?.invoke(100)
    }
}
