package mv.magnettv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Opens a web page and pulls out the magnet links on it. */
object PageScraper {

    data class Found(val uri: String, val name: String)

    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val MAGNET = Regex("""magnet:\?[^\s"'<>`]+""", RegexOption.IGNORE_CASE)
    private val HASH = Regex("""btih:([0-9a-zA-Z]{32,40})""", RegexOption.IGNORE_CASE)
    private val NAME = Regex("""[?&]dn=([^&]+)""")

    fun isPageUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    /** Blocking. Returns unique magnet links found on the page, in page order. */
    fun findMagnets(pageUrl: String): List<Found> {
        val html = fetch(pageUrl)
        val out = LinkedHashMap<String, Found>()
        collect(html, out)
        // Some sites put the link URL-encoded inside another link (magnet%3A%3Fxt%3D...).
        if (out.isEmpty() && html.contains("magnet%3A", ignoreCase = true)) {
            runCatching { URLDecoder.decode(html, "UTF-8") }.getOrNull()?.let { collect(it, out) }
        }
        return out.values.toList()
    }

    private fun collect(text: String, out: MutableMap<String, Found>) {
        for (m in MAGNET.findAll(text)) {
            val uri = decodeEntities(m.value)
            val hash = HASH.find(uri)?.groupValues?.get(1)?.lowercase() ?: continue
            if (hash in out) continue
            val name = NAME.find(uri)?.groupValues?.get(1)
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "Torrent ${hash.take(8)}"
            out[hash] = Found(uri, name)
        }
    }

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&#x26;", "&")
        .replace("&#X26;", "&")

    private fun fetch(start: String): String {
        var url = URL(start)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "text/html,*/*")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                    url = URL(url, loc)
                    return@repeat
                }
                if (code !in 200..299) throw IOException("Page returned HTTP $code")
                c.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    val bytes = java.io.ByteArrayOutputStream()
                    while (bytes.size() < MAX_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes.write(buf, 0, n)
                    }
                    return bytes.toString("UTF-8")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}
