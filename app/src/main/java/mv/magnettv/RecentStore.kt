package mv.magnettv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLDecoder

/** One item in the "Recently watched" row. */
data class Recent(
    val key: String,
    val title: String,
    val link: String,
    val direct: Boolean,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val thumb: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Recently watched list, kept on the TV (newest first, up to 20). */
object RecentStore {
    private const val PREFS = "recents"
    private const val ITEMS = "items"
    private const val MAX = 20

    private val VIDEO_EXT = listOf(
        ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".mpg", ".mpeg", ".flv", ".wmv", ".m3u8"
    )

    /** Same video = same key: the info-hash for magnets, the address for direct links. */
    fun keyFor(link: String): String =
        Regex("btih:([0-9a-zA-Z]{32,40})", RegexOption.IGNORE_CASE).find(link)
            ?.groupValues?.get(1)?.lowercase() ?: link

    fun all(ctx: Context): List<Recent> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ITEMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Recent(
                    key = o.getString("key"),
                    title = o.optString("title"),
                    link = o.getString("link"),
                    direct = o.optBoolean("direct"),
                    positionMs = o.optLong("pos"),
                    durationMs = o.optLong("dur"),
                    thumb = o.optString("thumb").ifBlank { null },
                    updatedAt = o.optLong("at")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(ctx: Context, key: String): Recent? = all(ctx).firstOrNull { it.key == key }

    @Synchronized
    fun save(ctx: Context, r: Recent) {
        val list = listOf(r.copy(updatedAt = System.currentTimeMillis())) + all(ctx).filter { it.key != r.key }
        list.drop(MAX).forEach { old -> old.thumb?.let { File(it).delete() } }
        write(ctx, list.take(MAX))
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        val list = all(ctx)
        list.filter { it.key == key }.forEach { it.thumb?.let { t -> File(t).delete() } }
        write(ctx, list.filter { it.key != key })
    }

    fun thumbFile(ctx: Context, key: String): File {
        val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
        return File(dir, Integer.toHexString(key.hashCode()) + ".jpg")
    }

    private fun write(ctx: Context, list: List<Recent>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("key", r.key); put("title", r.title); put("link", r.link); put("direct", r.direct)
                put("pos", r.positionMs); put("dur", r.durationMs); put("thumb", r.thumb ?: ""); put("at", r.updatedAt)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ITEMS, arr.toString()).apply()
    }

    /** "Buddy.2026.1080p.WEBRip.x265.mkv" → "Buddy (2026)" */
    fun cleanTitle(raw: String): String {
        var t = raw.trim()
        VIDEO_EXT.firstOrNull { t.lowercase().endsWith(it) }?.let { t = t.dropLast(it.length) }
        t = t.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()
        val cut = Regex(
            """(?i)(\b\d{3,4}p\b|\bweb[ -]?rip\b|\bweb[ -]?dl\b|\bblu[ -]?ray\b|\bbrrip\b|\bhdrip\b|\bdvdrip\b|\bhdtv\b|\bx26[45]\b|\bh ?26[45]\b|\bhevc\b|\b10bit\b|\bddp?\d|\baac\b|\[)"""
        ).find(t)
        if (cut != null && cut.range.first > 1) t = t.substring(0, cut.range.first)
        t = t.trim().trimEnd('-', ' ', '(', '[')
        Regex("""^(.*\S)\s+((?:19|20)\d{2})$""").find(t)?.let { m ->
            t = "${m.groupValues[1]} (${m.groupValues[2]})"
        }
        return t.ifBlank { raw }
    }

    /** Title for a direct link: its file name, tidied up. */
    fun titleFromUrl(url: String): String {
        val name = runCatching { URL(url).path.substringAfterLast('/') }.getOrDefault("")
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        return if (name.isBlank()) url else cleanTitle(name)
    }
}
