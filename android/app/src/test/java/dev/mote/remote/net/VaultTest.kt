package dev.mote.remote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.AEADBadTagException

class VaultTest {
    private val token = ByteArray(32) { it.toByte() }

    @Test fun undecryptableWrappedTokenMeansRepair() {
        // Wrap key regenerated / blob corrupt: no signer, so WsTransport shows "Pairing data is missing"
        // instead of reconnecting forever.
        assertNull(Vault.wrappedSigner("garbage") { throw AEADBadTagException("mac check failed") })
        assertNull(Vault.wrappedSigner("garbage") { throw IllegalArgumentException("bad base-64") })
    }

    @Test fun wrappedTokenSigns() {
        val sign = Vault.wrappedSigner("blob") { token.copyOf() }!!
        assertEquals(Proto.hmacHex(token, "nonce"), sign("nonce"))
    }
}
