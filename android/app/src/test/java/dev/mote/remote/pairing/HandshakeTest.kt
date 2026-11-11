package dev.mote.remote.pairing

import dev.mote.remote.net.Msg
import dev.mote.remote.net.toHex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Pure handshake state machine and proof checks (docs/pairing.md section 3). */
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

    @Test fun errKeepsRetryAfter() {  // keychain-reading / rate: retryAfter is not dropped
        val m = machine()
        m.onPair(hi())
        assertEquals(PairState.Failed("keychain-reading", "reading", 2), m.onErr("keychain-reading", "reading", 2))
    }

    @Test fun denialReasonsReadInPlainWords() {  // B6
        for (r in dev.mote.remote.net.DENIAL_CODES) assertTrue(r, dev.mote.remote.ui.denialText(r).length > 10)
        assertEquals("Bring the phone close to the Mac, with Bluetooth on.", dev.mote.remote.ui.denialText("far"))
        assertEquals("This Mac blocked this phone; unblock it in Mote Helper.", dev.mote.remote.ui.denialText("blocked"))
        assertEquals("The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again.", dev.mote.remote.ui.denialText("revoked"))
    }

    @Test fun parsesHelperMessages() {
        assertEquals(PairMsg.Denied("rate", 30), PairProto.parse(JSONObject("""{"t":"labs.pair.denied","reason":"rate","retryAfter":30}""")))
        assertEquals(PairMsg.Pending(60), PairProto.parse(JSONObject("""{"t":"labs.pair.pending","timeout":60}""")))
        assertEquals(null, PairProto.parse(JSONObject("""{"t":"labs.pair.hi","pub":"x"}""")))
        val hello = JSONObject(PairProto.hello(phonePub, "x".repeat(80), cn, "s"))
        assertEquals("labs.pair.hello", hello.getString("t"))
        assertEquals(64, hello.getString("name").length)
    }

    /** U12: a pinned fingerprint that differs ends in the "new key" prompt's error, never a silent connect. */
    @Test fun pinMismatchAsksTheUser() {
        val other = SeedSigner(Random(9).nextBytes(32))
        val st = machine(pin = PairProto.fingerprint(other.pub)).onPair(hi()) as PairState.Failed
        assertEquals("helper-key", st.code)
        assertTrue(HostKeyChange.isKeyMismatch(st.code, st.msg))
    }

    /** Ephemeral Mac (Keychain unavailable): per-process key accepted for the session only, flagged so the pin is kept, and it always asks on the Mac. */
    @Test fun ephemeralKeyIsSessionOnly() {
        val other = SeedSigner(Random(9).nextBytes(32))
        val m = machine(pin = PairProto.fingerprint(other.pub), allowPrompt = false)
        val v = m.onPair(hi().copy(ephemeral = true)) as PairState.Verified
        assertTrue(v.helper.ephemeral)
        assertTrue("asks on the Mac even for a silent reconnect", m.onPair(PairMsg.Pending(60)) is PairState.Pending)
        // a stale pin never rescues a bad signature
        val bad = hi().copy(ephemeral = true, sig = "00".repeat(64))
        assertEquals("helper-key", (machine(pin = PairProto.fingerprint(other.pub)).onPair(bad) as PairState.Failed).code)
    }

    @Test fun unknownHelloFieldsAreIgnored() {
        val o = JSONObject("""{"t":"labs.pair.hi","pub":"${mac.pub.toHex()}","fp":"$macFp","sig":"${"00".repeat(64)}","host":"Mac","hostId":"$hostId","ephemeral":true,"newKey":true,"prevFp":"x","future":1}""")
        val h = PairProto.parse(o) as PairMsg.Hi
        assertTrue(h.ephemeral && h.newKey)
        assertEquals(false, (PairProto.parse(JSONObject("""{"t":"labs.pair.hi","pub":"a","fp":"b","sig":"c","hostId":"d"}""")) as PairMsg.Hi).ephemeral)
    }

    /** Classic token `auth` error `keychain-unavailable`: its own message, not "no longer trusts this phone"; the token is never touched here. */
    @Test fun keychainUnavailableHasItsOwnCopy() {
        assertTrue(HostKeyChange.EPHEMERAL_TEXT.contains("Keychain unavailable"))
        assertEquals("keychain-unavailable", HostKeyChange.KEYCHAIN_UNAVAILABLE)
    }
}
