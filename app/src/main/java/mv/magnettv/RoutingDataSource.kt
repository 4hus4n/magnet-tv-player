package mv.magnettv

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/**
 * Sends the streaming video file through [TorrentDataSource] (waits for pieces)
 * and everything else (fully downloaded subtitle files) through the normal file reader.
 */
class RoutingDataSource(context: Context) : DataSource {

    private val torrent = TorrentDataSource()
    private val normal = DefaultDataSource.Factory(context).createDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        torrent.addTransferListener(transferListener)
        normal.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val videoUri = TorrentEngine.stream?.file?.let { Uri.fromFile(it) }
        val ds = if (dataSpec.uri == videoUri) torrent else normal
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IllegalStateException("Not opened")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
