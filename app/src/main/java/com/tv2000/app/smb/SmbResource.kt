package com.tv2000.app.smb

import android.net.Uri
import java.net.URI
import java.security.MessageDigest

data class SmbResource(
    val host: String,
    val port: Int = DEFAULT_SMB_PORT,
    val share: String,
    val directory: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val displayName: String
        get() = buildString {
            append(host)
            append('/')
            append(share)
            if (directory.isNotEmpty()) {
                append('/')
                append(directory.replace('\\', '/'))
            }
        }

    val address: String
        get() = buildString {
            append("smb://")
            append(hostForUri(host))
            if (port != DEFAULT_SMB_PORT) {
                append(':')
                append(port)
            }
            append('/')
            append(share)
            if (directory.isNotEmpty()) {
                append('/')
                append(directory.replace('\\', '/'))
            }
        }

    fun remotePath(relativePath: String): String =
        listOf(directory, normalizeRemotePath(relativePath))
            .filter(String::isNotEmpty)
            .joinToString("\\")

    companion object {
        fun parse(
            address: String,
            username: String = "",
            password: String = "",
            domain: String = "",
        ): Result<SmbResource> = runCatching {
            val normalizedAddress = address.trim().let { value ->
                if (value.startsWith("smb://", ignoreCase = true)) value else "smb://$value"
            }
            val uri = URI(normalizedAddress.replace(" ", "%20"))
            require(uri.scheme.equals("smb", ignoreCase = true)) { "地址必须使用 smb://" }
            val host = uri.host?.trim().orEmpty()
            require(host.isNotEmpty()) { "请填写服务器地址" }
            val port = if (uri.port == -1) DEFAULT_SMB_PORT else uri.port
            require(port in 1..65535) { "端口必须在 1 到 65535 之间" }

            val segments = uri.path.orEmpty()
                .split('/')
                .filter(String::isNotEmpty)
                .map(String::trim)
            val share = segments.firstOrNull().orEmpty()
            require(share.isNotEmpty()) { "地址中必须包含共享名" }

            SmbResource(
                host = host,
                port = port,
                share = share,
                directory = normalizeRemotePath(segments.drop(1).joinToString("/")),
                username = username.trim(),
                password = password,
                domain = domain.trim(),
            )
        }
    }
}

object SmbMediaUri {
    const val SCHEME = "tv2000-smb"
    private const val AUTHORITY = "primary"

    fun root(resource: SmbResource): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .appendPath(resource.fingerprint())
        .build()

    fun channel(channelName: String): Uri = build(channelName)

    fun episode(channelName: String, fileName: String): Uri = build(channelName, fileName)

    fun relativePath(uri: Uri): String? {
        if (uri.scheme != SCHEME || uri.authority != AUTHORITY) return null
        return uri.pathSegments
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\\")
            .takeIf(String::isNotEmpty)
    }

    fun isSmb(uri: Uri): Boolean = uri.scheme == SCHEME

    private fun build(vararg segments: String): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .apply { segments.forEach(::appendPath) }
        .build()
}

private fun SmbResource.fingerprint(): String {
    val identity = listOf(
        host.lowercase(),
        port.toString(),
        share.lowercase(),
        directory.lowercase(),
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun normalizeRemotePath(path: String): String = path
    .replace('/', '\\')
    .split('\\')
    .map(String::trim)
    .filter { segment -> segment.isNotEmpty() && segment != "." }
    .also { segments -> require(segments.none { it == ".." }) { "目录不能包含 .." } }
    .joinToString("\\")

private fun hostForUri(host: String): String =
    if (':' in host && !host.startsWith('[')) "[$host]" else host

const val DEFAULT_SMB_PORT = 445
