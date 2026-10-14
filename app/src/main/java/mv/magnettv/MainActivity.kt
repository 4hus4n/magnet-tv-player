package mv.magnettv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

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
            findOnPage(text)
            return
        }
        val link = normalizeMagnet(text)
        if (link == null) {
            toast("Enter a magnet link or a web page address")
            return
        }
        prefs.edit().putString("last", text).apply()
        startPlayer(link)
    }

    private fun startPlayer(link: String) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_MAGNET, link))
    }

    /** Opens the page in the background, then plays its magnet link or asks which one. */
    private fun findOnPage(url: String) {
        if (searching) return
        searching = true
        toast("Looking for magnet links on the page…")
        Thread {
            val result = runCatching { PageScraper.findMagnets(url) }
            runOnUiThread {
                searching = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onFailure { toast("Couldn't open the page: ${it.message}") }
                result.onSuccess { found ->
                    when {
                        found.isEmpty() -> toast("No magnet link found on that page")
                        found.size == 1 -> normalizeMagnet(found[0].uri)?.let { startPlayer(it) }
                        else -> AlertDialog.Builder(this)
                            .setTitle("${found.size} videos found — pick one")
                            .setItems(found.map { it.name }.toTypedArray()) { _, i ->
                                normalizeMagnet(found[i].uri)?.let { startPlayer(it) }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }.start()
    }

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
