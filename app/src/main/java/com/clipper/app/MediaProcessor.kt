package com.clipper.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object MediaProcessor {

    private data class TimedOverlay(
        val imageFile: File,
        val startSec: Double?,
        val endSec: Double?
    )

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
            val text = lines.drop(timeIdx + 1).joinToString("\n").trim()
            if (text.isNotEmpty() && endMs > startMs) {
                result.add(SrtCue(startMs / 1000.0, endMs / 1000.0, text))
            }
        }
        return result
    }

    private fun createStaticTextOverlay(
        cacheDir: File,
        hookText: String,
        hookStyle: HookStyle,
        watermarkText: String,
        watermarkPos: WatermarkPos
    ): File {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Gambar Hook / Judul Atas
        if (hookText.isNotBlank()) {
            val maxTextWidth = (width * 0.84f).toInt()
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 46f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = if (hookStyle == HookStyle.OUTLINE_WHITE) Color.WHITE else Color.BLACK
            }

            val staticLayout = StaticLayout.Builder
                .obtain(hookText.trim(), 0, hookText.trim().length, textPaint, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(8f, 1.0f)
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
                val padX = 28f
                val padY = 18f
                val left = (width - maxLineW) / 2f - padX
                val right = (width + maxLineW) / 2f + padX
                val rect = RectF(left, topY - padY, right, topY + staticLayout.height + padY)
                canvas.drawRoundRect(rect, 20f, 20f, bgPaint)
            }

            canvas.save()
            canvas.translate((width - maxTextWidth) / 2f, topY)

            if (hookStyle == HookStyle.OUTLINE_WHITE) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeWidth = 9f
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

        // 2. Gambar Watermark
        if (watermarkText.isNotBlank()) {
            val wm = watermarkText.trim()
            val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val yPos = when (watermarkPos) {
                WatermarkPos.TOP_SAFE -> height * 0.065f
                WatermarkPos.CENTER -> height * 0.50f
                WatermarkPos.BOTTOM_SAFE -> height * 0.77f
            }

            wmPaint.style = Paint.Style.STROKE
            wmPaint.strokeWidth = 6f
            wmPaint.color = Color.argb(175, 0, 0, 0)
            canvas.drawText(wm, width / 2f, yPos, wmPaint)

            wmPaint.style = Paint.Style.FILL
            wmPaint.color = Color.argb(190, 255, 255, 255)
            canvas.drawText(wm, width / 2f, yPos, wmPaint)
        }

        val file = File(cacheDir, "ov_static_${System.nanoTime()}.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    private fun createSubtitleCueOverlay(cacheDir: File, cueText: String): File {
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val maxTextWidth = (width * 0.82f).toInt()
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.WHITE
        }

        val staticLayout = StaticLayout.Builder
            .obtain(cueText, 0, cueText.length, textPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(6f, 1.0f)
            .build()

        // Safe-zone di atas tombol interaksi bawah TikTok/Reels/Shorts
        val bottomY = height * 0.81f
        val topY = bottomY - staticLayout.height

        canvas.save()
        canvas.translate((width - maxTextWidth) / 2f, topY)

        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = 9f
        textPaint.color = Color.BLACK
        staticLayout.draw(canvas)

        textPaint.style = Paint.Style.FILL
        textPaint.color = Color.WHITE
        staticLayout.draw(canvas)

        canvas.restore()

        val file = File(cacheDir, "ov_sub_${System.nanoTime()}.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
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
        srtFile: File?
    ) = withContext(Dispatchers.IO) {
        YoutubeExtractor.ensureInitialized(context)

        val ffmpegBin = resolveFfmpegBinary(context)
        val ldLibraryPath = resolveLdLibraryPath(context)
        val tempOverlays = mutableListOf<TimedOverlay>()

        try {
            // Siapkan layer PNG untuk Hook & Watermark
            if (hookText.isNotBlank() || watermarkText.isNotBlank()) {
                val staticPng = createStaticTextOverlay(
                    cacheDir = context.cacheDir,
                    hookText = hookText,
                    hookStyle = hookStyle,
                    watermarkText = watermarkText,
                    watermarkPos = watermarkPos
                )
                tempOverlays.add(TimedOverlay(staticPng, null, null))
            }

            // Siapkan layer PNG untuk setiap baris subtitle .srt
            if (srtFile != null && srtFile.exists()) {
                val cues = parseSrtCues(srtFile)
                for (cue in cues) {
                    val subPng = createSubtitleCueOverlay(context.cacheDir, cue.text)
                    tempOverlays.add(TimedOverlay(subPng, cue.startSec, cue.endSec))
                }
            }

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

            // Masukkan semua PNG overlay sebagai input tambahan (-i)
            for (ov in tempOverlays) {
                cmd.add("-i")
                cmd.add(ov.imageFile.absolutePath)
            }

            val filterSteps = mutableListOf<String>()

            // Langkah dasar: Bentuk kanvas video
            when (layout) {
                AspectLayout.BLUR_BG_9_16 -> {
                    filterSteps.add("[0:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=25:5[bg]")
                    filterSteps.add("[0:v]scale=1080:-2:force_original_aspect_ratio=decrease[fg]")
                    filterSteps.add("[bg][fg]overlay=(W-w)/2:(H-h)/2[v0]")
                }
                AspectLayout.CROP_9_16 -> {
                    filterSteps.add("[0:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920[v0]")
                }
                AspectLayout.ORIGINAL -> {
                    filterSteps.add("[0:v]scale=trunc(iw/2)*2:trunc(ih/2)*2[v0]")
                }
            }

            // Tempelkan setiap PNG overlay menggunakan filter 'overlay'
            var currentLabel = "v0"
            tempOverlays.forEachIndexed { idx, ov ->
                val inputIdx = idx + 1
                val scaledLabel = "ov_s$idx"
                val nextLabel = "v${idx + 1}"

                // Sesuaikan skala overlay PNG dengan dimensi video aktif (penting untuk mode ORIGINAL)
                filterSteps.add("[$inputIdx:v][$currentLabel]scale2ref=w=iw:h=ih[$scaledLabel][base$idx]")

                if (ov.startSec != null && ov.endSec != null) {
                    val enableExpr = String.format(
                        Locale.US,
                        "between(t,%.3f,%.3f)",
                        ov.startSec,
                        ov.endSec
                    )
                    filterSteps.add("[base$idx][$scaledLabel]overlay=0:0:enable='$enableExpr'[$nextLabel]")
                } else {
                    filterSteps.add("[base$idx][$scaledLabel]overlay=0:0[$nextLabel]")
                }
                currentLabel = nextLabel
            }

            cmd.add("-filter_complex")
            cmd.add(filterSteps.joinToString(";"))
            cmd.add("-map")
            cmd.add("[$currentLabel]")
            cmd.add("-map")
            cmd.add("0:a?")

            cmd.addAll(
                listOf(
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "22",
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
            val logOutput = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("FFmpeg gagal (kode $exitCode): ${logOutput.takeLast(350)}")
            }
        } finally {
            tempOverlays.forEach { if (it.imageFile.exists()) it.imageFile.delete() }
        }
    }
}
