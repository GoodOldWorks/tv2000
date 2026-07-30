package com.tv2000.app.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tv2000.app.model.ScannedChannel
import com.tv2000.app.model.ScannedEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ChannelScanner {
    suspend fun scan(context: Context, rootUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        try {
            val root = if (rootUri.scheme == "file") {
                rootUri.path?.let { path -> DocumentFile.fromFile(File(path)) }
            } else {
                DocumentFile.fromTreeUri(context, rootUri)
            }
                ?: return@withContext ScanResult.Failure(ScanFailure.UNAVAILABLE)

            if (!root.exists() || !root.isDirectory) {
                return@withContext ScanResult.Failure(ScanFailure.UNAVAILABLE)
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

            ScanResult.Success(candidates)
        } catch (_: SecurityException) {
            ScanResult.Failure(ScanFailure.PERMISSION_LOST)
        } catch (_: Exception) {
            ScanResult.Failure(ScanFailure.UNAVAILABLE)
        }
    }

    private fun scanChannel(directory: DocumentFile): ScannedChannel? {
        val name = directory.name?.trim().orEmpty()
        if (name.isEmpty()) return null

        val episodes = directory.listFiles()
            .asSequence()
            .filter { it.isFile }
            .filterNot { it.name.orEmpty().startsWith(".") }
            .filter { file ->
                val fileName = file.name.orEmpty()
                val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
                extension.lowercase() in SUPPORTED_EXTENSIONS && file.length() > 0L
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
            relativePath = name,
            name = name,
            sourceUri = directory.uri,
            episodes = episodes,
        )
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf(
            "mp4",
            "mkv",
            "avi",
            "mov",
            "ts",
            "m2ts",
            "webm",
        )
    }
}

sealed interface ScanResult {
    data class Success(val channels: List<ScannedChannel>) : ScanResult
    data class Failure(val reason: ScanFailure) : ScanResult
}

enum class ScanFailure {
    PERMISSION_LOST,
    UNAVAILABLE,
}
