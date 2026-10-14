package mv.magnettv

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import java.io.File

class PlayerActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var loadingBox: View
    private lateinit var loadingText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var statusText: TextView
    private var player: ExoPlayer? = null
    private var controllerVisible = false
    @Volatile private var destroyed = false
    private val handler = Handler(Looper.getMainLooper())

    // What is playing, for the "Recently watched" list.
    private var link = ""
    private var direct = false
    private var key = ""
    private var title = ""
    private var thumbTaken = false

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (!direct) {
                val buffering = p == null || p.playbackState == Player.STATE_BUFFERING
                statusText.text = TorrentEngine.statusText()
                statusText.visibility = if (controllerVisible || buffering) View.VISIBLE else View.GONE
            }
            // First picture for the list: a frame from about 20 s in.
            if (p != null && !thumbTaken && p.isPlaying && p.currentPosition > 20_000) saveProgress(true)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        loadingBox = findViewById(R.id.loading_box)
        loadingText = findViewById(R.id.loading_text)
        spinner = findViewById(R.id.spinner)
        statusText = findViewById(R.id.status_text)

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            controllerVisible = v == View.VISIBLE
        })

        val url = intent.getStringExtra(EXTRA_URL)
        val magnet = intent.getStringExtra(EXTRA_MAGNET)
        link = url ?: magnet ?: run { finish(); return }
        direct = url != null
        key = RecentStore.keyFor(link)
        title = intent.getStringExtra(EXTRA_TITLE) ?: RecentStore.get(this, key)?.title.orEmpty()

        if (direct) {
            statusText.visibility = View.GONE
            if (title.isBlank()) title = RecentStore.titleFromUrl(link)
            startPlayer(Uri.parse(link), emptyList())
        } else {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "stream")
            Thread {
                try {
                    val sf = TorrentEngine.open(link, dir) { msg -> runOnUiThread { loadingText.text = msg } }
                    if (!destroyed) runOnUiThread {
                        title = RecentStore.cleanTitle(sf.name)
                        startPlayer(Uri.fromFile(sf.file), sf.subtitles)
                    }
                } catch (e: Exception) {
                    if (!destroyed) runOnUiThread { showError(e.message ?: "Couldn't start stream") }
                }
            }.start()
        }

        handler.post(tick)
    }

    private fun startPlayer(uri: Uri, subtitles: List<File>) {
        if (destroyed) return
        loadingBox.visibility = View.GONE

        val item = MediaItem.Builder()
            .setUri(uri)
            .setSubtitleConfigurations(buildSubtitles(subtitles))
            .build()
        val dataSources: DataSource.Factory = if (direct) {
            DefaultDataSource.Factory(
                this,
                DefaultHttpDataSource.Factory()
                    .setUserAgent(PageScraper.UA)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(20_000)
                    .setReadTimeoutMs(30_000)
            )
        } else {
            DataSource.Factory { RoutingDataSource(this) }
        }
        val source = DefaultMediaSourceFactory(dataSources).createMediaSource(item)

        playerView.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.25f)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 120_000, 3_000, 6_000)
            .build()

        val p = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showError("Playback error: ${error.errorCodeName}\n${error.cause?.message ?: ""}")
            }
        })
        p.setMediaSource(source)

        // Carry on from where it was stopped last time.
        val prev = RecentStore.get(this, key)
        val resumeAt = prev?.positionMs ?: 0L
        val nearEnd = prev != null && prev.durationMs > 0 && resumeAt > prev.durationMs - 60_000
        if (resumeAt > 30_000 && !nearEnd) {
            p.seekTo(resumeAt)
            Toast.makeText(this, "Resuming from ${formatTime(resumeAt)}", Toast.LENGTH_LONG).show()
        }
        thumbTaken = prev?.thumb?.let { File(it).exists() } == true
        RecentStore.save(
            this,
            Recent(key, title, link, direct, if (nearEnd) 0 else resumeAt, prev?.durationMs ?: 0, prev?.thumb)
        )

        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        player = p
        playerView.requestFocus()
    }

    /** Remembers where playback is; optionally grabs the current frame as the list picture. */
    private fun saveProgress(takePicture: Boolean) {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        var thumb = RecentStore.get(this, key)?.thumb
        if (takePicture) {
            captureFrame()?.let { thumb = it; thumbTaken = true }
        }
        RecentStore.save(this, Recent(key, title, link, direct, pos, dur, thumb))
    }

    private fun captureFrame(): String? = runCatching {
        val tv = playerView.videoSurfaceView as? TextureView ?: return null
        val bmp = tv.getBitmap(480, 270) ?: return null
        val f = RecentStore.thumbFile(this, key)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        bmp.recycle()
        f.absolutePath
    }.getOrNull()

    /** Side-loaded subtitle tracks. An English one (or else the first) is switched on by default. */
    private fun buildSubtitles(files: List<File>): List<MediaItem.SubtitleConfiguration> {
        if (files.isEmpty()) return emptyList()
        val langs = files.map { guessLanguage(it.name) }
        val defaultIdx = langs.indexOf("en").takeIf { it >= 0 } ?: 0
        return files.mapIndexed { i, f ->
            val mime = when (f.extension.lowercase()) {
                "vtt" -> MimeTypes.TEXT_VTT
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                .setMimeType(mime)
                .setLanguage(langs[i])
                .setLabel(f.nameWithoutExtension)
                .setSelectionFlags(if (i == defaultIdx) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
    }

    private fun guessLanguage(name: String): String? {
        val tokens = name.lowercase().split('.', '_', '-', ' ', '[', ']', '(', ')')
        val map = mapOf(
            "en" to "en", "eng" to "en", "english" to "en",
            "ar" to "ar", "ara" to "ar", "arabic" to "ar",
            "hi" to "hi", "hin" to "hi", "hindi" to "hi",
            "fr" to "fr", "fre" to "fr", "french" to "fr",
            "es" to "es", "spa" to "es", "spanish" to "es",
            "de" to "de", "ger" to "de", "german" to "de"
        )
        return tokens.firstNotNullOfOrNull { map[it] }
    }

    private fun showError(msg: String) {
        loadingBox.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        loadingText.text = "$msg\n\nPress Back to return."
    }

    override fun onPause() {
        super.onPause()
        player?.let { p ->
            if (p.currentPosition > 5_000) saveProgress(takePicture = true)
            p.pause()
        }
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (!direct) Thread { TorrentEngine.stop() }.start()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MAGNET = "magnet"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}
