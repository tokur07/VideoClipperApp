package com.clipper.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object MediaProcessor {

    private fun resolveFfmpegBinary(context: Context): String {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val unpackedBin = File(
            context.noBackupFilesDir,
            "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"
        )
        val nativeSoBin = File(nativeLibDir, "libffmpeg.so")

        return when {
            unpackedBin.exists() && unpackedBin.canExecute() -> unpackedBin.absolutePath
            nativeSoBin.exists() -> nativeSoBin.absolutePath
            else -> throw IllegalStateException("Binary FFmpeg tidak ditemukan di $nativeLibDir")
        }
    }

    private fun resolveSystemFont(): String {
        val candidates = listOf(
            "/system/fonts/Roboto-Bold.ttf",
            "/system/fonts/DroidSans-Bold.ttf",
            "/system/fonts/NotoSans-Bold.ttf",
            "/system/fonts/Roboto-Regular.ttf"
        )
        return candidates.firstOrNull { File(it).exists() } ?: "/system/fonts/Roboto-Regular.ttf"
    }

    private fun escapeDrawtext(input: String): String {
        return input
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "'\\\\\\''")
            .replace("%", "\\%")
    }

    private fun wrapLines(text: String, maxCharsPerLine: Int = 26): String {
        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (currentLine.isEmpty()) {
                currentLine.append(word)
            } else if (currentLine.length + 1 + word.length <= maxCharsPerLine) {
                currentLine.append(" ").append(word)
            } else {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines.joinToString("\n")
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
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val fontPath = resolveSystemFont()
        val overlayFilters = mutableListOf<String>()

        if (hookText.isNotBlank()) {
            val wrapped = wrapLines(hookText, maxCharsPerLine = 26)
            val escaped = escapeDrawtext(wrapped)
            val styleParams = when (hookStyle) {
                HookStyle.OUTLINE_WHITE ->
                    "fontcolor=white:borderw=4:bordercolor=black"
                HookStyle.YELLOW_BOX ->
                    "fontcolor=black:box=1:boxcolor=yellow@0.95:boxborderw=14"
                HookStyle.WHITE_BOX ->
                    "fontcolor=black:box=1:boxcolor=white@0.95:boxborderw=14"
            }
            overlayFilters.add(
                "drawtext=fontfile='$fontPath':text='$escaped':fontsize=42:$styleParams:x=(w-text_w)/2:y=h*0.11:line_spacing=8"
            )
        }

        if (watermarkText.isNotBlank()) {
            val escapedWm = escapeDrawtext(watermarkText.trim())
            val yExpr = when (watermarkPos) {
                WatermarkPos.TOP_SAFE -> "h*0.06"
                WatermarkPos.CENTER -> "(h-text_h)/2"
                WatermarkPos.BOTTOM_SAFE -> "h*0.76"
            }
            overlayFilters.add(
                "drawtext=fontfile='$fontPath':text='$escapedWm':fontsize=30:fontcolor=white@0.65:borderw=2:bordercolor=black@0.65:x=(w-text_w)/2:y=$yExpr"
            )
        }

        if (srtFile != null && srtFile.exists()) {
            val escapedSrt = srtFile.absolutePath
                .replace("\\", "\\\\")
                .replace(":", "\\:")
                .replace("'", "\\'")
            overlayFilters.add(
                "subtitles='$escapedSrt':fontsdir='/system/fonts':force_style='FontName=Roboto,FontSize=15,Bold=1,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=2,Shadow=1,Alignment=2,MarginV=68'"
            )
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

        if (layout == AspectLayout.BLUR_BG_9_16) {
            val postChain = if (overlayFilters.isNotEmpty()) {
                "[vbase];[vbase]${overlayFilters.joinToString(",")}[vout]"
            } else {
                "[vout]"
            }
            val filterComplex =
                "[0:v]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=25:5[bg];" +
                "[0:v]scale=1080:-2:force_original_aspect_ratio=decrease[fg];" +
                "[bg][fg]overlay=(W-w)/2:(H-h)/2$postChain"

            cmd.add("-filter_complex")
            cmd.add(filterComplex)
            cmd.add("-map")
            cmd.add("[vout]")
            cmd.add("-map")
            cmd.add("0:a?")
        } else {
            val videoFilters = mutableListOf<String>()
            when (layout) {
                AspectLayout.CROP_9_16 -> {
                    videoFilters.add("scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920")
                }
                AspectLayout.ORIGINAL -> {
                    videoFilters.add("scale=trunc(iw/2)*2:trunc(ih/2)*2")
                }
                else -> {}
            }
            videoFilters.addAll(overlayFilters)

            cmd.add("-vf")
            cmd.add(videoFilters.joinToString(","))
        }

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
            environment()["LD_LIBRARY_PATH"] = nativeLibDir
            redirectErrorStream(true)
        }
        val process = pb.start()
        val logOutput = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("FFmpeg gagal (kode $exitCode): ${logOutput.takeLast(350)}")
        }
    }
}
