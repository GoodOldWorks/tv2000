package com.tv2000.app.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.ntlm.NtlmException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.spnego.SpnegoException
import com.tv2000.app.scanner.ScanFailure
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class ClassifiedSmbFailure(
    val reason: ScanFailure,
    val diagnostic: String,
)

fun classifySmbFailure(error: Throwable): ClassifiedSmbFailure {
    val causes = error.causeSequence().toList()
    val serverIdentity = causes
        .filterIsInstance<SmbAuthenticationDiagnosticException>()
        .firstOrNull()
        ?.serverIdentity
        ?.safeDiagnostic()
        ?.takeIf(String::isNotBlank)
    val apiError = causes.filterIsInstance<SMBApiException>().firstOrNull()
    if (apiError != null) {
        val status = apiError.status
        val reason = when (status) {
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_ACCESS_DENIED,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_PASSWORD_EXPIRED,
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
            -> ScanFailure.SMB_AUTHENTICATION

            NtStatus.STATUS_BAD_NETWORK_NAME,
            NtStatus.STATUS_BAD_NETWORK_PATH,
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_NOT_A_DIRECTORY,
            -> ScanFailure.SMB_SHARE_NOT_FOUND

            NtStatus.STATUS_TIMEOUT,
            NtStatus.STATUS_IO_TIMEOUT,
            NtStatus.STATUS_NETWORK_NAME_DELETED,
            NtStatus.STATUS_CONNECTION_DISCONNECTED,
            NtStatus.STATUS_CONNECTION_RESET,
            -> ScanFailure.SMB_CONNECTION

            NtStatus.STATUS_NOT_SUPPORTED,
            NtStatus.STATUS_NOT_IMPLEMENTED,
            NtStatus.STATUS_INVALID_PARAMETER,
            -> ScanFailure.SMB_PROTOCOL

            else -> ScanFailure.SMB_UNKNOWN
        }
        return ClassifiedSmbFailure(
            reason = reason,
            diagnostic = "${status.name} (${apiError.statusCode.toNtStatusHex()}) · " +
                error.safeCauseNames() +
                serverIdentity?.let { " · $it" }.orEmpty(),
        )
    }

    val reason = when {
        causes.any {
            it is UnknownHostException ||
                it is ConnectException ||
                it is NoRouteToHostException ||
                it is SocketTimeoutException ||
                it is SocketException ||
                it is TransportException
        } -> ScanFailure.SMB_CONNECTION

        causes.any { it is NtlmException || it is SpnegoException } -> {
            ScanFailure.SMB_PROTOCOL
        }

        else -> ScanFailure.SMB_UNKNOWN
    }
    return ClassifiedSmbFailure(
        reason = reason,
        diagnostic = error.safeCauseNames(),
    )
}

private fun Throwable.causeSequence(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeSequence
    repeat(MAX_CAUSE_DEPTH) {
        val cause = current ?: return@sequence
        yield(cause)
        current = cause.cause.takeUnless { it === cause }
    }
}

private fun Throwable.safeCauseNames(): String = causeSequence()
    .map { cause -> cause.javaClass.simpleName.ifEmpty { cause.javaClass.name } }
    .distinct()
    .joinToString(" → ")

private fun Long.toNtStatusHex(): String = "0x%08X".format(this and 0xFFFF_FFFFL)

private const val MAX_CAUSE_DEPTH = 8
