package dev.mote.remote.pairing

import dev.mote.remote.net.Proto
import dev.mote.remote.net.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Instant pairing wire format (docs/pairing.md). Pure JVM. Wire names keep the `labs.pair.` prefix of the 0.5 preview. */
object PairProto {
    const val VERSION = "mote-labs-pair-v1"
    /** Mote service (docs/protocol.md section 3) and the pairing characteristics inside it. */
    const val SERVICE_UUID = "3abad8f4-b472-41fc-bfc5-6f645f33564b"
    const val INFO_UUID = "6d6f7465-0001-4c61-6273-50616972696e"
    /** Rotating proximity token (`<window>:<32 hex>`), readable only over BLE. */
    const val PROX_UUID = "6d6f7465-0002-4c61-6273-50616972696e"
    private val PROX = Regex("^[0-9]{1,12}:[0-9a-f]{32}$")

    /** The proximity characteristic's value, or null when it isn't one (the bytes are untrusted). */
    fun proxOrNull(bytes: ByteArray?): String? = bytes?.toString(Charsets.UTF_8)?.trim()?.takeIf(PROX::matches)

    /** First 16 bytes of SHA-256(raw public key), hex. */
    fun fingerprint(pub: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(pub).copyOf(16).toHex()

    /** "3f2a 9c01 …" for showing a fingerprint to a person. */
    fun groupFp(fp: String): String = fp.chunked(4).joinToString(" ")

    fun clientProof(hostId: String, nonce: String, clientPub: String): ByteArray =
        "$VERSION|client|$hostId|$nonce|$clientPub".toByteArray(Charsets.UTF_8)

    fun helperProof(hostId: String, nonce: String, cn: String, clientPub: String): ByteArray =
        "$VERSION|helper|$hostId|$nonce|$cn|$clientPub".toByteArray(Charsets.UTF_8)

    fun hello(pub: String, name: String, cn: String, sig: String, prox: String? = null): String =
        JSONObject().put("t", "labs.pair.hello").put("pub", pub).put("name", name.take(64)).put("cn", cn).put("sig", sig)
            .putOpt("prox", prox).toString()

    fun info(): String = JSONObject().put("t", "labs.pair.info").toString()

    /** `labs.pair.*` from the helper, or null for anything else / malformed. */
    fun parse(o: JSONObject): PairMsg? = runCatching {
        when (o.optString("t")) {
            "labs.pair.hi" -> PairMsg.Hi(o.getString("pub"), o.getString("fp"), o.getString("sig"), o.optString("host"), o.getString("hostId"), o.optBoolean("ephemeral", false), o.optBoolean("newKey", false))
            "labs.pair.pending" -> PairMsg.Pending(o.optInt("timeout", 60))
            "labs.pair.ok" -> PairMsg.Ok(o.getString("via"), o.getString("id"), o.optString("name"))
            "labs.pair.denied" -> PairMsg.Denied(o.optString("reason", "denied"), if (o.has("retryAfter")) o.optInt("retryAfter") else null)
            "labs.pair.info" -> BleInfo.from(o)?.let { PairMsg.Info(it) }
            else -> null
        }
    }.getOrNull()

    fun hexOrNull(s: String, bytes: Int): ByteArray? =
        if (s.length == bytes * 2 && s.all { it in '0'..'9' || it in 'a'..'f' }) Proto.hexToBytes(s) else null
}

sealed interface PairMsg {
    data class Hi(val pub: String, val fp: String, val sig: String, val host: String, val hostId: String, val ephemeral: Boolean = false, val newKey: Boolean = false) : PairMsg
    data class Pending(val timeout: Int) : PairMsg
    data class Ok(val via: String, val id: String, val name: String) : PairMsg
    data class Denied(val reason: String, val retryAfter: Int?) : PairMsg
    data class Info(val info: BleInfo) : PairMsg
}

/**
 * The helper's BLE info characteristic (and `labs.pair.info`): who the Mac is and where to reach it.
 * Addresses are hints only; the handshake proves the key behind [fp].
 */
data class BleInfo(
    val hostId: String, val name: String, val fp: String, val lan: List<String> = emptyList(),
    val tailnetDns: String? = null, val tailnetIp: String? = null, val port: Int = Proto.DEFAULT_PORT,
) {
    fun toJson(): String = JSONObject().put("v", 1).put("id", hostId).put("n", name).put("fp", fp).put("lan", JSONArray(lan))
        .putOpt("ts", tailnetDns).putOpt("t4", tailnetIp).put("p", port).toString()

    companion object {
        private val HEX16 = Regex("^[0-9a-f]{16}$")
        private val HEX32 = Regex("^[0-9a-f]{32}$")
        private val ADDR = Regex("^[A-Za-z0-9.:\\-]{1,253}$")

        fun parse(bytes: ByteArray): BleInfo? = parse(String(bytes, Charsets.UTF_8))
        fun parse(json: String): BleInfo? = runCatching { from(JSONObject(json)) }.getOrNull()

        /** Strict: unknown version, bad ids or addresses that aren't plain host names drop the entry. */
        fun from(o: JSONObject): BleInfo? {
            if (o.optInt("v", 0) != 1) return null
            val id = o.optString("id").takeIf { HEX16.matches(it) } ?: return null
            val fp = o.optString("fp").takeIf { HEX32.matches(it) } ?: return null
            val name = o.optString("n").take(64).ifBlank { "Mac" }
            val arr = o.optJSONArray("lan")
            val lan = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf(ADDR::matches) }.take(4)
            fun addr(k: String) = o.optString(k).ifEmpty { null }?.takeIf(ADDR::matches)
            val port = o.optInt("p", Proto.DEFAULT_PORT).takeIf { it in 1..65535 } ?: return null
            return BleInfo(id, name, fp, lan, addr("ts"), addr("t4"), port)
        }
    }
}
