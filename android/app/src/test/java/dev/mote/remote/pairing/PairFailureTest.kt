package dev.mote.remote.pairing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class PairFailureTest {
    private val running = "Mac answered, but Mote Helper isn't running there. Open it from ~/Applications on the Mac."

    @Test fun refusedPortSaysHelperNotRunning() = assertEquals(running, pairFailure(ConnectException("refused"), "Mac"))

    /** WsSession.onFailure wraps OkHttp's ConnectException in a plain IOException (D-FC-2 seen on a real phone). */
    @Test fun wrappedRefusedPortStillSaysHelperNotRunning() =
        assertEquals(running, pairFailure(IOException("Failed to connect to /100.83.11.75:19394", ConnectException("Failed to connect to /100.83.11.75:19394")), "Mac"))

    @Test fun wrappedTimeoutSaysNoAnswer() =
        assertEquals("Couldn't reach Mac: no answer. Is it awake and on the same Wi-Fi (or Tailscale on)?", pairFailure(IOException("t", SocketTimeoutException("t")), "Mac"))

    @Test fun unknownIoKeepsTheDetail() =
        assertEquals("Couldn't connect to Mac (IOException: boom). Is Mote Helper running?", pairFailure(IOException("boom"), "Mac"))
}
