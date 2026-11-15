package com.clipper.app

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

enum class ResolutionOption(val label: String, val maxHeight: Int) {
    MAX_4K("4K / Resolusi Tertinggi", 2160),
    FHD_1080("1080p Full HD", 1080),
    HD_720("720p HD", 720),
    SD_480("480p (Hemat Kuota)", 480)
}

enum class ClipMode {
    SINGLE,
    BATCH,
    AUTO_SPLIT
}

enum class AspectLayout {
    BLUR_BG_9_16,
    CROP_9_16,
    ORIGINAL
}

enum class HookStyle(val label: String) {
    OUTLINE_WHITE("Putih Bold + Outline Hitam"),
    YELLOW_BOX("Teks Hitam + Box Kuning"),
    WHITE_BOX("Teks Hitam + Box Putih")
}

enum class WatermarkPos(val label: String) {
    BOTTOM_SAFE("Bawah (Safe-Zone)"),
    CENTER("Tengah Layar"),
    TOP_SAFE("Atas (Safe-Zone)")
}

data class ClipSegment(
    val startMs: Long,
    val endMs: Long,
    val partNumber: Int,
    val totalParts: Int
)

data class ClipJobConfig(
    val localOrDirectUri: Uri?,
    val youtubePageUrl: String?,
    val isDirectStream: Boolean,
    val resolution: ResolutionOption,
    val segments: List<ClipSegment>,
    val layout: AspectLayout,
    val useAutoYoutubeSub: Boolean,
    val manualSrtContent: String?,
    val hookTitle: String,
    val autoPartNumber: Boolean,
    val hookStyle: HookStyle,
    val watermarkText: String,
    val watermarkPos: WatermarkPos
)

sealed class ClipProgressState {
    data object Idle : ClipProgressState()
    data class Running(val message: String, val percent: Int) : ClipProgressState()
    data class Completed(val totalClips: Int) : ClipProgressState()
    data class Error(val errorMessage: String) : ClipProgressState()
}

object ClipJobBus {
    var pendingConfig: ClipJobConfig? = null
    val state = MutableStateFlow<ClipProgressState>(ClipProgressState.Idle)
}
