package com.tv2000.app.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.tv2000.app.model.ScannedChannel
import com.tv2000.app.model.ScannedEpisode
import com.tv2000.app.scanner.NaturalOrderComparator
import com.tv2000.app.scanner.displayChannelName
import com.tv2000.app.scanner.isSupportedVideoFile
import java.io.Closeable
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SmbjMediaClient {
    private val currentServerIdentity = ThreadLocal<NtlmServerIdentity?>()
    private val standardClientConfig = createClientConfig(ntlmIntegrity = true)
    private val compatibilityClientConfig = createClientConfig(ntlmIntegrity = false)
    private val compatibilityAuthenticationKeys = ConcurrentHashMap.newKeySet<String>()

    private fun createClientConfig(ntlmIntegrity: Boolean): SmbConfig = SmbConfig.builder()
        .apply { withNtlmConfig().withIntegrity(ntlmIntegrity) }
        .withAuthenticators(
            DiagnosticNtlmAuthenticatorFactory(currentServerIdentity::set),
        )
        .withSoTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withReadTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withTransactTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun scanChannels(resource: SmbResource): List<ScannedChannel> =
        withShare(resource) { share ->
            share.list(resource.directory)
                .asSequence()
                .filterNot { entry -> entry.fileName == "." || entry.fileName == ".." }
                .filter { entry -> entry.hasAttribute(FileAttributes.FILE_ATTRIBUTE_DIRECTORY) }
                .filterNot { entry ->
                    entry.fileName.startsWith('.') ||
                        entry.hasAttribute(FileAttributes.FILE_ATTRIBUTE_HIDDEN)
                }
                .mapNotNull { directory ->
                    val channelName = directory.fileName.trim()
                    if (channelName.isEmpty()) return@mapNotNull null
                    val channelRemotePath = resource.remotePath(channelName)
                    val episodes = share.list(channelRemotePath)
                        .asSequence()
                        .filterNot { entry -> entry.fileName == "." || entry.fileName == ".." }
                        .filterNot { entry ->
                            entry.hasAttribute(FileAttributes.FILE_ATTRIBUTE_DIRECTORY) ||
                                entry.hasAttribute(FileAttributes.FILE_ATTRIBUTE_HIDDEN)
                        }
                        .filter { entry ->
                            entry.endOfFile > 0L && isSupportedVideoFile(entry.fileName)
                        }
                        .map { entry ->
                            val fileName = entry.fileName
                            ScannedEpisode(
                                relativePath = fileName,
                                title = fileName.substringBeforeLast(
                                    '.',
                                    missingDelimiterValue = fileName,
                                ),
                                uri = SmbMediaUri.episode(channelName, fileName),
                                sizeBytes = entry.endOfFile,
                                modifiedAt = entry.lastWriteTime.toEpochMillis(),
                            )
                        }
                        .sortedWith { left, right ->
                            NaturalOrderComparator.compare(left.title, right.title)
                        }
                        .toList()
                    if (episodes.isEmpty()) return@mapNotNull null

                    ScannedChannel(
                        relativePath = channelName,
                        name = displayChannelName(channelName),
                        sourceUri = SmbMediaUri.channel(channelName),
                        episodes = episodes,
                    )
                }
                .sortedWith { left, right ->
                    NaturalOrderComparator.compare(left.name, right.name)
                }
                .toList()
        }

    fun isReadable(resource: SmbResource, relativePath: String): Boolean =
        runCatching {
            withShare(resource) { share ->
                share.fileExists(resource.remotePath(relativePath))
            }
        }.getOrDefault(false)

    fun open(resource: SmbResource, relativePath: String): SmbOpenFile =
        withCompatibleConfig(resource) { config ->
            open(resource, relativePath, config)
        }

    private fun open(
        resource: SmbResource,
        relativePath: String,
        config: SmbConfig,
    ): SmbOpenFile {
        val client = SMBClient(config)
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        return try {
            connection = client.connect(resource.host, resource.port)
            session = connection.authenticateWithDiagnostics(resource)
            share = session.connectShare(resource.share) as? DiskShare
                ?: error("SMB resource is not a disk share")
            val file = share.openFile(
                resource.remotePath(relativePath),
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(
                    SMB2ShareAccess.FILE_SHARE_READ,
                    SMB2ShareAccess.FILE_SHARE_WRITE,
                    SMB2ShareAccess.FILE_SHARE_DELETE,
                ),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            SmbOpenFile(
                client = client,
                connection = connection,
                session = session,
                share = share,
                file = file,
                length = file.fileInformation.standardInformation.endOfFile,
            )
        } catch (error: Exception) {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
            throw error
        }
    }

    private fun <T> withShare(resource: SmbResource, block: (DiskShare) -> T): T =
        withCompatibleConfig(resource) { config ->
            withShare(resource, config, block)
        }

    private fun <T> withShare(
        resource: SmbResource,
        config: SmbConfig,
        block: (DiskShare) -> T,
    ): T =
        SMBClient(config).use { client ->
            client.connect(resource.host, resource.port).use { connection ->
                connection.authenticateWithDiagnostics(resource).use { session ->
                    val share = session.connectShare(resource.share) as? DiskShare
                        ?: error("SMB resource is not a disk share")
                    share.use { block(it) }
                }
            }
        }

    private fun Connection.authenticateWithDiagnostics(resource: SmbResource): Session {
        currentServerIdentity.remove()
        return try {
            authenticate(resource.authenticationContext())
        } catch (error: Exception) {
            val identity = currentServerIdentity.get()
            if (identity != null) {
                throw SmbAuthenticationDiagnosticException(identity, error)
            }
            throw error
        } finally {
            currentServerIdentity.remove()
        }
    }

    private fun <T> withCompatibleConfig(
        resource: SmbResource,
        block: (SmbConfig) -> T,
    ): T {
        val key = resource.authenticationCompatibilityKey()
        if (key in compatibilityAuthenticationKeys) {
            return block(compatibilityClientConfig)
        }

        return try {
            block(standardClientConfig)
        } catch (error: Exception) {
            if (!shouldRetryWithoutNtlmIntegrity(error)) throw error
            block(compatibilityClientConfig).also {
                compatibilityAuthenticationKeys += key
            }
        }
    }

    private fun SmbResource.authenticationContext(): AuthenticationContext =
        if (username.isEmpty() && password.isEmpty()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(username, password.toCharArray(), domain)
        }

    private fun com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation.hasAttribute(
        attribute: FileAttributes,
    ): Boolean = fileAttributes and attribute.value != 0L

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
    }
}

internal fun shouldRetryWithoutNtlmIntegrity(error: Throwable): Boolean {
    if (error !is SmbAuthenticationDiagnosticException) return false
    return generateSequence<Throwable>(error) { cause ->
        cause.cause.takeUnless { it === cause }
    }.filterIsInstance<SMBApiException>().any { apiError ->
        apiError.status == NtStatus.STATUS_INVALID_PARAMETER &&
            apiError.failedCommand == SMB2MessageCommandCode.SMB2_SESSION_SETUP
    }
}

private fun SmbResource.authenticationCompatibilityKey(): String =
    listOf(host.lowercase(), port.toString(), username.lowercase(), domain.lowercase())
        .joinToString("\u0000")

class SmbOpenFile internal constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
    private val file: com.hierynomus.smbj.share.File,
    val length: Long,
) : Closeable {
    fun read(buffer: ByteArray, fileOffset: Long, offset: Int, length: Int): Int =
        file.read(buffer, fileOffset, offset, length)

    override fun close() {
        runCatching { file.close() }
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}
