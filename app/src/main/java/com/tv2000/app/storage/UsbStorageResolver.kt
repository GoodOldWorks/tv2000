package com.tv2000.app.storage

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
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
        return findMountedUsbRoots(context, requireReadAccess = true)
    }

    fun findMountedUsbRootCandidate(context: Context): Uri? =
        findMountedUsbRoots(context, requireReadAccess = false).singleOrNull()

    private fun findMountedUsbRoots(
        context: Context,
        requireReadAccess: Boolean,
    ): List<Uri> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val removableVolumes = mountedRemovableVolumes(storageManager)

        if (removableVolumes.isEmpty()) return emptyList()

        val mediaStoreVolumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context)
                .filterNot { it == MediaStore.VOLUME_EXTERNAL_PRIMARY }
                .sorted()
        } else {
            emptyList()
        }

        return removableVolumes.mapNotNull { volume ->
            val fallbackDirectory = volume.directory
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
            } ?: volume.directory
                ?.takeIf { directory ->
                    shouldExposeMountedFileRoot(
                        isDirectory = directory.isDirectory,
                        isReadable = directory.canRead(),
                        requireReadAccess = requireReadAccess,
                    )
                }
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
        removableVolume: MountedStorageVolume,
        mediaStoreVolumes: List<String>,
        allowSingleFallback: Boolean,
    ): String? {
        removableVolume.mediaStoreVolumeName
            ?.let { volumeName ->
                mediaStoreVolumes.firstOrNull {
                    it.equals(volumeName, ignoreCase = true)
                }
            }
            ?.let { return it }

        removableVolume.uuid?.let { uuid ->
            mediaStoreVolumes.firstOrNull { volumeName ->
                volumeName.equals(uuid, ignoreCase = true)
            }?.let { return it }
        }
        return mediaStoreVolumes.singleOrNull().takeIf { allowSingleFallback }
    }

    private fun uuidIdentity(value: String): String =
        "uuid:${value.lowercase(Locale.ROOT)}"

    private fun mountedRemovableVolumes(
        storageManager: StorageManager,
    ): List<MountedStorageVolume> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Api24StorageVolumes.mountedRemovableVolumes(storageManager)
        } else {
            legacyMountedRemovableVolumes(storageManager)
        }

    private fun legacyMountedRemovableVolumes(
        storageManager: StorageManager,
    ): List<MountedStorageVolume> {
        val pathVolumes = legacyMountedVolumePaths(storageManager)
        Log.i(TAG, "legacy_path_mounted_removable_count=${pathVolumes.size}")
        if (pathVolumes.isNotEmpty()) return pathVolumes

        val volumeResult = runCatching {
            storageManager.javaClass.getMethod("getVolumeList").invoke(storageManager)
        }.onFailure { error ->
            Log.w(TAG, "legacy getVolumeList unavailable: ${error.javaClass.simpleName}")
        }
        val volumes = volumeResult.getOrNull() as? Array<*> ?: return emptyList()

        return volumes.filterNotNull().mapNotNull { volume ->
            val isRemovable = invokeNoArg(volume, "isRemovable") as? Boolean
                ?: return@mapNotNull null
            if (!isRemovable) return@mapNotNull null

            val directory = reflectedVolumeDirectory(volume)
                ?: return@mapNotNull null
            val state = invokeNoArg(volume, "getState") as? String
                ?: runCatching { Environment.getExternalStorageState(directory) }.getOrNull()
            if (state != Environment.MEDIA_MOUNTED) return@mapNotNull null

            MountedStorageVolume(
                uuid = invokeNoArg(volume, "getUuid") as? String,
                directory = directory,
            )
        }
    }

    private fun legacyMountedVolumePaths(
        storageManager: StorageManager,
    ): List<MountedStorageVolume> {
        val volumePathsResult = runCatching {
            storageManager.javaClass.getMethod("getVolumePaths").invoke(storageManager)
        }.onFailure { error ->
            Log.w(TAG, "legacy getVolumePaths unavailable: ${error.javaClass.simpleName}")
        }
        val volumePaths = volumePathsResult.getOrNull() as? Array<*> ?: return emptyList()
        val primaryPath = runCatching {
            Environment.getExternalStorageDirectory().canonicalPath
        }.getOrNull()

        return volumePaths.filterIsInstance<String>().mapNotNull { path ->
            val directory = File(path)
            val canonicalPath = runCatching { directory.canonicalPath }.getOrNull()
                ?: return@mapNotNull null
            if (canonicalPath == primaryPath || !directory.isDirectory) {
                return@mapNotNull null
            }
            val isRemovable = runCatching {
                Environment.isExternalStorageRemovable(directory)
            }.getOrDefault(true)
            if (!isRemovable) return@mapNotNull null

            val state = invokeWithString(
                receiver = storageManager,
                methodName = "getVolumeState",
                value = canonicalPath,
            ) as? String ?: runCatching {
                Environment.getExternalStorageState(directory)
            }.getOrNull()
            Log.i(TAG, "legacy_volume_candidate removable=$isRemovable state=$state")
            if (state != Environment.MEDIA_MOUNTED) return@mapNotNull null

            MountedStorageVolume(
                uuid = null,
                directory = directory,
            )
        }
    }

    private fun reflectedVolumeDirectory(volume: Any): File? =
        runCatching {
            volume.javaClass.getMethod("getPathFile").invoke(volume) as? File
        }.getOrNull() ?: runCatching {
            val path = volume.javaClass.getMethod("getPath").invoke(volume) as? String
            path?.let(::File)
        }.getOrNull()

    private fun invokeNoArg(receiver: Any, methodName: String): Any? =
        runCatching {
            receiver.javaClass.getMethod(methodName).invoke(receiver)
        }.getOrNull()

    private fun invokeWithString(
        receiver: Any,
        methodName: String,
        value: String,
    ): Any? = runCatching {
        receiver.javaClass.getMethod(methodName, String::class.java).invoke(receiver, value)
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.N)
    private object Api24StorageVolumes {
        fun mountedRemovableVolumes(
            storageManager: StorageManager,
        ): List<MountedStorageVolume> = storageManager.storageVolumes
            .asSequence()
            .filter { volume ->
                volume.isRemovable && volume.state == Environment.MEDIA_MOUNTED
            }
            .map { volume ->
                MountedStorageVolume(
                    uuid = volume.uuid,
                    directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory
                    } else {
                        reflectedVolumeDirectory(volume)
                    },
                    mediaStoreVolumeName = if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ) {
                        volume.mediaStoreVolumeName
                    } else {
                        null
                    },
                )
            }
            .toList()
    }

    private const val FALLBACK_PATH_QUERY = "fallbackPath"
    private const val TAG = "TV2000.Usb"
}

private data class MountedStorageVolume(
    val uuid: String?,
    val directory: File?,
    val mediaStoreVolumeName: String? = null,
)

internal fun shouldExposeMountedFileRoot(
    isDirectory: Boolean,
    isReadable: Boolean,
    requireReadAccess: Boolean,
): Boolean = isDirectory && (!requireReadAccess || isReadable)
