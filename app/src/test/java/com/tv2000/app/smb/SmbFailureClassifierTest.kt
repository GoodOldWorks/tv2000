package com.tv2000.app.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.tv2000.app.scanner.ScanFailure
import java.net.ConnectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbFailureClassifierTest {
    @Test
    fun `classifies nested logon failure without exposing exception message`() {
        val apiError = SMBApiException(
            NtStatus.STATUS_LOGON_FAILURE.value,
            SMB2MessageCommandCode.SMB2_SESSION_SETUP,
            "secret text must not be displayed",
            null,
        )

        val result = classifySmbFailure(IllegalStateException("outer secret", apiError))

        assertEquals(ScanFailure.SMB_AUTHENTICATION, result.reason)
        assertTrue(result.diagnostic.contains("STATUS_LOGON_FAILURE (0xC000006D)"))
        assertTrue(result.diagnostic.contains("SMBApiException"))
        assertFalse(result.diagnostic.contains("secret"))
    }

    @Test
    fun `classifies missing share`() {
        val error = SMBApiException(
            NtStatus.STATUS_BAD_NETWORK_NAME.value,
            SMB2MessageCommandCode.SMB2_TREE_CONNECT,
            null,
        )

        assertEquals(
            ScanFailure.SMB_SHARE_NOT_FOUND,
            classifySmbFailure(error).reason,
        )
    }

    @Test
    fun `classifies nested connection errors`() {
        val error = IllegalStateException(ConnectException("connection refused"))

        val result = classifySmbFailure(error)

        assertEquals(ScanFailure.SMB_CONNECTION, result.reason)
        assertEquals("IllegalStateException → ConnectException", result.diagnostic)
    }

    @Test
    fun `classifies invalid session parameter as a protocol error`() {
        val error = SMBApiException(
            NtStatus.STATUS_INVALID_PARAMETER.value,
            SMB2MessageCommandCode.SMB2_SESSION_SETUP,
            null,
        )

        assertEquals(ScanFailure.SMB_PROTOCOL, classifySmbFailure(error).reason)
    }
}
