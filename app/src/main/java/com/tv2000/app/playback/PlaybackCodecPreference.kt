package com.tv2000.app.playback

import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.tv2000.app.smb.SmbMediaUri

/**
 * Amlogic's hardware AVC decoder can accept some Matroska streams and then only render black
 * frames. Keep hardware decoding for normal sources, but prefer Android's software decoder for
 * SMB-hosted MKV files on affected devices.
 */
@OptIn(UnstableApi::class)
class PlaybackCodecPreference(
    private val softwareSmbMkvWorkaroundEnabled: Boolean =
        Build.HARDWARE.contains("amlogic", ignoreCase = true),
) {
    @Volatile
    private var preferSoftwareAvc = false

    val mediaCodecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        val delegate = if (
            preferSoftwareAvc && mimeType.equals(MimeTypes.VIDEO_H264, ignoreCase = true)
        ) {
            MediaCodecSelector.PREFER_SOFTWARE
        } else {
            MediaCodecSelector.DEFAULT
        }
        delegate.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
    }

    fun onSourceOpening(uri: Uri) {
        preferSoftwareAvc = softwareSmbMkvWorkaroundEnabled &&
            shouldPreferSoftwareAvcForSmbPath(
                isSmb = SmbMediaUri.isSmb(uri),
                path = uri.lastPathSegment,
            )
    }
}

internal fun shouldPreferSoftwareAvcForSmbPath(isSmb: Boolean, path: String?): Boolean =
    isSmb && path?.endsWith(".mkv", ignoreCase = true) == true
