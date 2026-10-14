package mv.magnettv

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
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

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            val buffering = p == null || p.playbackState == Player.STATE_BUFFERING
            statusText.text = TorrentEngine.statusText()
            statusText.visibility = if (controllerVisible || buffering) View.VISIBLE else View.GONE
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

        val magnet = intent.getStringExtra(EXTRA_MAGNET)
        if (magnet == null) {
            finish()
            return
        }

        val dir = File(getExternalFilesDir(null) ?: filesDir, "stream")
        Thread {
            try {
                val sf = TorrentEngine.open(magnet, dir) { msg -> runOnUiThread { loadingText.text = msg } }
                if (!destroyed) runOnUiThread { startPlayer(sf) }
            } catch (e: Exception) {
                if (!destroyed) runOnUiThread { showError(e.message ?: "Couldn't start stream") }
            }
        }.start()

        handler.post(tick)
    }

    private fun startPlayer(sf: TorrentEngine.StreamFile) {
        if (destroyed) return
        loadingBox.visibility = View.GONE

        val subConfigs = buildSubtitles(sf.subtitles)
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(sf.file))
            .setSubtitleConfigurations(subConfigs)
            .build()
        val source = DefaultMediaSourceFactory(DataSource.Factory { RoutingDataSource(this) })
            .createMediaSource(item)

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
        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        player = p
        playerView.requestFocus()
    }

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
        player?.pause()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        Thread { TorrentEngine.stop() }.start()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MAGNET = "magnet"
    }
}
