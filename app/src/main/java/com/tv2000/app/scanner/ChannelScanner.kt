package com.tv2000.app.scanner

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

class ChannelScanner(
    private val smbClient: SmbjMediaClient? = null,
    private val smbResourceProvider: suspend (Uri) -> SmbResource? = { null },
) {
    suspend fun scan(
        context: Context,
        rootUri: Uri,
        usbVideoDirectory: String = UsbStorageResolver.DEFAULT_VIDEO_DIRECTORY,
        includeDirectFileSnapshot: Boolean = true,
    ): ScanResult = withContext(Dispatchers.IO) {
        if (SmbMediaUri.isSmb(rootUri)) {
            val client = smbClient
                ?: return@withContext ScanResult.Failure(ScanFailure.UNAVAILABLE)
            val resource = smbResourceProvider(rootUri)
                ?: return@withContext ScanResult.Failure(ScanFailure.PERMISSION_LOST)
            return@withContext try {
                ScanResult.Success(
                    channels = client.scanChannels(resource),
                    isAuthoritative = true,
                )
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
            return@withContext scanMediaStore(
                context = context,
                rootUri = rootUri,
                usbVideoDirectory = usbVideoDirectory,
                includeDirectFileSnapshot = includeDirectFileSnapshot,
            )
        }

        scanDocumentUri(context, rootUri, usbVideoDirectory)
    }

    suspend fun mediaStoreRevision(context: Context, rootUri: Uri): String? =
        withContext(Dispatchers.IO) {
            if (!UsbStorageResolver.isMediaStoreRoot(rootUri)) return@withContext null
            val volumeName = UsbStorageResolver.mediaStoreVolumeName(rootUri)
                ?: return@withContext null
            readMediaStoreRevision(context, volumeName)
        }

    private fun scanDocumentUri(
        context: Context,
        rootUri: Uri,
        usbVideoDirectory: String,
    ): ScanResult =
        try {
            if (rootUri.scheme == "file") {
                val storageRoot = rootUri.path?.let(::File)
                    ?: return ScanResult.Failure(ScanFailure.UNAVAILABLE)
                val videoRoot = resolveFileVideoRoot(storageRoot, usbVideoDirectory)
                    ?: return ScanResult.Failure(ScanFailure.UNAVAILABLE)
                return scanFileRoot(videoRoot)
            }

            val root = DocumentFile.fromTreeUri(context, rootUri)
                ?: return ScanResult.Failure(ScanFailure.UNAVAILABLE)
            scanDocumentRoot(resolveDocumentVideoRoot(root, usbVideoDirectory))
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

        return ScanResult.Success(
            channels = candidates,
            isAuthoritative = true,
        )
    }

    private fun scanChannel(directory: DocumentFile): ScannedChannel? {
        val relativePath = directory.name?.trim().orEmpty()
        if (relativePath.isEmpty()) return null

        val episodes = directory.listFiles()
            .asSequence()
            .mapNotNull { file ->
                val fileName = file.name.orEmpty()
                if (!file.isFile ||
                    fileName.startsWith('.') ||
                    !isSupportedVideoFile(fileName)
                ) {
                    return@mapNotNull null
                }
                val sizeBytes = file.length()
                if (sizeBytes <= 0L) return@mapNotNull null
                ScannedEpisode(
                    relativePath = fileName,
                    title = fileName.substringBeforeLast('.', missingDelimiterValue = fileName),
                    uri = file.uri,
                    sizeBytes = sizeBytes,
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

    private fun scanFileRoot(root: File): ScanResult {
        val rootAttributes = readAttributes(root)
            ?: return ScanResult.Failure(ScanFailure.UNAVAILABLE)
        if (!rootAttributes.isDirectory) {
            return ScanResult.Failure(ScanFailure.UNAVAILABLE)
        }
        val children = root.listFiles()
            ?: return ScanResult.Failure(ScanFailure.UNAVAILABLE)
        val channels = buildList {
            children.forEach { child ->
                if (child.name.startsWith('.')) return@forEach
                val attributes = readAttributes(child)
                    ?: return ScanResult.Failure(ScanFailure.UNAVAILABLE)
                if (!attributes.isDirectory) return@forEach

                when (val channel = scanFileChannel(child)) {
                    FileChannelScan.Empty -> Unit
                    FileChannelScan.Failure ->
                        return ScanResult.Failure(ScanFailure.UNAVAILABLE)
                    is FileChannelScan.Success -> add(channel.channel)
                }
            }
        }.sortedWith { left, right ->
            NaturalOrderComparator.compare(left.name, right.name)
        }
        return ScanResult.Success(
            channels = channels,
            isAuthoritative = true,
        )
    }

    private fun scanFileChannel(directory: File): FileChannelScan {
        val relativePath = directory.name.trim()
        if (relativePath.isEmpty()) return FileChannelScan.Empty
        val children = directory.listFiles() ?: return FileChannelScan.Failure
        val episodes = buildList {
            children.forEach { file ->
                if (file.name.startsWith('.') || !isSupportedVideoFile(file.name)) {
                    return@forEach
                }
                val attributes = readAttributes(file)
                    ?: return FileChannelScan.Failure
                if (!attributes.isRegularFile || attributes.size() <= 0L) {
                    return@forEach
                }
                add(
                    ScannedEpisode(
                        relativePath = file.name,
                        title = file.name.substringBeforeLast(
                            '.',
                            missingDelimiterValue = file.name,
                        ),
                        uri = Uri.fromFile(file),
                        sizeBytes = attributes.size(),
                        modifiedAt = attributes.lastModifiedTime().toMillis(),
                    ),
                )
            }
        }.sortedWith { left, right ->
            NaturalOrderComparator.compare(left.title, right.title)
        }
        if (episodes.isEmpty()) return FileChannelScan.Empty
        return FileChannelScan.Success(
            ScannedChannel(
                relativePath = relativePath,
                name = displayChannelName(relativePath),
                sourceUri = Uri.fromFile(directory),
                episodes = episodes,
            ),
        )
    }

    private fun readAttributes(file: File): BasicFileAttributes? = runCatching {
        Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    }.getOrNull()

    private fun scanMediaStore(
        context: Context,
        rootUri: Uri,
        usbVideoDirectory: String,
        includeDirectFileSnapshot: Boolean,
    ): ScanResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ScanResult.Failure(ScanFailure.UNAVAILABLE)
        }

        val volumeName = UsbStorageResolver.mediaStoreVolumeName(rootUri)
            ?: return ScanResult.Failure(ScanFailure.UNAVAILABLE)
        val fallbackRoot = UsbStorageResolver.fallbackFilePath(rootUri)
            ?.let(::File)
            ?.takeIf { root -> root.isDirectory && root.canRead() }
        if (includeDirectFileSnapshot && fallbackRoot != null) {
            val startedAt = SystemClock.elapsedRealtime()
            val directResult = resolveFileVideoRoot(fallbackRoot, usbVideoDirectory)
                ?.let(::scanFileRoot)
            if (directResult is ScanResult.Success) {
                Log.i(
                    SCAN_TAG,
                    "direct_file volume=${UsbStorageResolver.volumeIdentity(rootUri)} " +
                        "channels=${directResult.channels.size} " +
                        "episodes=${directResult.channels.sumOf { it.episodes.size }} " +
                        "duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
                )
                return directResult.copy(
                    sourceRevision = readMediaStoreRevision(context, volumeName),
                    isAuthoritative = true,
                )
            }
        }
        val collectionUri = MediaStore.Video.Media.getContentUri(volumeName)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
        )

        return try {
            val startedAt = SystemClock.elapsedRealtime()
            val revisionBefore = readMediaStoreRevision(context, volumeName)
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

            val effectiveDirectory = inferMediaStoreVideoDirectory(
                records,
                usbVideoDirectory,
            )
            val readableFileRoot = fallbackRoot?.takeIf(File::canRead)
            val mediaStoreChannels = indexMediaStoreVideos(
                records = records,
                rootDirectory = effectiveDirectory,
            ).map { indexedChannel ->
                val sourcePath = listOf(effectiveDirectory, indexedChannel.relativePath)
                    .filter(String::isNotEmpty)
                    .joinToString("/")
                ScannedChannel(
                    relativePath = indexedChannel.relativePath,
                    name = indexedChannel.name,
                    sourceUri = rootUri.buildUpon()
                        .appendPath(sourcePath)
                        .build(),
                    episodes = indexedChannel.episodes.map { record ->
                        ScannedEpisode(
                            relativePath = record.displayName,
                            title = record.displayName.substringBeforeLast(
                                '.',
                                missingDelimiterValue = record.displayName,
                            ),
                            uri = directMediaFileUri(
                                storageRoot = readableFileRoot,
                                relativePath = record.relativePath,
                                displayName = record.displayName,
                            ) ?: ContentUris.withAppendedId(collectionUri, record.id),
                            sizeBytes = record.sizeBytes,
                            modifiedAt = record.modifiedAtSeconds * 1_000L,
                        )
                    },
                )
            }
            val revisionAfter = readMediaStoreRevision(context, volumeName)
            val stableRevision = revisionAfter.takeIf { revision ->
                revisionBefore != null && revision == revisionBefore
            }
            Log.i(
                SCAN_TAG,
                "media_store volume=${UsbStorageResolver.volumeIdentity(rootUri)} " +
                    "records=${records.size} channels=${mediaStoreChannels.size} " +
                    "episodes=${mediaStoreChannels.sumOf { it.episodes.size }} " +
                    "duration_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                    "stable_revision=${stableRevision != null}",
            )
            ScanResult.Success(
                channels = mediaStoreChannels,
                sourceRevision = stableRevision,
                isAuthoritative = false,
            )
        } catch (_: SecurityException) {
            ScanResult.Failure(ScanFailure.PERMISSION_LOST)
        } catch (_: Exception) {
            ScanResult.Failure(ScanFailure.UNAVAILABLE)
        }
    }

    private fun resolveDocumentVideoRoot(
        storageRoot: DocumentFile,
        preferredDirectory: String,
    ): DocumentFile {
        val normalized = UsbStorageResolver.normalizeVideoDirectory(preferredDirectory)
            ?: UsbStorageResolver.DEFAULT_VIDEO_DIRECTORY
        if (normalized.isEmpty()) return storageRoot

        var current = storageRoot
        normalized.split('/').forEach { segment ->
            current = current.listFiles().firstOrNull { child ->
                child.isDirectory && child.name.equals(segment, ignoreCase = true)
            } ?: return storageRoot
        }
        return current
    }

    private fun resolveFileVideoRoot(
        storageRoot: File,
        preferredDirectory: String,
    ): File? {
        val normalized = UsbStorageResolver.normalizeVideoDirectory(preferredDirectory)
            ?: UsbStorageResolver.DEFAULT_VIDEO_DIRECTORY
        if (normalized.isEmpty()) return storageRoot

        var current = storageRoot
        normalized.split('/').forEach { segment ->
            val children = current.listFiles() ?: return null
            val child = children.firstOrNull { candidate ->
                candidate.isDirectory && candidate.name.equals(segment, ignoreCase = true)
            } ?: return storageRoot
            current = child
        }
        return current
    }

    private fun inferMediaStoreVideoDirectory(
        records: List<MediaStoreVideoRecord>,
        preferredDirectory: String,
    ): String {
        val normalized = UsbStorageResolver.normalizeVideoDirectory(preferredDirectory)
            ?: UsbStorageResolver.DEFAULT_VIDEO_DIRECTORY
        if (normalized.isEmpty()) return ""
        val preferredSegments = normalized.split('/')
        val directoryFound = records.any { record ->
            val recordSegments = record.relativePath
                .replace('\\', '/')
                .split('/')
                .map(String::trim)
                .filter(String::isNotEmpty)
            recordSegments.size > preferredSegments.size &&
                recordSegments.take(preferredSegments.size)
                    .zip(preferredSegments)
                    .all { (actual, expected) -> actual.equals(expected, ignoreCase = true) }
        }
        return normalized.takeIf { directoryFound }.orEmpty()
    }

    private fun readMediaStoreRevision(context: Context, volumeName: String): String? =
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    "generation:${MediaStore.getGeneration(context, volumeName)}"

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    "version:${MediaStore.getVersion(context, volumeName)}"

                else -> null
            }
        }.getOrNull()

    private fun directMediaFileUri(
        storageRoot: File?,
        relativePath: String,
        displayName: String,
    ): Uri? {
        storageRoot ?: return null
        if (displayName.isBlank() || displayName.contains('/') || displayName.contains('\\')) {
            return null
        }
        val pathSegments = relativePath
            .replace('\\', '/')
            .split('/')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (pathSegments.any { segment -> segment == "." || segment == ".." }) return null

        val directory = pathSegments.fold(storageRoot) { parent, segment ->
            File(parent, segment)
        }
        return Uri.fromFile(File(directory, displayName))
    }
}

private sealed interface FileChannelScan {
    data object Empty : FileChannelScan
    data object Failure : FileChannelScan
    data class Success(val channel: ScannedChannel) : FileChannelScan
}

sealed interface ScanResult {
    data class Success(
        val channels: List<ScannedChannel>,
        val sourceRevision: String? = null,
        val isAuthoritative: Boolean = false,
    ) : ScanResult
    data class Failure(
        val reason: ScanFailure,
        val diagnostic: String? = null,
    ) : ScanResult
}

enum class ScanFailure {
    PERMISSION_LOST,
    UNAVAILABLE,
    USB_REMOVED,
    SMB_AUTHENTICATION,
    SMB_SHARE_NOT_FOUND,
    SMB_CONNECTION,
    SMB_PROTOCOL,
    SMB_UNKNOWN,
}

private const val TAG = "TV2000-SMB"
private const val SCAN_TAG = "TV2000.Scan"
