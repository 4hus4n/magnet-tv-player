package mv.magnettv

import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

/** Tiny web page on the TV so a magnet link can be pasted from a phone on the same network. */
class LinkServer(port: Int, private val onMagnet: (String) -> Unit) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            session.parseBody(HashMap())
            val raw = session.parameters["magnet"]?.firstOrNull()?.trim().orEmpty()
            return when {
                normalizeMagnet(raw) != null -> {
                    onMagnet(raw)
                    page("<h2>✓ Sent to TV</h2><p>Playback is starting on the TV.</p><a href='/'>Send another</a>")
                }
                PageScraper.isPageUrl(raw) -> {
                    onMagnet(raw)
                    page("<h2>✓ Sent to TV</h2><p>The TV is opening that link. If the page has several videos, pick one on the TV.</p><a href='/'>Send another</a>")
                }
                else -> page("<h2>That's not a magnet link or web address</h2><a href='/'>Try again</a>")
            }
        }
        return page(
            """
            <h2>Magnet Player</h2>
            <form method='post'>
              <textarea name='magnet' placeholder='Paste a magnet link, a video link, or a web page that has one' autofocus></textarea>
              <button type='submit'>Play on TV</button>
            </form>
            """
        )
    }

    private fun page(body: String): Response = NanoHTTPD.newFixedLengthResponse(
        Response.Status.OK, "text/html; charset=utf-8",
        """
        <!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>
        <title>Magnet Player</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0f1115;color:#eee;margin:0;padding:24px}
          textarea{width:100%;box-sizing:border-box;height:140px;font-size:15px;padding:12px;border-radius:10px;border:1px solid #333;background:#1a1d24;color:#eee}
          button{margin-top:14px;width:100%;padding:16px;font-size:17px;border:0;border-radius:10px;background:#14b8a6;color:#001;font-weight:600}
          a{color:#2dd4bf}
        </style></head><body>$body</body></html>
        """.trimIndent()
    )

    companion object {
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}

private val EXTRA_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker.dler.org:6969/announce"
)

/** Accepts a magnet link or a bare info-hash; adds a few public trackers to help find peers. */
fun normalizeMagnet(raw: String): String? {
    val s = raw.trim()
    val base = when {
        s.startsWith("magnet:?", ignoreCase = true) -> s
        s.matches(Regex("^[0-9a-fA-F]{40}$")) || s.matches(Regex("^[A-Za-z2-7]{32}$")) -> "magnet:?xt=urn:btih:$s"
        else -> return null
    }
    val sb = StringBuilder(base)
    for (tr in EXTRA_TRACKERS) {
        val enc = java.net.URLEncoder.encode(tr, "UTF-8")
        if (!base.contains(enc) && !base.contains(tr)) sb.append("&tr=").append(enc)
    }
    return sb.toString()
}
