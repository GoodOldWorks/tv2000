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

object UsbStorageResolver {
    const val MEDIA_STORE_SCHEME = "tv2000-mediastore"

    fun requiredReadPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun findMountedUsbRoot(context: Context): Uri? {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val removableVolumes = storageManager.storageVolumes
            .filter { volume ->
                volume.isRemovable && volume.state == Environment.MEDIA_MOUNTED
            }

        if (removableVolumes.isEmpty()) return null

        val fallbackDirectory = removableVolumes
            .asSequence()
            .mapNotNull(::volumeDirectoryCompat)
            .firstOrNull { directory -> directory.isDirectory }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaStoreVolumes = MediaStore.getExternalVolumeNames(context)
                .filterNot { it == MediaStore.VOLUME_EXTERNAL_PRIMARY }
                .sorted()

            findMediaStoreVolumeName(removableVolumes, mediaStoreVolumes)?.let { volumeName ->
                return mediaStoreRootUri(
                    volumeName = volumeName,
                    fallbackPath = fallbackDirectory?.absolutePath,
                )
            }
        }

        return fallbackDirectory
            ?.takeIf(File::canRead)
            ?.let(Uri::fromFile)
    }

    fun isMediaStoreRoot(uri: Uri): Boolean = uri.scheme == MEDIA_STORE_SCHEME

    fun mediaStoreVolumeName(uri: Uri): String? =
        uri.authority?.takeIf(String::isNotBlank)

    fun fallbackFilePath(uri: Uri): String? =
        uri.getQueryParameter(FALLBACK_PATH_QUERY)?.takeIf(String::isNotBlank)

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
        removableVolumes: List<StorageVolume>,
        mediaStoreVolumes: List<String>,
    ): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            removableVolumes
                .asSequence()
                .mapNotNull(StorageVolume::getMediaStoreVolumeName)
                .firstOrNull(mediaStoreVolumes::contains)
                ?.let { return it }
        }

        val removableUuids = removableVolumes
            .mapNotNull(StorageVolume::getUuid)
            .map { it.lowercase() }
            .toSet()

        return mediaStoreVolumes.firstOrNull { volumeName ->
            volumeName.lowercase() in removableUuids
        } ?: mediaStoreVolumes.firstOrNull()
    }

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
