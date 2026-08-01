package com.tv2000.app.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbAuthenticationCompatibilityTest {
    private val identity = NtlmServerIdentity(
        targetName = "NAS",
        netBiosDomain = null,
        netBiosComputer = "NAS",
        dnsDomain = null,
        dnsComputer = null,
    )

    @Test
    fun `retries invalid parameter returned by session setup`() {
        val apiError = SMBApiException(
            NtStatus.STATUS_INVALID_PARAMETER.value,
            SMB2MessageCommandCode.SMB2_SESSION_SETUP,
            null,
        )

        assertTrue(
            shouldRetryWithoutNtlmIntegrity(
                SmbAuthenticationDiagnosticException(identity, apiError),
            ),
        )
    }

    @Test
    fun `does not retry invalid parameter from a share operation`() {
        val apiError = SMBApiException(
            NtStatus.STATUS_INVALID_PARAMETER.value,
            SMB2MessageCommandCode.SMB2_TREE_CONNECT,
            null,
        )

        assertFalse(
            shouldRetryWithoutNtlmIntegrity(
                SmbAuthenticationDiagnosticException(identity, apiError),
            ),
        )
    }

    @Test
    fun `does not retry authentication failures`() {
        val apiError = SMBApiException(
            NtStatus.STATUS_LOGON_FAILURE.value,
            SMB2MessageCommandCode.SMB2_SESSION_SETUP,
            null,
        )

        assertFalse(
            shouldRetryWithoutNtlmIntegrity(
                SmbAuthenticationDiagnosticException(identity, apiError),
            ),
        )
    }
}
