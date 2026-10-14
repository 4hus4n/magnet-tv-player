package mv.magnettv

import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Single active torrent stream. Downloads only the largest video file,
 * sequentially, and lets the player block until the bytes it needs arrive.
 */
object TorrentEngine {

    data class StreamFile(
        val file: File,
        val name: String,
        val size: Long,
        val offsetInTorrent: Long,
        val pieceLength: Int,
        val firstPiece: Int,
        val lastPiece: Int,
        val saveDir: File,
        val subtitles: List<File> = emptyList()
    )

    private val VIDEO_EXT = listOf(
        ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".mpg", ".mpeg", ".flv", ".wmv"
    )

    val SUB_EXT = listOf(".srt", ".vtt", ".ass", ".ssa")
    private const val MAX_SUB_SIZE = 5L * 1024 * 1024
    private const val SUB_WAIT_MS = 20_000L

    private const val READ_AHEAD_BYTES = 16L * 1024 * 1024
    private const val FETCH_TIMEOUT_S = 120

    private var session: SessionManager? = null
    @Volatile var stream: StreamFile? = null
        private set
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var addLatch = CountDownLatch(1)
    @Volatile private var generation = 0
    @Volatile private var lastWindowPiece = -1

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig())
        override fun alert(alert: Alert<*>) {
            if (alert is AddTorrentAlert) {
                handle = alert.handle()
                addLatch.countDown()
            }
        }
    }

    @Synchronized
    private fun session(): SessionManager {
        session?.let { return it }
        val sp = SettingsPack()
        sp.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        sp.setString(
            settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
            "dht.libtorrent.org:25401,router.bittorrent.com:6881,dht.transmissionbt.com:6881," +
                "router.utorrent.com:6881,dht.aelitis.com:6881"
        )
        sp.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:6881,[::]:6881")
        sp.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)
        sp.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)

        val s = SessionManager()
        s.addListener(listener)
        s.start(SessionParams(sp))
        session = s
        return s
    }

    /** Blocking. Call from a background thread. */
    fun open(magnet: String, saveDir: File, onStatus: (String) -> Unit): StreamFile {
        stop()
        val gen = generation
        saveDir.mkdirs()
        val s = session()

        // One torrent from start to finish: add the magnet once and wait for its file list on that
        // same torrent. (Fetching the info separately and re-adding it raced with the removal of the
        // temporary copy and could crash the torrent library.)
        val hash = magnetHash(magnet)
        waitUntilGone(s, hash)
        addLatch = CountDownLatch(1)
        handle = null
        s.download(magnet, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        if (!addLatch.await(30, TimeUnit.SECONDS)) throw IOException("Torrent failed to start.")
        val th = handle?.takeIf { it.isValid } ?: throw IOException("Torrent failed to start.")

        val started = System.currentTimeMillis()
        while (!th.status().hasMetadata()) {
            if (gen != generation || !th.isValid) throw InterruptedIOException("Cancelled")
            val secs = (System.currentTimeMillis() - started) / 1000
            val peers = th.status().numPeers()
            val nodes = runCatching { s.dhtNodes() }.getOrDefault(0L)
            if (secs >= FETCH_TIMEOUT_S) {
                throw IOException(
                    if (peers == 0 && nodes == 0L)
                        "Couldn't reach the torrent network. Check the TV's internet connection, or your network may block torrents."
                    else
                        "No one is sharing this torrent right now (no peers found). Try another link."
                )
            }
            onStatus(
                "Finding peers and fetching torrent info…\n" +
                    "Peers: $peers   ·   Network nodes: $nodes   ·   ${secs}s of $FETCH_TIMEOUT_S s"
            )
            Thread.sleep(500)
        }
        if (gen != generation) throw InterruptedIOException("Cancelled")

        val ti = th.torrentFile() ?: throw IOException("Couldn't read the torrent's file list.")
        val fs = ti.files()
        var best = -1
        var bestSize = 0L
        for (i in 0 until fs.numFiles()) {
            val n = fs.fileName(i).lowercase()
            if (VIDEO_EXT.any { n.endsWith(it) } && fs.fileSize(i) > bestSize) {
                best = i
                bestSize = fs.fileSize(i)
            }
        }
        if (best < 0) throw IOException("No playable video file in this torrent.")

        // Subtitle files that ship with the torrent (small, so download them fully).
        val subIdx = (0 until fs.numFiles()).filter { i ->
            val n = fs.fileName(i).lowercase()
            SUB_EXT.any { n.endsWith(it) } && fs.fileSize(i) in 1..MAX_SUB_SIZE
        }

        onStatus("Starting download: ${fs.fileName(best)}")

        th.prioritizeFiles(Array(fs.numFiles()) {
            if (it == best || it in subIdx) Priority.TOP_PRIORITY else Priority.IGNORE
        })
        th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

        val pl = ti.pieceLength()

        // Fetch subtitle files first (usually a few KB each).
        val subs = mutableListOf<File>()
        if (subIdx.isNotEmpty()) {
            onStatus("Getting subtitles…")
            val ranges = subIdx.associateWith { i ->
                val o = fs.fileOffset(i)
                (o / pl).toInt()..((o + fs.fileSize(i) - 1) / pl).toInt()
            }
            ranges.values.forEach { r -> r.forEach { p -> th.setPieceDeadline(p, 500) } }
            val until = System.currentTimeMillis() + SUB_WAIT_MS
            while (System.currentTimeMillis() < until &&
                ranges.values.any { r -> r.any { p -> !th.havePiece(p) } }
            ) {
                if (gen != generation) throw InterruptedIOException("Cancelled")
                Thread.sleep(150)
            }
            for ((i, r) in ranges) {
                if (r.all { th.havePiece(it) }) subs += File(saveDir, fs.filePath(i))
            }
        }

        val off = fs.fileOffset(best)
        val sf = StreamFile(
            file = File(saveDir, fs.filePath(best)),
            name = fs.fileName(best),
            size = bestSize,
            offsetInTorrent = off,
            pieceLength = pl,
            firstPiece = (off / pl).toInt(),
            lastPiece = ((off + bestSize - 1) / pl).toInt(),
            saveDir = saveDir,
            subtitles = subs.sortedBy { it.name.lowercase() }
        )
        stream = sf
        lastWindowPiece = -1

        // Start of file + last 2 pieces (MP4 index often sits at the end).
        setWindow(th, sf, sf.firstPiece)
        for (p in max(sf.firstPiece, sf.lastPiece - 1)..sf.lastPiece) th.setPieceDeadline(p, 1500)

        return sf
    }

    /** Info-hash of a magnet link, or null if it can't be parsed. */
    private fun magnetHash(uri: String): Sha1Hash? = runCatching {
        val ec = error_code()
        val p = libtorrent.parse_magnet_uri(uri, ec)
        if (ec.value() != 0) null else Sha1Hash(p.getInfo_hashes().get_best())
    }.getOrNull()

    /** A previous stream of the same torrent may still be shutting down; wait for it to go. */
    private fun waitUntilGone(s: SessionManager, hash: Sha1Hash?) {
        if (hash == null) return
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val old = runCatching { s.find(hash) }.getOrNull()
            if (old == null || !old.isValid) return
            runCatching { s.remove(old, SessionHandle.DELETE_FILES) }
            Thread.sleep(200)
        }
    }

    fun pieceAt(sf: StreamFile, pos: Long): Int = ((sf.offsetInTorrent + pos) / sf.pieceLength).toInt()

    /** Byte position (within the file) where the piece containing [pos] ends. */
    fun pieceEnd(sf: StreamFile, pos: Long): Long {
        val p = pieceAt(sf, pos)
        return min(sf.size, (p + 1).toLong() * sf.pieceLength - sf.offsetInTorrent)
    }

    /** Blocks until the piece holding [pos] is on disk. */
    fun waitForBytes(sf: StreamFile, pos: Long, cancelled: () -> Boolean) {
        val th = handle ?: throw IOException("Stream closed")
        val p = pieceAt(sf, pos)
        if (p != lastWindowPiece) setWindow(th, sf, p)
        while (!th.havePiece(p)) {
            if (cancelled() || stream !== sf || !th.isValid) throw InterruptedIOException("Stream closed")
            try {
                Thread.sleep(80)
            } catch (e: InterruptedException) {
                throw InterruptedIOException("Interrupted")
            }
        }
    }

    private fun setWindow(th: TorrentHandle, sf: StreamFile, start: Int) {
        lastWindowPiece = start
        val count = max(6, (READ_AHEAD_BYTES / sf.pieceLength).toInt())
        val end = min(sf.lastPiece, start + count)
        for ((i, p) in (start..end).withIndex()) {
            if (!th.havePiece(p)) th.setPieceDeadline(p, 800 + i * 150)
        }
    }

    fun statusText(): String {
        val th = handle ?: return ""
        if (!th.isValid) return ""
        val st = th.status()
        val rate = st.downloadPayloadRate()
        val speed = if (rate > 1024 * 1024) "%.1f MB/s".format(rate / 1048576f) else "${rate / 1024} KB/s"
        return "Peers ${st.numPeers()}   ·   $speed   ·   ${(st.progress() * 100).toInt()}% downloaded"
    }

    /** Removes the torrent and deletes its files. */
    fun stop() {
        generation++
        val th = handle
        val sf = stream
        stream = null
        handle = null
        try {
            if (th != null && th.isValid) session?.remove(th, SessionHandle.DELETE_FILES)
        } catch (_: Exception) {
        }
        sf?.saveDir?.deleteRecursively()
    }
}
