package com.tv2000.app.smb

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.hierynomus.protocol.transport.TransportException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

@OptIn(UnstableApi::class)
class Tv2000DataSource private constructor(
    private val defaultDataSource: DataSource,
    private val smbDataSource: DataSource,
    private val onSourceOpening: (Uri) -> Unit,
) : DataSource {
    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        defaultDataSource.addTransferListener(transferListener)
        smbDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(activeDataSource == null) { "DataSource is already open" }
        onSourceOpening(dataSpec.uri)
        val selected = if (SmbMediaUri.isSmb(dataSpec.uri)) smbDataSource else defaultDataSource
        activeDataSource = selected
        return try {
            selected.open(dataSpec)
        } catch (error: Exception) {
            activeDataSource = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(activeDataSource) { "DataSource is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = activeDataSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        activeDataSource?.responseHeaders.orEmpty()

    override fun close() {
        val selected = activeDataSource
        activeDataSource = null
        selected?.close()
    }

    class Factory(
        context: Context,
        smbClient: SmbjMediaClient,
        resourceProvider: (Uri) -> SmbResource?,
        private val onSourceOpening: (Uri) -> Unit,
    ) : DataSource.Factory {
        private val defaultFactory = DefaultDataSource.Factory(context)
        private val smbFactory = SmbDataSource.Factory(smbClient, resourceProvider)

        override fun createDataSource(): DataSource = Tv2000DataSource(
            defaultDataSource = defaultFactory.createDataSource(),
            smbDataSource = smbFactory.createDataSource(),
            onSourceOpening = onSourceOpening,
        )
    }
}

@OptIn(UnstableApi::class)
private class SmbDataSource(
    private val smbClient: SmbjMediaClient,
    private val resourceProvider: (Uri) -> SmbResource?,
) : BaseDataSource(false) {
    private var openedUri: Uri? = null
    private var openedFile: SmbOpenFile? = null
    private var openedResource: SmbResource? = null
    private var readPosition = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private val readBuffer = RandomAccessReadBuffer()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val resource = resourceProvider(dataSpec.uri)
            ?: throw IOException("SMB resource is not configured")
        val relativePath = SmbMediaUri.relativePath(dataSpec.uri)
            ?: throw IOException("Invalid TV2000 SMB URI")
        val file = try {
            smbClient.open(resource, relativePath)
        } catch (error: Exception) {
            if (isRetryableSmbTransportFailure(error)) {
                smbClient.invalidateConnections(resource)
            }
            throw error.asSmbIOException("SMB open failed")
        }
        if (dataSpec.position > file.length) {
            file.close()
            throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
        }

        openedUri = dataSpec.uri
        openedFile = file
        openedResource = resource
        readBuffer.reset()
        readPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            file.length - dataSpec.position
        } else {
            minOf(dataSpec.length, file.length - dataSpec.position)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requestedLength = minOf(length.toLong(), bytesRemaining).toInt()
        val file = openedFile ?: throw IOException("SMB file is not open")
        val bytesRead = try {
            readBuffer.read(
                position = readPosition,
                destination = buffer,
                destinationOffset = offset,
                requestedLength = requestedLength,
                availableLength = bytesRemaining,
                source = RandomAccessSource(file::read),
            )
        } catch (error: Exception) {
            if (isRetryableSmbTransportFailure(error)) {
                openedResource?.let(smbClient::invalidateConnections)
            }
            throw error.asSmbIOException("SMB read failed")
        }
        if (bytesRead < 0) {
            throw EOFException("SMB file ended before the advertised length")
        }
        if (bytesRead == 0) {
            throw IOException("SMB server returned no data before end of file")
        }

        readPosition += bytesRead
        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        openedUri = null
        openedFile?.close()
        openedFile = null
        openedResource = null
        readBuffer.reset()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(
        private val smbClient: SmbjMediaClient,
        private val resourceProvider: (Uri) -> SmbResource?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(smbClient, resourceProvider)
    }
}

internal fun isRetryableSmbTransportFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { cause ->
        cause.cause.takeUnless { it === cause }
    }.take(MAX_SMB_CAUSE_DEPTH).toList()
    if (causes.any { cause -> cause is InterruptedException }) return false
    return causes.any { cause ->
        cause is SocketTimeoutException ||
            cause is SocketException ||
            cause is TransportException
    }
}

private fun Exception.asSmbIOException(message: String): IOException =
    this as? IOException ?: IOException(message, this)

private const val MAX_SMB_CAUSE_DEPTH = 12
