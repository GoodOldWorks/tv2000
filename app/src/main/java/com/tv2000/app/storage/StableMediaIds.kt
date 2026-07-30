package com.tv2000.app.storage

import java.security.MessageDigest
import java.text.Normalizer

object StableMediaIds {
    fun volume(rootUri: String): String =
        hash("volume", normalize(rootUri))

    fun channel(volumeId: String, relativePath: String): String =
        hash("channel", volumeId, normalizePath(relativePath))

    fun episode(channelId: String, relativePath: String): String =
        hash("episode", channelId, normalizePath(relativePath))

    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)

    private fun normalizePath(value: String): String =
        normalize(value)
            .replace('\\', '/')
            .trim('/')

    private fun hash(vararg parts: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString(separator = "\u0000").toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
