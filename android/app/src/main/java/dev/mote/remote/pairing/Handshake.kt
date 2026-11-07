package dev.mote.remote.pairing

import dev.mote.remote.net.HelperError
import dev.mote.remote.net.Msg
import dev.mote.remote.net.WsSession
import dev.mote.remote.net.toHex
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** The phone's Ed25519 identity: raw 32-byte public key and a signer (Keystore or wrapped seed). */
interface Signer {
    val pub: ByteArray
    fun sign(msg: ByteArray): ByteArray
}

/** A signer over a raw seed (tests, and the wrapped-seed fallback). */
class SeedSigner(private val seed: ByteArray) : Signer {
    override val pub: ByteArray = Ed25519.publicKey(seed)
    override fun sign(msg: ByteArray): ByteArray = Ed25519.sign(seed, msg)
}

/** Helper as proven by `labs.pair.hi`. */
data class HelperKey(val pub: String, val fp: String, val host: String)

sealed interface PairState {
    data object SentHello : PairState
    data class Verified(val helper: HelperKey) : PairState
    /** The Mac shows "Allow <phone>?"; waits up to [timeout] seconds. */
    data class Pending(val helper: HelperKey, val timeout: Int) : PairState
    data class Accepted(val helper: HelperKey, val via: String, val id: String) : PairState
    data class Done(val helper: HelperKey, val via: String, val authed: Msg.Authed) : PairState
    data class Denied(val reason: String, val retryAfter: Int?) : PairState
    data class Failed(val code: String, val msg: String) : PairState

    val terminal get() = this is Done || this is Denied || this is Failed
}

/**
 * Pure client side of the handshake (docs/pairing.md section 3). Fed every helper message in
 * order; anything out of order, a bad helper signature or a key that doesn't match [pinnedFp]
 * (from the BLE info or the stored trust) ends in [PairState.Failed].
 */
class PairMachine(
    private val hostId: String, private val nonce: String, private val cn: String,
    private val clientPub: String, private val pinnedFp: String?, private val allowPrompt: Boolean = true,
) {
    var state: PairState = PairState.SentHello
        private set

    fun onPair(m: PairMsg): PairState { state = next(state, m); return state }
    fun onAuthed(a: Msg.Authed): PairState {
        val s = state
        state = if (s is PairState.Accepted && a.id == s.id) PairState.Done(s.helper, s.via, a) else fail("bad", "Unexpected authed")
        return state
    }
    fun onErr(code: String, msg: String): PairState { if (!state.terminal) state = PairState.Failed(code, msg); return state }

    private fun fail(code: String, msg: String) = PairState.Failed(code, msg)

    private fun next(s: PairState, m: PairMsg): PairState = when {
        s.terminal -> s
        m is PairMsg.Hi && s == PairState.SentHello -> verify(m)
        m is PairMsg.Pending && s is PairState.Verified ->
            if (allowPrompt) PairState.Pending(s.helper, m.timeout) else fail("revoked", "The Mac no longer trusts this phone")
        m is PairMsg.Ok && s is PairState.Verified -> PairState.Accepted(s.helper, m.via, m.id)
        m is PairMsg.Ok && s is PairState.Pending -> PairState.Accepted(s.helper, m.via, m.id)
        m is PairMsg.Denied && (s is PairState.Verified || s is PairState.Pending) -> PairState.Denied(m.reason, m.retryAfter)
        m is PairMsg.Info -> s
        else -> fail("bad", "Unexpected ${m::class.simpleName} in ${s::class.simpleName}")
    }

    private fun verify(h: PairMsg.Hi): PairState {
        if (h.hostId != hostId) return fail("helper-key", "Another Mac answered")
        val pub = PairProto.hexOrNull(h.pub, 32) ?: return fail("bad", "Malformed helper key")
        val fp = PairProto.fingerprint(pub)
        if (fp != h.fp) return fail("helper-key", "Helper key and fingerprint differ")
        if (pinnedFp != null && pinnedFp != fp) return fail("helper-key", "This Mac's key changed")
        val sig = PairProto.hexOrNull(h.sig, 64) ?: return fail("bad", "Malformed helper signature")
        if (!Ed25519.verify(pub, PairProto.helperProof(hostId, nonce, cn, clientPub), sig)) return fail("helper-key", "Bad helper signature")
        return PairState.Verified(HelperKey(h.pub, fp, h.host))
    }
}

fun newNonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()

/**
 * Runs the handshake on an open [WsSession] (after `hello`). [prox] is the proximity token read over
 * BLE (docs/pairing.md section 4), if any. Returns [PairState.Done] or throws [HelperError] whose
 * code is the helper's deny reason (`far`, `stale`, `blocked`, `bad-proximity`, `denied`, `timeout`,
 * `busy`, `rate`), `helper-key` (wrong Mac or key), `revoked` (prompt needed but [allowPrompt] is
 * false) or `unauth` (instant pairing turned off on the Mac).
 */
suspend fun labsHandshake(
    s: WsSession, me: Signer, name: String, pinnedFp: String?, allowPrompt: Boolean = true,
    prox: String? = null, onState: (PairState) -> Unit = {},
): PairState.Done {
    val hostId = s.hello.hostId ?: throw HelperError("version", "Mote Helper too old for Instant pairing")
    val pub = me.pub.toHex()
    val cn = newNonce()
    val machine = PairMachine(hostId, s.hello.nonce, cn, pub, pinnedFp, allowPrompt)
    s.send(PairProto.hello(pub, name, cn, me.sign(PairProto.clientProof(hostId, s.hello.nonce, pub)).toHex(), prox))
    onState(machine.state)
    while (!machine.state.terminal) {
        val wait = (machine.state as? PairState.Pending)?.let { (it.timeout + 10) * 1000L } ?: 8000L
        val m = withTimeoutOrNull(wait) { s.inbox.receive() } ?: throw HelperError("timeout", "No answer from the Mac")
        val st = when (m) {
            is Msg.Other -> m.raw.takeIf { it.isNotEmpty() }?.let { org.json.JSONObject(it) }?.let(PairProto::parse)?.let(machine::onPair) ?: continue
            is Msg.Authed -> machine.onAuthed(m)
            is Msg.Err -> machine.onErr(m.code, m.msg)
            else -> continue
        }
        onState(st)
    }
    return when (val st = machine.state) {
        is PairState.Done -> st
        is PairState.Denied -> throw HelperError(st.reason, st.reason, st.retryAfter)
        is PairState.Failed -> throw HelperError(st.code, st.msg)
        else -> throw HelperError("bad", "handshake ended in $st")
    }
}
