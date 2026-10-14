package mv.magnettv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Works out what a web address is: a video to play directly, or a page to search for magnet links. */
object PageScraper {

    data class Found(val uri: String, val name: String)

    sealed class Probe {
        data class Video(val url: String) : Probe()
        data class Page(val found: List<Found>) : Probe()
    }

    private const val MAX_BYTES = 3 * 1024 * 1024
    const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val VIDEO_EXT = listOf(
        ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".mpg", ".mpeg", ".flv", ".wmv", ".m3u8"
    )

    private val MAGNET = Regex("""magnet:\?[^\s"'<>`]+""", RegexOption.IGNORE_CASE)
    private val HASH = Regex("""btih:([0-9a-zA-Z]{32,40})""", RegexOption.IGNORE_CASE)
    private val NAME = Regex("""[?&]dn=([^&]+)""")

    fun isPageUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    /** True when the address itself ends in a video file name. */
    fun looksLikeVideoUrl(url: String): Boolean {
        val path = runCatching { URL(url).path.lowercase() }.getOrDefault(url.lowercase())
        return VIDEO_EXT.any { path.endsWith(it) }
    }

    /** Blocking. A video link is returned as-is; a web page is searched for magnet links. */
    fun probe(url: String): Probe {
        if (looksLikeVideoUrl(url)) return Probe.Video(url)
        val r = fetch(url)
        val body = r.body ?: return Probe.Video(r.finalUrl)
        val out = LinkedHashMap<String, Found>()
        collect(body, out)
        // Some sites put the link URL-encoded inside another link (magnet%3A%3Fxt%3D...).
        if (out.isEmpty() && body.contains("magnet%3A", ignoreCase = true)) {
            runCatching { URLDecoder.decode(body, "UTF-8") }.getOrNull()?.let { collect(it, out) }
        }
        return Probe.Page(out.values.toList())
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

    /** body == null means the address serves a video, not a page. */
    private class FetchResult(val finalUrl: String, val body: String?)

    private fun fetch(start: String): FetchResult {
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

                val type = c.contentType?.lowercase().orEmpty()
                val disposition = c.getHeaderField("Content-Disposition")?.lowercase().orEmpty()
                val isVideo = type.startsWith("video/") ||
                    type.contains("mpegurl") ||
                    (type.contains("octet-stream") &&
                        (looksLikeVideoUrl(url.toString()) || VIDEO_EXT.any { disposition.contains(it) }))
                if (isVideo) return FetchResult(url.toString(), null)

                c.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    val bytes = java.io.ByteArrayOutputStream()
                    while (bytes.size() < MAX_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes.write(buf, 0, n)
                    }
                    return FetchResult(url.toString(), bytes.toString("UTF-8"))
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}
