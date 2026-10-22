package com.devilbike.loopwallpaper

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.devilbike.loopwallpaper.databinding.ActivityMainBinding
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var sourceFile: File? = null
    private var thumbs: VideoThumbs? = null
    private var loop: LoopResult? = null
    private var renderedFile: File? = null
    private var player: ExoPlayer? = null
    private var findJob: Job? = null
    private var busy = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.pickButton.setOnClickListener {
            pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        b.createButton.setOnClickListener { createLoop() }
        b.setWallpaperButton.setOnClickListener { setWallpaper() }
        b.saveButton.setOnClickListener { saveToGallery() }

        b.rangeSlider.addOnChangeListener(RangeSlider.OnChangeListener { _, _, _ -> onRangeChanged() })
        for (slider in listOf(b.lengthSlider, b.flexSlider, b.fadeSlider)) {
            slider.addOnChangeListener(Slider.OnChangeListener { _, _, _ -> updateLabels() })
        }
        b.rangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {}
            override fun onStopTrackingTouch(slider: RangeSlider) = findLoop()
        })
        val retrigger = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = findLoop()
        }
        b.lengthSlider.addOnSliderTouchListener(retrigger)
        b.flexSlider.addOnSliderTouchListener(retrigger)

        if (savedInstanceState == null) handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { loadVideo(it) }
    }

    // ---- Loading and analysing ----

    private fun loadVideo(uri: Uri) {
        if (busy) return
        lifecycleScope.launch {
            setBusy("Opening video…")
            try {
                findJob?.cancel()
                releasePlayer()
                b.settingsGroup.isVisible = false
                b.previewGroup.isVisible = false
                thumbs = null
                loop = null
                renderedFile = null

                showProgress("Copying video…", 0f)
                val file = withContext(Dispatchers.IO) { copyToCache(uri) }
                sourceFile = file

                showProgress("Scanning frames…", 0f)
                val t = withContext(Dispatchers.Default) {
                    VideoAnalyzer.analyze(file) { p -> b.progressBar.post { showProgress("Scanning frames…", p) } }
                }
                if (t.durationUs < 1_000_000) throw LoopException("This video is shorter than a second.")
                thumbs = t
                b.videoInfo.text = String.format(
                    Locale.US, "%dx%d, %.1f s, %.0f fps", t.width, t.height, t.durationUs / 1e6, t.fps
                )
                b.videoInfo.isVisible = true
                setupSliders(t)
                b.settingsGroup.isVisible = true
                setIdle()
                findLoop()
            } catch (e: Exception) {
                setIdle()
                showError(e)
            }
        }
    }

    private fun copyToCache(uri: Uri): File {
        val file = File(cacheDir, "source.mp4")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open that video." }
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun setupSliders(t: VideoThumbs) {
        val seconds = t.durationUs / 1e6f
        b.rangeSlider.valueFrom = 0f
        b.rangeSlider.valueTo = seconds
        b.rangeSlider.values = listOf(0f, seconds)
        b.lengthSlider.valueFrom = MIN_LENGTH_S
        b.lengthSlider.valueTo = max(seconds, MIN_LENGTH_S + 0.1f)
        b.lengthSlider.value = min(10f, seconds * 0.8f).coerceIn(MIN_LENGTH_S, b.lengthSlider.valueTo)
        updateLabels()
    }

    private fun onRangeChanged() {
        val (from, to) = b.rangeSlider.values.let { it[0] to it[1] }
        val newMax = max(to - from, MIN_LENGTH_S + 0.1f)
        if (b.lengthSlider.value > newMax) b.lengthSlider.value = newMax
        b.lengthSlider.valueTo = newMax
        updateLabels()
    }

    private fun updateLabels() {
        val (from, to) = b.rangeSlider.values.let { it[0] to it[1] }
        b.rangeLabel.text = String.format(Locale.US, "Use the part between %.1f s and %.1f s", from, to)
        b.lengthLabel.text = String.format(Locale.US, "Loop length: about %.1f s", b.lengthSlider.value)
        b.flexLabel.text = String.format(
            Locale.US, "Length flexibility: ±%.0f%% (more = better match)", b.flexSlider.value
        )
        b.fadeLabel.text = if (b.fadeSlider.value == 0f) {
            "Crossfade at the seam: off"
        } else {
            String.format(Locale.US, "Crossfade at the seam: %.2f s", b.fadeSlider.value)
        }
    }

    private fun findLoop() {
        val t = thumbs ?: return
        findJob?.cancel()
        findJob = lifecycleScope.launch {
            b.resultText.text = "Looking for the best loop…"
            b.createButton.isEnabled = false
            val (from, to) = b.rangeSlider.values.let { it[0] to it[1] }
            val length = b.lengthSlider.value
            val flex = b.flexSlider.value / 100f
            val result = withContext(Dispatchers.Default) {
                LoopFinder.find(
                    t,
                    rangeStartUs = (from * 1e6).toLong(),
                    rangeEndUs = (to * 1e6).toLong(),
                    minLenUs = (max(MIN_LENGTH_S, length * (1 - flex)) * 1e6).toLong(),
                    maxLenUs = (length * (1 + flex) * 1e6).toLong(),
                )
            }
            loop = result
            if (result == null) {
                b.resultText.text = "No loop fits those settings. Try a shorter length or a wider range."
                b.endFrame.setImageDrawable(null)
                b.startFrame.setImageDrawable(null)
                return@launch
            }
            b.resultText.text = describe(result)
            b.createButton.isEnabled = !busy
            showSeamFrames(result)
        }
    }

    private fun describe(r: LoopResult): String {
        val verdict = when {
            r.seamRatio < 1.2f -> "Excellent. The jump should be invisible."
            r.seamRatio < 2.5f -> "Good. The crossfade will hide it."
            r.seamRatio < 5f -> "OK. A longer crossfade will help."
            else -> "Rough. Try another length, more flexibility, or a longer crossfade."
        }
        return String.format(
            Locale.US,
            "Loop from %.2f s to %.2f s (%.2f s long).\nThe jump at the seam is %.1f× a normal frame change. %s",
            r.startUs / 1e6, r.endUs / 1e6, r.lengthUs / 1e6, r.seamRatio, verdict
        )
    }

    private suspend fun showSeamFrames(r: LoopResult) {
        val file = sourceFile ?: return
        val frames = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                fun frame(us: Long): Bitmap? =
                    retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, 320, 180)
                frame(r.endUs) to frame(r.startUs)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null to null
            } finally {
                retriever.release()
            }
        }
        b.endFrame.setImageBitmap(frames.first)
        b.startFrame.setImageBitmap(frames.second)
    }

    // ---- Rendering ----

    private fun createLoop() {
        val r = loop ?: return
        val source = sourceFile ?: return
        val t = thumbs ?: return
        if (busy) return
        lifecycleScope.launch {
            setBusy("Creating loop…")
            try {
                releasePlayer()
                renderedFile?.delete()
                val out = File(cacheDir, "loop_${System.currentTimeMillis()}.mp4")
                val fadeUs = (b.fadeSlider.value * 1e6).toLong()
                withContext(Dispatchers.IO) {
                    LoopRenderer(source, out, r.startUs, r.endUs, fadeUs, t.fps).render { p ->
                        b.progressBar.post { showProgress("Creating loop…", p) }
                    }
                }
                renderedFile = out
                setIdle()
                showPreview(out)
            } catch (e: Exception) {
                setIdle()
                showError(e)
            }
        }
    }

    private fun showPreview(file: File) {
        releasePlayer()
        val p = ExoPlayer.Builder(this).build()
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.volume = 0f
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.play()
        player = p
        b.playerView.player = p
        b.previewGroup.isVisible = true
        b.root.post { b.root.smoothScrollTo(0, b.previewGroup.top) }
    }

    private fun releasePlayer() {
        b.playerView.player = null
        player?.release()
        player = null
    }

    // ---- Output ----

    private fun setWallpaper() {
        val file = renderedFile ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { WallpaperStore.install(this@MainActivity, file) }
                val manager = WallpaperManager.getInstance(this@MainActivity)
                if (manager.wallpaperInfo?.packageName == packageName) {
                    toast("Wallpaper updated.")
                    return@launch
                }
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, LoopWallpaperService::class.java)
                )
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                }
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    private fun saveToGallery() {
        val file = renderedFile ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val name = "loop_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LoopWallpaper")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val resolver = contentResolver
                    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw LoopException("Couldn't create the file in your gallery.")
                    resolver.openOutputStream(uri).use { out ->
                        requireNotNull(out) { "Couldn't write to your gallery." }
                        file.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                toast("Saved to Movies/LoopWallpaper.")
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    // ---- UI state ----

    private fun setBusy(message: String) {
        busy = true
        b.pickButton.isEnabled = false
        b.createButton.isEnabled = false
        b.setWallpaperButton.isEnabled = false
        b.saveButton.isEnabled = false
        showProgress(message, 0f)
    }

    private fun setIdle() {
        busy = false
        b.progressGroup.visibility = View.GONE
        b.pickButton.isEnabled = true
        b.createButton.isEnabled = loop != null
        b.setWallpaperButton.isEnabled = true
        b.saveButton.isEnabled = true
    }

    private fun showProgress(message: String, fraction: Float) {
        if (!busy) return
        b.progressGroup.visibility = View.VISIBLE
        b.progressText.text = message
        b.progressBar.setProgressCompat((fraction * 100).toInt(), false)
    }

    private fun showError(e: Exception) {
        if (e is CancellationException) return
        val message = if (e is LoopException) e.message else "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
        toast(message ?: "Something went wrong.")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val MIN_LENGTH_S = 0.5f
    }
}
