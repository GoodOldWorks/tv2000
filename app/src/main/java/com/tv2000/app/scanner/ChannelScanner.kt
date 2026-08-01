package com.tv2000.app.scanner

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tv2000.app.model.ScannedChannel
import com.tv2000.app.model.ScannedEpisode
import com.tv2000.app.storage.UsbStorageResolver
import com.tv2000.app.smb.SmbMediaUri
import com.tv2000.app.smb.SmbResource
import com.tv2000.app.smb.SmbjMediaClient
import com.tv2000.app.smb.classifySmbFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ChannelScanner(
    private val smbClient: SmbjMediaClient? = null,
    private val smbResourceProvider: suspend (Uri) -> SmbResource? = { null },
) {
    suspend fun scan(context: Context, rootUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        if (SmbMediaUri.isSmb(rootUri)) {
            val client = smbClient
                ?: return@withContext ScanResult.Failure(ScanFailure.UNAVAILABLE)
            val resource = smbResourceProvider(rootUri)
                ?: return@withContext ScanResult.Failure(ScanFailure.PERMISSION_LOST)
            return@withContext try {
                ScanResult.Success(client.scanChannels(resource))
            } catch (error: Exception) {
                val failure = classifySmbFailure(error)
                Log.e(TAG, "SMB scan failed: ${failure.diagnostic}")
                ScanResult.Failure(
                    reason = failure.reason,
                    diagnostic = failure.diagnostic,
                )
            }
        }

        if (UsbStorageResolver.isMediaStoreRoot(rootUri)) {
            return@withContext scanMediaStore(context, rootUri)
        }

        scanDocumentUri(context, rootUri)
    }

    private fun scanDocumentUri(context: Context, rootUri: Uri): ScanResult =
        try {
            val root = if (rootUri.scheme == "file") {
                rootUri.path?.let { path -> DocumentFile.fromFile(File(path)) }
            } else {
                DocumentFile.fromTreeUri(context, rootUri)
            }
                ?: return ScanResult.Failure(ScanFailure.UNAVAILABLE)

            scanDocumentRoot(root)
        } catch (_: SecurityException) {
            ScanResult.Failure(ScanFailure.PERMISSION_LOST)
        } catch (_: Exception) {
            ScanResult.Failure(ScanFailure.UNAVAILABLE)
        }

    private fun scanDocumentRoot(root: DocumentFile): ScanResult {
        if (!root.exists() || !root.isDirectory) {
            return ScanResult.Failure(ScanFailure.UNAVAILABLE)
        }

        val candidates = root.listFiles()
            .asSequence()
            .filter { it.isDirectory }
            .filterNot { it.name.orEmpty().startsWith(".") }
            .mapNotNull(::scanChannel)
            .sortedWith { left, right ->
                NaturalOrderComparator.compare(left.name, right.name)
            }
            .toList()

        return ScanResult.Success(candidates)
    }

    private fun scanChannel(directory: DocumentFile): ScannedChannel? {
        val relativePath = directory.name?.trim().orEmpty()
        if (relativePath.isEmpty()) return null

        val episodes = directory.listFiles()
            .asSequence()
            .filter { it.isFile }
            .filterNot { it.name.orEmpty().startsWith(".") }
            .filter { file ->
                val fileName = file.name.orEmpty()
                isSupportedVideoFile(fileName) && file.length() > 0L
            }
            .map { file ->
                val fileName = file.name.orEmpty()
                ScannedEpisode(
                    relativePath = fileName,
                    title = fileName.substringBeforeLast('.', missingDelimiterValue = fileName),
                    uri = file.uri,
                    sizeBytes = file.length(),
                    modifiedAt = file.lastModified(),
                )
            }
            .sortedWith { left, right ->
                NaturalOrderComparator.compare(left.title, right.title)
            }
            .toList()

        if (episodes.isEmpty()) return null

        return ScannedChannel(
            relativePath = relativePath,
            name = displayChannelName(relativePath),
            sourceUri = directory.uri,
            episodes = episodes,
        )
    }

    private fun scanMediaStore(
        context: Context,
        rootUri: Uri,
    ): ScanResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ScanResult.Failure(ScanFailure.UNAVAILABLE)
        }

        val volumeName = UsbStorageResolver.mediaStoreVolumeName(rootUri)
            ?: return ScanResult.Failure(ScanFailure.UNAVAILABLE)
        val collectionUri = MediaStore.Video.Media.getContentUri(volumeName)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
        )

        return try {
            val records = buildList {
                context.contentResolver.query(
                    collectionUri,
                    projection,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.Video.Media.DISPLAY_NAME,
                    )
                    val pathColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.Video.Media.RELATIVE_PATH,
                    )
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val modifiedColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.Video.Media.DATE_MODIFIED,
                    )

                    while (cursor.moveToNext()) {
                        add(
                            MediaStoreVideoRecord(
                                id = cursor.getLong(idColumn),
                                displayName = cursor.getString(nameColumn).orEmpty(),
                                relativePath = cursor.getString(pathColumn).orEmpty(),
                                sizeBytes = cursor.getLong(sizeColumn),
                                modifiedAtSeconds = cursor.getLong(modifiedColumn),
                            ),
                        )
                    }
                }
            }

            val channels = indexMediaStoreVideos(records).map { indexedChannel ->
                ScannedChannel(
                    relativePath = indexedChannel.relativePath,
                    name = indexedChannel.name,
                    sourceUri = rootUri.buildUpon()
                        .appendPath(indexedChannel.relativePath)
                        .build(),
                    episodes = indexedChannel.episodes.map { record ->
                        ScannedEpisode(
                            relativePath = record.displayName,
                            title = record.displayName.substringBeforeLast(
                                '.',
                                missingDelimiterValue = record.displayName,
                            ),
                            uri = ContentUris.withAppendedId(collectionUri, record.id),
                            sizeBytes = record.sizeBytes,
                            modifiedAt = record.modifiedAtSeconds * 1_000L,
                        )
                    },
                )
            }

            if (channels.isEmpty()) {
                val fallbackResult = UsbStorageResolver.fallbackFilePath(rootUri)
                    ?.let(::File)
                    ?.let(DocumentFile::fromFile)
                    ?.let(::scanDocumentRoot)

                if (fallbackResult is ScanResult.Success &&
                    fallbackResult.channels.isNotEmpty()
                ) {
                    return fallbackResult
                }
            }

            ScanResult.Success(channels)
        } catch (_: SecurityException) {
            ScanResult.Failure(ScanFailure.PERMISSION_LOST)
        } catch (_: Exception) {
            ScanResult.Failure(ScanFailure.UNAVAILABLE)
        }
    }
}

sealed interface ScanResult {
    data class Success(val channels: List<ScannedChannel>) : ScanResult
    data class Failure(
        val reason: ScanFailure,
        val diagnostic: String? = null,
    ) : ScanResult
}

enum class ScanFailure {
    PERMISSION_LOST,
    UNAVAILABLE,
    SMB_AUTHENTICATION,
    SMB_SHARE_NOT_FOUND,
    SMB_CONNECTION,
    SMB_PROTOCOL,
    SMB_UNKNOWN,
}

private const val TAG = "TV2000-SMB"
