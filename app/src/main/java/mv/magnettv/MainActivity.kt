package mv.magnettv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var phoneHint: TextView
    private lateinit var qrImage: ImageView
    private var server: LinkServer? = null
    private var searching = false
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.magnet_input)
        phoneHint = findViewById(R.id.phone_hint)
        qrImage = findViewById(R.id.qr_image)
        input.setText(prefs.getString("last", ""))

        val play = findViewById<Button>(R.id.btn_play)
        play.setOnClickListener { play(input.text.toString()) }
        findViewById<Button>(R.id.btn_paste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { input.setText("") }
        play.requestFocus()

        CrashLog.install(this)
        CrashLog.consume(this)?.let { report ->
            AlertDialog.Builder(this)
                .setTitle("The app closed unexpectedly last time")
                .setMessage("Please send a photo of this to the developer:\n\n$report")
                .setPositiveButton("OK", null)
                .show()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let {
                input.setText(it)
                play(it)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showRecents()
        val s = LinkServer(PORT) { link -> runOnUiThread { input.setText(link); play(link) } }
        try {
            s.start()
            server = s
            val ip = LinkServer.localIp()
            if (ip != null) {
                val url = "http://$ip:$PORT"
                qrImage.setImageBitmap(makeQr(url, 512))
                qrImage.visibility = View.VISIBLE
                phoneHint.text = "Same Wi-Fi as the TV\n$url"
            } else {
                qrImage.visibility = View.GONE
                phoneHint.text = "Connect the TV to Wi-Fi or LAN to send links from your phone."
            }
        } catch (e: Exception) {
            qrImage.visibility = View.GONE
            phoneHint.text = "Phone link page unavailable (port $PORT busy)."
        }
    }

    override fun onPause() {
        super.onPause()
        server?.stop()
        server = null
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) toast("Clipboard is empty") else input.setText(text.trim())
    }

    private fun play(raw: String) {
        val text = raw.trim()
        if (PageScraper.isPageUrl(text)) {
            prefs.edit().putString("last", text).apply()
            openWebAddress(text)
            return
        }
        val link = normalizeMagnet(text)
        if (link == null) {
            toast("Enter a magnet link, a video link or a web page address")
            return
        }
        prefs.edit().putString("last", text).apply()
        startTorrent(link)
    }

    private fun startTorrent(link: String, title: String? = null) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_MAGNET, link)
                .apply { title?.let { putExtra(PlayerActivity.EXTRA_TITLE, it) } }
        )
    }

    private fun startDirect(url: String, title: String? = null) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, url)
                .apply { title?.let { putExtra(PlayerActivity.EXTRA_TITLE, it) } }
        )
    }

    /** A video link plays directly; any other page is searched for magnet links. */
    private fun openWebAddress(url: String) {
        if (PageScraper.looksLikeVideoUrl(url)) {
            startDirect(url)
            return
        }
        if (searching) return
        searching = true
        toast("Opening link…")
        Thread {
            val result = runCatching { PageScraper.probe(url) }
            runOnUiThread {
                searching = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onFailure { toast("Couldn't open the link: ${it.message}") }
                result.onSuccess { probe ->
                    when (probe) {
                        is PageScraper.Probe.Video -> startDirect(probe.url, RecentStore.titleFromUrl(url))
                        is PageScraper.Probe.Page -> {
                            val found = probe.found
                            when {
                                found.isEmpty() -> toast("No video or magnet link found on that page")
                                found.size == 1 -> normalizeMagnet(found[0].uri)?.let { startTorrent(it) }
                                else -> AlertDialog.Builder(this)
                                    .setTitle("${found.size} videos found — pick one")
                                    .setItems(found.map { it.name }.toTypedArray()) { _, i ->
                                        normalizeMagnet(found[i].uri)?.let { startTorrent(it) }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }
                }
            }
        }.start()
    }

    // ---------- Recently watched ----------

    private fun showRecents() {
        val row = findViewById<LinearLayout>(R.id.recent_row)
        val label = findViewById<View>(R.id.recent_label)
        val scroll = findViewById<View>(R.id.recent_scroll)
        row.removeAllViews()
        val items = RecentStore.all(this)
        val show = if (items.isEmpty()) View.GONE else View.VISIBLE
        label.visibility = show
        scroll.visibility = show
        items.forEach { row.addView(recentCard(it)) }
    }

    private fun recentCard(r: Recent): View {
        val w = dp(200)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(5), dp(5), dp(5), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(14) }
        }
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, dp(112))
            scaleType = ImageView.ScaleType.CENTER_CROP
            val bmp = r.thumb?.takeIf { File(it).exists() }?.let { BitmapFactory.decodeFile(it) }
            if (bmp != null) setImageBitmap(bmp) else setImageResource(R.drawable.banner)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(w, dp(3))
            max = 1000
            progress = if (r.durationMs > 0) ((r.positionMs * 1000) / r.durationMs).toInt() else 0
            progressTintList = ColorStateList.valueOf(Color.parseColor("#2DD4BF"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#333844"))
            visibility = if (r.durationMs > 0) View.VISIBLE else View.INVISIBLE
        }
        val name = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            text = r.title.ifBlank { "Untitled" }
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            text = buildString {
                if (r.durationMs > 0) append(PlayerActivity.formatTime(r.positionMs))
                    .append(" / ").append(PlayerActivity.formatTime(r.durationMs)).append("  ·  ")
                append(if (r.direct) "Link" else "Torrent")
            }
            setTextColor(Color.parseColor("#9AA3AF"))
            textSize = 12f
            maxLines = 1
        }
        card.addView(img); card.addView(bar); card.addView(name); card.addView(sub)
        card.setOnClickListener {
            if (r.direct) startDirect(r.link, r.title) else startTorrent(r.link, r.title)
        }
        card.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(r.title)
                .setItems(arrayOf("Play", "Remove from list")) { _, which ->
                    if (which == 0) card.performClick()
                    else { RecentStore.remove(this, r.key); showRecents() }
                }
                .show()
            true
        }
        return card
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun makeQr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val PORT = 8080
    }
}
