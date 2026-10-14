package mv.magnettv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.RandomAccessFile

/** ExoPlayer data source that reads the partially-downloaded file, waiting for pieces as needed. */
class TorrentDataSource : BaseDataSource(true) {

    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var stream: TorrentEngine.StreamFile? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    @Volatile private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        val sf = TorrentEngine.stream ?: throw IOException("No active stream")
        stream = sf
        uri = dataSpec.uri
        closed = false
        transferInitializing(dataSpec)

        position = dataSpec.position
        if (position > sf.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else sf.size - position

        if (remaining > 0) {
            TorrentEngine.waitForBytes(sf, position) { closed }
            raf = RandomAccessFile(sf.file, "r")
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val sf = stream ?: throw IOException("Stream closed")
        val f = raf ?: throw IOException("Stream closed")

        TorrentEngine.waitForBytes(sf, position) { closed }
        val toRead = minOf(length.toLong(), remaining, TorrentEngine.pieceEnd(sf, position) - position).toInt()

        f.seek(position)
        val n = f.read(buffer, offset, toRead)
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        uri = null
        try {
            raf?.close()
        } finally {
            raf = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
