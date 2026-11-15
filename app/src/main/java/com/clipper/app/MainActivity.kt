package com.clipper.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var localOrDirectUri: Uri? = null
    private var youtubePageUrl: String? = null
    private var isDirectStream = false
    private var totalDurationMs: Long = 0L
    private var manualSrtContent: String? = null

    private lateinit var playerView: PlayerView
    private lateinit var tvVideoInfo: TextView
    private lateinit var layoutResolution: LinearLayout
    private lateinit var spResolution: Spinner
    private lateinit var etStreamUrl: EditText
    private lateinit var rgClipMode: RadioGroup
    private lateinit var panelSingle: LinearLayout
    private lateinit var panelBatch: LinearLayout
    private lateinit var panelAutoSplit: LinearLayout
    private lateinit var etStartTime: EditText
    private lateinit var etEndTime: EditText
    private lateinit var etBatchTimestamps: EditText
    private lateinit var etSplitSec: EditText
    private lateinit var rgAspectLayout: RadioGroup
    private lateinit var swAutoSub: SwitchMaterial
    private lateinit var tvSrtStatus: TextView
    private lateinit var etHookTitle: EditText
    private lateinit var swAutoPartNumber: SwitchMaterial
    private lateinit var spHookStyle: Spinner
    private lateinit var etWatermark: EditText
    private lateinit var spWatermarkPos: Spinner
    private lateinit var btnStartClip: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                loadLocalGalleryVideo(uri)
            }
        }

    private val pickSrtLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (!content.isNullOrBlank()) {
                        manualSrtContent = content
                        tvSrtStatus.text = "File .SRT manual dimuat"
                        swAutoSub.isChecked = false
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal membaca file .srt: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupSpinners()
        setupListeners()
        observeBackgroundProgress()
        requestNotificationPermissionIfNeeded()

        lifecycleScope.launch {
            try {
                YoutubeExtractor.ensureInitialized(this@MainActivity)
            } catch (_: Exception) {
            }
        }

        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        tvVideoInfo = findViewById(R.id.tvVideoInfo)
        layoutResolution = findViewById(R.id.layoutResolution)
        spResolution = findViewById(R.id.spResolution)
        etStreamUrl = findViewById(R.id.etStreamUrl)
        rgClipMode = findViewById(R.id.rgClipMode)
        panelSingle = findViewById(R.id.panelSingle)
        panelBatch = findViewById(R.id.panelBatch)
        panelAutoSplit = findViewById(R.id.panelAutoSplit)
        etStartTime = findViewById(R.id.etStartTime)
        etEndTime = findViewById(R.id.etEndTime)
        etBatchTimestamps = findViewById(R.id.etBatchTimestamps)
        etSplitSec = findViewById(R.id.etSplitSec)
        rgAspectLayout = findViewById(R.id.rgAspectLayout)
        swAutoSub = findViewById(R.id.swAutoSub)
        tvSrtStatus = findViewById(R.id.tvSrtStatus)
        etHookTitle = findViewById(R.id.etHookTitle)
        swAutoPartNumber = findViewById(R.id.swAutoPartNumber)
        spHookStyle = findViewById(R.id.spHookStyle)
        etWatermark = findViewById(R.id.etWatermark)
        spWatermarkPos = findViewById(R.id.spWatermarkPos)
        btnStartClip = findViewById(R.id.btnStartClip)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
    }

    private fun setupSpinners() {
        spResolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ResolutionOption.entries.map { it.label }
        )
        spResolution.setSelection(1)

        spHookStyle.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            HookStyle.entries.map { it.label }
        )

        spWatermarkPos.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            WatermarkPos.entries.map { it.label }
        )
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnPickVideo).setOnClickListener {
            pickVideoLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }

        findViewById<Button>(R.id.btnLoadUrl).setOnClickListener {
            val url = etStreamUrl.text.toString().trim()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                loadNetworkUrl(url)
            } else {
                Toast.makeText(this, "Masukkan URL valid (http:// atau https://)", Toast.LENGTH_SHORT).show()
            }
        }

        rgClipMode.setOnCheckedChangeListener { _, checkedId ->
            panelSingle.visibility = if (checkedId == R.id.rbSingle) View.VISIBLE else View.GONE
            panelBatch.visibility = if (checkedId == R.id.rbBatch) View.VISIBLE else View.GONE
            panelAutoSplit.visibility = if (checkedId == R.id.rbAutoSplit) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btnSetStartFromPlayer).setOnClickListener {
            val pos = player?.currentPosition ?: 0L
            etStartTime.setText(SubtitleUtils.formatMsToHhMmSs(pos))
        }

        findViewById<Button>(R.id.btnSetEndFromPlayer).setOnClickListener {
            val pos = player?.currentPosition ?: 0L
            etEndTime.setText(SubtitleUtils.formatMsToHhMmSs(pos))
        }

        findViewById<Button>(R.id.btnPickSrt).setOnClickListener {
            pickSrtLauncher.launch(arrayOf("*/*"))
        }

        btnStartClip.setOnClickListener {
            triggerBackgroundClipping()
        }

        findViewById<Button>(R.id.btnUpdateYtDlp).setOnClickListener {
            lifecycleScope.launch {
                tvStatus.text = "Memperbarui engine yt-dlp..."
                try {
                    val res = YoutubeExtractor.updateEngine(this@MainActivity)
                    tvStatus.text = res
                } catch (e: Exception) {
                    tvStatus.text = "Gagal update yt-dlp: ${e.message}"
                }
            }
        }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val urlMatch = Regex("https?://\\S+").find(sharedText)?.value ?: return
            etStreamUrl.setText(urlMatch)
            loadNetworkUrl(urlMatch)
        }
    }

    private fun loadLocalGalleryVideo(uri: Uri) {
        lifecycleScope.launch {
            tvVideoInfo.text = "Membaca metadata video lokal..."
            val meta = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, uri)
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    Triple(dur, w, h)
                } catch (_: Exception) {
                    null
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }

            if (meta != null && meta.first > 0L) {
                localOrDirectUri = uri
                youtubePageUrl = null
                isDirectStream = false
                totalDurationMs = meta.first
                layoutResolution.visibility = View.GONE
                swAutoSub.isChecked = false
                swAutoSub.isEnabled = false

                tvVideoInfo.text =
                    "Galeri | Durasi: ${SubtitleUtils.formatMsToHhMmSs(totalDurationMs)} | ${meta.second}x${meta.third}"
                etEndTime.setText(SubtitleUtils.formatMsToHhMmSs(min(30_000L, totalDurationMs)))
                btnStartClip.isEnabled = true
                setupPreviewPlayer(uri, emptyMap())
            } else {
                tvVideoInfo.text = "Gagal memuat video galeri."
            }
        }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".mp4") || clean.endsWith(".mkv") ||
                clean.endsWith(".webm") || clean.endsWith(".m3u8") || clean.endsWith(".mpd")
    }

    private fun loadNetworkUrl(url: String) {
        lifecycleScope.launch {
            tvVideoInfo.text = "Mengekstrak info video & subtitle..."
            btnStartClip.isEnabled = false

            if (isDirectMediaUrl(url)) {
                val uri = Uri.parse(url)
                localOrDirectUri = uri
                youtubePageUrl = null
                isDirectStream = true
                totalDurationMs = 0L
                layoutResolution.visibility = View.GONE
                swAutoSub.isChecked = false
                swAutoSub.isEnabled = false

                tvVideoInfo.text = "Direct Stream URL dimuat"
                btnStartClip.isEnabled = true
                setupPreviewPlayer(uri, emptyMap())
                return@launch
            }

            try {
                val meta = YoutubeExtractor.fetchMetadata(this@MainActivity, url)
                localOrDirectUri = null
                youtubePageUrl = url
                isDirectStream = false
                totalDurationMs = meta.durationMs
                layoutResolution.visibility = View.VISIBLE
                swAutoSub.isEnabled = true
                swAutoSub.isChecked = false

                
                tvVideoInfo.text =
                    "${meta.title}\nDurasi: ${SubtitleUtils.formatMsToHhMmSs(totalDurationMs)} | Max: ${meta.maxWidth}x${meta.maxHeight}"

                if (totalDurationMs > 0L) {
                    etEndTime.setText(SubtitleUtils.formatMsToHhMmSs(min(30_000L, totalDurationMs)))
                }
                btnStartClip.isEnabled = true

                if (!meta.previewStreamUrl.isNullOrBlank()) {
                    setupPreviewPlayer(Uri.parse(meta.previewStreamUrl), meta.httpHeaders)
                }
            } catch (e: Exception) {
                tvVideoInfo.text = "Gagal memuat link: ${e.message}"
            }
        }
    }

    private fun setupPreviewPlayer(uri: Uri, headers: Map<String, String>) {
        player?.release()
        playerView.visibility = View.VISIBLE

        val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.setMediaItem(MediaItem.fromUri(uri))
                exo.prepare()
                exo.playWhenReady = false
            }
    }

    private fun buildSegments(): List<ClipSegment>? {
        return when (rgClipMode.checkedRadioButtonId) {
            R.id.rbSingle -> {
                val startMs = SubtitleUtils.parseTimeToMs(etStartTime.text.toString())
                val rawEndMs = SubtitleUtils.parseTimeToMs(etEndTime.text.toString())
                if (startMs == null || rawEndMs == null) {
                    Toast.makeText(this, "Format waktu harus JJ:MM:DD", Toast.LENGTH_SHORT).show()
                    return null
                }
                val endMs = if (totalDurationMs > 0L) min(rawEndMs, totalDurationMs) else rawEndMs
                if (startMs >= endMs || startMs < 0L) {
                    Toast.makeText(this, "Waktu selesai harus lebih besar dari mulai", Toast.LENGTH_SHORT).show()
                    return null
                }
                listOf(ClipSegment(startMs, endMs, 1, 1))
            }

            R.id.rbBatch -> {
                val lines = etBatchTimestamps.text.toString()
                    .lines()
                    .map { it.trim() }
                    .filter { it.contains("-") }
                val rawRanges = mutableListOf<Pair<Long, Long>>()

                for (line in lines) {
                    val parts = line.split("-")
                    if (parts.size == 2) {
                        val s = SubtitleUtils.parseTimeToMs(parts[0])
                        val e = SubtitleUtils.parseTimeToMs(parts[1])
                        if (s != null && e != null && e > s) {
                            val clampedEnd = if (totalDurationMs > 0L) min(e, totalDurationMs) else e
                            rawRanges.add(Pair(s, clampedEnd))
                        }
                    }
                }
                if (rawRanges.isEmpty()) {
                    Toast.makeText(this, "Masukkan minimal 1 rentang valid (JJ:MM:DD-JJ:MM:DD)", Toast.LENGTH_SHORT).show()
                    return null
                }
                rawRanges.mapIndexed { i, pair ->
                    ClipSegment(pair.first, pair.second, i + 1, rawRanges.size)
                }
            }

            else -> {
                if (totalDurationMs <= 0L) {
                    Toast.makeText(this, "Auto-Split butuh total durasi video.", Toast.LENGTH_SHORT).show()
                    return null
                }
                val chunkSec = etSplitSec.text.toString().toLongOrNull() ?: 0L
                if (chunkSec <= 0L) {
                    Toast.makeText(this, "Durasi per part harus > 0 detik", Toast.LENGTH_SHORT).show()
                    return null
                }
                val chunkMs = chunkSec * 1000L
                val temp = mutableListOf<Pair<Long, Long>>()
                var cur = 0L
                while (cur < totalDurationMs) {
                    val end = min(cur + chunkMs, totalDurationMs)
                    if (end - cur >= 1000L) temp.add(Pair(cur, end))
                    cur += chunkMs
                }
                temp.mapIndexed { i, pair ->
                    ClipSegment(pair.first, pair.second, i + 1, temp.size)
                }
            }
        }
    }

    private fun triggerBackgroundClipping() {
        val segments = buildSegments() ?: return
        player?.pause()

        val selectedLayout = when (rgAspectLayout.checkedRadioButtonId) {
            R.id.rbLayoutBlurBg -> AspectLayout.BLUR_BG_9_16
            R.id.rbLayoutCrop -> AspectLayout.CROP_9_16
            else -> AspectLayout.ORIGINAL
        }

        val config = ClipJobConfig(
            localOrDirectUri = localOrDirectUri,
            youtubePageUrl = youtubePageUrl,
            isDirectStream = isDirectStream,
            resolution = ResolutionOption.entries[spResolution.selectedItemPosition],
            segments = segments,
            layout = selectedLayout,
            useAutoYoutubeSub = swAutoSub.isChecked,
            manualSrtContent = manualSrtContent,
            hookTitle = etHookTitle.text.toString(),
            autoPartNumber = swAutoPartNumber.isChecked,
            hookStyle = HookStyle.entries[spHookStyle.selectedItemPosition],
            watermarkText = etWatermark.text.toString(),
            watermarkPos = WatermarkPos.entries[spWatermarkPos.selectedItemPosition]
        )

        ClipJobBus.pendingConfig = config
        ClipJobBus.state.value = ClipProgressState.Running("Memulai service latar belakang...", 0)

        val serviceIntent = Intent(this, ClipperService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun observeBackgroundProgress() {
        lifecycleScope.launch {
            ClipJobBus.state.collect { state ->
                when (state) {
                    is ClipProgressState.Idle -> {
                        progressBar.visibility = View.GONE
                    }
                    is ClipProgressState.Running -> {
                        btnStartClip.isEnabled = false
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = state.percent
                        tvStatus.text = state.message
                    }
                    is ClipProgressState.Completed -> {
                        btnStartClip.isEnabled = true
                        progressBar.visibility = View.GONE
                        tvStatus.text = "Selesai! ${state.totalClips} klip tersimpan di Movies/VideoClipper."
                        Toast.makeText(this@MainActivity, "Semua klip berhasil dirender!", Toast.LENGTH_LONG).show()
                    }
                    is ClipProgressState.Error -> {
                        btnStartClip.isEnabled = true
                        progressBar.visibility = View.GONE
                        tvStatus.text = "Error: ${state.errorMessage}"
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
