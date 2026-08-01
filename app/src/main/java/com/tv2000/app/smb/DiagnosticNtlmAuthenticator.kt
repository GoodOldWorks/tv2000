package com.tv2000.app.smb

import com.hierynomus.ntlm.av.AvId
import com.hierynomus.ntlm.messages.NtlmChallenge
import com.hierynomus.protocol.commons.Factory
import com.hierynomus.protocol.commons.buffer.Buffer
import com.hierynomus.protocol.commons.buffer.Endian
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticateResponse
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.Authenticator
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.ConnectionContext
import com.hierynomus.spnego.NegTokenTarg

internal data class NtlmServerIdentity(
    val targetName: String?,
    val netBiosDomain: String?,
    val netBiosComputer: String?,
    val dnsDomain: String?,
    val dnsComputer: String?,
) {
    fun safeDiagnostic(): String = buildList {
        targetName?.takeIf(String::isNotBlank)?.let { add("target=$it") }
        netBiosDomain?.takeIf(String::isNotBlank)?.let { add("domain=$it") }
        netBiosComputer?.takeIf(String::isNotBlank)?.let { add("server=$it") }
        dnsDomain?.takeIf(String::isNotBlank)?.let { add("dnsDomain=$it") }
        dnsComputer?.takeIf(String::isNotBlank)?.let { add("dnsServer=$it") }
    }.distinct().joinToString(", ")
}

internal class DiagnosticNtlmAuthenticatorFactory(
    private val onServerIdentity: (NtlmServerIdentity) -> Unit,
) : Factory.Named<Authenticator> {
    override fun getName(): String = "NTLM"

    override fun create(): Authenticator = DiagnosticNtlmAuthenticator(onServerIdentity)
}

private class DiagnosticNtlmAuthenticator(
    private val onServerIdentity: (NtlmServerIdentity) -> Unit,
) : Authenticator {
    private val delegate = NtlmAuthenticator()

    override fun init(config: SmbConfig) = delegate.init(config)

    override fun supports(context: AuthenticationContext): Boolean = delegate.supports(context)

    override fun authenticate(
        context: AuthenticationContext,
        gssToken: ByteArray,
        connectionContext: ConnectionContext,
    ): AuthenticateResponse? {
        parseServerIdentity(gssToken)?.let(onServerIdentity)
        return delegate.authenticate(context, gssToken, connectionContext)
    }
}

private fun parseServerIdentity(gssToken: ByteArray): NtlmServerIdentity? = runCatching {
    if (gssToken.isEmpty()) return@runCatching null
    val responseToken = NegTokenTarg().read(gssToken).responseToken ?: return@runCatching null
    val challenge = NtlmChallenge().apply {
        read(Buffer.PlainBuffer(responseToken, Endian.LE))
    }
    val targetInfo = challenge.targetInfo
    NtlmServerIdentity(
        targetName = challenge.targetName,
        netBiosDomain = targetInfo?.value(AvId.MsvAvNbDomainName),
        netBiosComputer = targetInfo?.value(AvId.MsvAvNbComputerName),
        dnsDomain = targetInfo?.value(AvId.MsvAvDnsDomainName),
        dnsComputer = targetInfo?.value(AvId.MsvAvDnsComputerName),
    )
}.getOrNull()

private fun com.hierynomus.ntlm.messages.TargetInfo.value(id: AvId): String? =
    getAvPair<com.hierynomus.ntlm.av.AvPair<*>>(id)?.value as? String

internal class SmbAuthenticationDiagnosticException(
    val serverIdentity: NtlmServerIdentity,
    cause: Throwable,
) : RuntimeException(cause)
