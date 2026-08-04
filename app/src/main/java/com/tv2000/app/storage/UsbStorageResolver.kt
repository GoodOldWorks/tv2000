package com.tv2000.app.storage

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

object UsbStorageResolver {
    const val MEDIA_STORE_SCHEME = "tv2000-mediastore"
    const val DEFAULT_VIDEO_DIRECTORY = "TV2000"

    fun requiredReadPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun findMountedUsbRoots(context: Context): List<Uri> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val removableVolumes = storageManager.storageVolumes
            .filter { volume ->
                volume.isRemovable && volume.state == Environment.MEDIA_MOUNTED
            }

        if (removableVolumes.isEmpty()) return emptyList()

        val mediaStoreVolumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context)
                .filterNot { it == MediaStore.VOLUME_EXTERNAL_PRIMARY }
                .sorted()
        } else {
            emptyList()
        }

        return removableVolumes.mapNotNull { volume ->
            val fallbackDirectory = volumeDirectoryCompat(volume)
                ?.takeIf { directory -> directory.isDirectory }
            findMediaStoreVolumeName(
                removableVolume = volume,
                mediaStoreVolumes = mediaStoreVolumes,
                allowSingleFallback = removableVolumes.size == 1,
            )?.let { volumeName ->
                mediaStoreRootUri(
                    volumeName = volumeName,
                    fallbackPath = fallbackDirectory?.absolutePath,
                )
            } ?: fallbackDirectory
                ?.takeIf(File::canRead)
                ?.let(Uri::fromFile)
        }.distinctBy(::volumeIdentity)
    }

    fun findMountedUsbRoot(context: Context): Uri? =
        findMountedUsbRoots(context).singleOrNull()

    fun volumeIdentity(uri: Uri): String = volumeIdentity(uri.toString())

    internal fun volumeIdentity(rootUri: String): String {
        val normalized = rootUri.trim()
        val mediaStorePrefix = "$MEDIA_STORE_SCHEME://"
        if (normalized.startsWith(mediaStorePrefix, ignoreCase = true)) {
            val authority = normalized
                .substring(mediaStorePrefix.length)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
            if (authority.isNotBlank()) return uuidIdentity(authority)
        }

        val externalStorageTreePrefix =
            "content://com.android.externalstorage.documents/tree/"
        if (normalized.startsWith(externalStorageTreePrefix, ignoreCase = true)) {
            val encodedDocumentId = normalized
                .substring(externalStorageTreePrefix.length)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
            val volumeName = runCatching {
                URLDecoder.decode(encodedDocumentId, Charsets.UTF_8.name())
                    .substringBefore(':')
            }.getOrNull()
            if (!volumeName.isNullOrBlank() &&
                !volumeName.equals("primary", ignoreCase = true)
            ) {
                return uuidIdentity(volumeName)
            }
        }

        if (normalized.startsWith("file:", ignoreCase = true)) {
            val pathSegments = runCatching { URI(normalized).path }
                .getOrNull()
                ?.split('/')
                ?.filter(String::isNotBlank)
                .orEmpty()
            val storageIndex = pathSegments.indexOf("storage")
            val volumeName = pathSegments.getOrNull(storageIndex + 1)
            if (!volumeName.isNullOrBlank() &&
                !volumeName.equals("emulated", ignoreCase = true) &&
                !volumeName.equals("self", ignoreCase = true)
            ) {
                return uuidIdentity(volumeName)
            }
        }

        return "uri:$normalized"
    }

    fun isMediaStoreRoot(uri: Uri): Boolean = uri.scheme == MEDIA_STORE_SCHEME

    fun mediaStoreVolumeName(uri: Uri): String? =
        uri.authority?.takeIf(String::isNotBlank)

    fun fallbackFilePath(uri: Uri): String? =
        uri.getQueryParameter(FALLBACK_PATH_QUERY)?.takeIf(String::isNotBlank)

    fun normalizeVideoDirectory(value: String): String? {
        val normalized = value
            .trim()
            .replace('\\', '/')
            .trim('/')
        if (normalized.isEmpty()) return ""

        val segments = normalized.split('/').map(String::trim)
        if (segments.any { segment ->
                segment.isEmpty() || segment == "." || segment == ".."
            }
        ) {
            return null
        }
        return segments.joinToString("/")
    }

    internal fun mediaStoreRootUri(
        volumeName: String,
        fallbackPath: String? = null,
    ): Uri = Uri.Builder()
            .scheme(MEDIA_STORE_SCHEME)
            .authority(volumeName)
            .apply {
                fallbackPath?.let { appendQueryParameter(FALLBACK_PATH_QUERY, it) }
            }
            .build()

    private fun findMediaStoreVolumeName(
        removableVolume: StorageVolume,
        mediaStoreVolumes: List<String>,
        allowSingleFallback: Boolean,
    ): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            removableVolume.mediaStoreVolumeName
                ?.let { volumeName ->
                    mediaStoreVolumes.firstOrNull {
                        it.equals(volumeName, ignoreCase = true)
                    }
                }
                ?.let { return it }
        }

        removableVolume.uuid?.let { uuid ->
            mediaStoreVolumes.firstOrNull { volumeName ->
                volumeName.equals(uuid, ignoreCase = true)
            }?.let { return it }
        }
        return mediaStoreVolumes.singleOrNull().takeIf { allowSingleFallback }
    }

    private fun uuidIdentity(value: String): String =
        "uuid:${value.lowercase(Locale.ROOT)}"

    @Suppress("DEPRECATION")
    private fun volumeDirectoryCompat(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }

        return runCatching {
            volume.javaClass.getMethod("getPathFile").invoke(volume) as? File
        }.getOrNull() ?: runCatching {
            val path = volume.javaClass.getMethod("getPath").invoke(volume) as? String
            path?.let(::File)
        }.getOrNull()
    }

    private const val FALLBACK_PATH_QUERY = "fallbackPath"
}
