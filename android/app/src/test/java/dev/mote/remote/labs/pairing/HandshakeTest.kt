package dev.mote.remote.labs.pairing

import dev.mote.remote.net.Msg
import dev.mote.remote.net.toHex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pure handshake state machine and proof checks (docs/labs/pairing.md section 3). */
class HandshakeTest {
    private val hostId = "0123456789abcdef"
    private val nonce = "a".repeat(32)
    private val cn = "b".repeat(32)
    private val phone = SeedSigner(Random(7).nextBytes(32))
    private val mac = SeedSigner(Random(8).nextBytes(32))
    private val phonePub = phone.pub.toHex()
    private val macFp = PairProto.fingerprint(mac.pub)

    private fun hi(signer: Signer = mac, host: String = hostId, n: String = nonce, c: String = cn, fp: String = PairProto.fingerprint(signer.pub)) =
        PairMsg.Hi(signer.pub.toHex(), fp, signer.sign(PairProto.helperProof(host, n, c, phonePub)).toHex(), "Mac", hostId)

    private fun machine(pin: String? = macFp, allowPrompt: Boolean = true) = PairMachine(hostId, nonce, cn, phonePub, pin, allowPrompt)
    private val authed = Msg.Authed("k:x", "Pixel", false, true)

    @Test fun proofStringsAreTheSpec() {
        assertEquals("mote-labs-pair-v1|client|$hostId|$nonce|$phonePub", String(PairProto.clientProof(hostId, nonce, phonePub)))
        assertEquals("mote-labs-pair-v1|helper|$hostId|$nonce|$cn|$phonePub", String(PairProto.helperProof(hostId, nonce, cn, phonePub)))
        assertEquals(32, macFp.length)
        assertEquals("3f2a 9c01", PairProto.groupFp("3f2a9c01"))
    }

    @Test fun approvedFlow() {
        val m = machine()
        assertTrue(m.onPair(hi()) is PairState.Verified)
        assertEquals(PairState.Pending(HelperKey(mac.pub.toHex(), macFp, "Mac"), 60), m.onPair(PairMsg.Pending(60)))
        assertTrue(m.onPair(PairMsg.Ok("approved", "k:x", "Pixel")) is PairState.Accepted)
        val done = m.onAuthed(authed) as PairState.Done
        assertEquals("approved", done.via)
        assertEquals(macFp, done.helper.fp)
    }

    @Test fun trustedAndTailscaleSkipThePrompt() {
        for (via in listOf("trusted", "tailscale")) {
            val m = machine()
            m.onPair(hi())
            m.onPair(PairMsg.Ok(via, "k:x", "Pixel"))
            assertEquals(via, (m.onAuthed(authed) as PairState.Done).via)
        }
    }

    @Test fun denied() {
        val m = machine()
        m.onPair(hi()); m.onPair(PairMsg.Pending(60))
        assertEquals(PairState.Denied("denied", null), m.onPair(PairMsg.Denied("denied", null)))
        assertTrue("terminal states stay", m.onPair(PairMsg.Ok("approved", "k:x", "")) is PairState.Denied)
    }

    private fun failCode(m: PairMachine, msg: PairMsg) = (m.onPair(msg) as? PairState.Failed)?.code

    @Test fun badHelperProofsAreRejected() {
        assertEquals("helper-key", failCode(machine(), hi(host = "fedcba9876543210")))  // signed for another Mac
        assertEquals("helper-key", failCode(machine(), hi(n = "c".repeat(32))))        // old connection's nonce
        assertEquals("helper-key", failCode(machine(), hi(c = "d".repeat(32))))        // replay: not our cn
        assertEquals("helper-key", failCode(machine(), hi(fp = "0".repeat(32))))       // fp doesn't match pub
        val other = SeedSigner(Random(9).nextBytes(32))
        assertEquals("helper-key", failCode(machine(), hi(signer = other)))            // pinned key changed (BLE spoof)
        assertTrue("unpinned first contact accepts any valid key", machine(pin = null).onPair(hi(signer = other)) is PairState.Verified)
        assertEquals("helper-key", failCode(machine(), hi().copy(hostId = "fedcba9876543210")))
        assertEquals("bad", failCode(machine(), hi().copy(sig = "zz")))
        assertEquals("bad", failCode(machine(), hi().copy(pub = "00")))
    }

    @Test fun outOfOrderAndSilentReconnect() {
        assertEquals("bad", failCode(machine(), PairMsg.Ok("trusted", "k:x", "")))     // ok before hi
        val m = machine()
        m.onPair(hi())
        assertEquals("bad", (m.onAuthed(authed) as PairState.Failed).code)              // authed before ok
        val silent = machine(allowPrompt = false)
        silent.onPair(hi())
        assertEquals("revoked", failCode(silent, PairMsg.Pending(60)))                   // reconnects never prompt
        val e = machine()
        e.onPair(hi())
        assertEquals(PairState.Failed("unauth", "authenticate first"), e.onErr("unauth", "authenticate first"))
    }

    @Test fun parsesHelperMessages() {
        assertEquals(PairMsg.Denied("rate", 30), PairProto.parse(JSONObject("""{"t":"labs.pair.denied","reason":"rate","retryAfter":30}""")))
        assertEquals(PairMsg.Pending(60), PairProto.parse(JSONObject("""{"t":"labs.pair.pending","timeout":60}""")))
        assertEquals(null, PairProto.parse(JSONObject("""{"t":"labs.pair.hi","pub":"x"}""")))
        val hello = JSONObject(PairProto.hello(phonePub, "x".repeat(80), cn, "s"))
        assertEquals("labs.pair.hello", hello.getString("t"))
        assertEquals(64, hello.getString("name").length)
    }
}
