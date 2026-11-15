package com.clipper.app

import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object SubtitleUtils {

    fun parseTimeToMs(raw: String): Long? {
        val clean = raw.trim().replace(',', '.')
        val parts = clean.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    ((h * 3600 + m * 60) * 1000L) + (s * 1000L).toLong()
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    (m * 60 * 1000L) + (s * 1000L).toLong()
                }
                1 -> (parts[0].toDouble() * 1000L).toLong()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun formatMsToHhMmSs(ms: Long): String {
        val totalSec = max(0L, ms / 1000L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    fun formatMsToSrtTimestamp(ms: Long): String {
        val safeMs = max(0L, ms)
        val totalSec = safeMs / 1000L
        val millis = safeMs % 1000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, millis)
    }

    fun sliceSrtForSegment(
        rawSubtitle: String,
        segmentStartMs: Long,
        segmentEndMs: Long,
        outputSrtFile: File
    ): Boolean {
        val normalized = rawSubtitle
            .replace("\r\n", "\n")
            .removePrefix("\uFEFF")
            .replace(Regex("^WEBVTT.*\\n", RegexOption.MULTILINE), "")

        val blocks = normalized.split(Regex("\\n\\s*\\n"))
        val outBuilder = StringBuilder()
        var indexCounter = 1

        for (block in blocks) {
            val lines = block.trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue

            val timeLineIdx = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIdx == -1) continue

            val timeParts = lines[timeLineIdx].split("-->")
            if (timeParts.size < 2) continue

            val cueStart = parseTimeToMs(timeParts[0].trim()) ?: continue
            val endToken = timeParts[1].trim().split(Regex("\\s+")).firstOrNull() ?: continue
            val cueEnd = parseTimeToMs(endToken) ?: continue

            if (cueEnd > segmentStartMs && cueStart < segmentEndMs) {
                val relativeStart = max(0L, cueStart - segmentStartMs)
                val relativeEnd = min(segmentEndMs - segmentStartMs, cueEnd - segmentStartMs)
                if (relativeEnd - relativeStart < 150L) continue

                val textLines = lines.drop(timeLineIdx + 1)
                    .joinToString("\n")
                    .replace(Regex("<[^>]*>"), "")
                    .trim()

                if (textLines.isNotEmpty()) {
                    outBuilder.append(indexCounter++).append("\n")
                    outBuilder.append(formatMsToSrtTimestamp(relativeStart))
                        .append(" --> ")
                        .append(formatMsToSrtTimestamp(relativeEnd))
                        .append("\n")
                    outBuilder.append(textLines).append("\n\n")
                }
            }
        }

        if (indexCounter == 1) return false
        outputSrtFile.writeText(outBuilder.toString(), Charsets.UTF_8)
        return true
    }
}
