package com.tv2000.app.smb

import com.hierynomus.protocol.transport.TransportException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbTransportFailureTest {
    @Test
    fun `retries nested socket timeout`() {
        val error = IllegalStateException(
            ExecutionException(TransportException(SocketTimeoutException())),
        )

        assertTrue(isRetryableSmbTransportFailure(error))
    }

    @Test
    fun `does not retry interrupted SMB read`() {
        val error = TransportException(ExecutionException(InterruptedException()))

        assertFalse(isRetryableSmbTransportFailure(error))
    }

    @Test
    fun `does not retry unrelated source failure`() {
        assertFalse(isRetryableSmbTransportFailure(IllegalArgumentException()))
    }
}
