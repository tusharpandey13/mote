package dev.mote.remote.labs.pairing

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 (RFC 8032), pure Kotlin on BigInteger: Android only has platform Ed25519 from API 33,
 * the app's minSdk is 28. A direct port of the RFC's reference code: not constant-time, so it is
 * used for verification everywhere and for signing only when the Keystore can't hold the key
 * (the seed is then never on the wire and signing happens once per connect). A few ms per call.
 */
object Ed25519 {
    private val TWO: BigInteger = BigInteger.valueOf(2) // BigInteger.TWO is API 33+
    private val P: BigInteger = TWO.pow(255) - BigInteger.valueOf(19)
    private val L: BigInteger = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * inv(BigInteger.valueOf(121666)) % P
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)
    private val G: Pt = run {
        val y = BigInteger.valueOf(4) * inv(BigInteger.valueOf(5)) % P
        val x = recoverX(y, 0)!!
        Pt(x, y, BigInteger.ONE, x * y % P)
    }
    private val ZERO = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** Extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun inv(a: BigInteger) = a.modPow(P - TWO, P)

    private fun add(p: Pt, q: Pt): Pt {
        val a = (p.y - p.x) * (q.y - q.x) % P
        val b = (p.y + p.x) * (q.y + q.x) % P
        val c = TWO * p.t * q.t * D % P
        val d = TWO * p.z * q.z % P
        val e = b - a; val f = d - c; val g = d + c; val h = b + a
        return Pt((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    private fun mul(s: BigInteger, p0: Pt): Pt {
        var q = ZERO; var p = p0; var k = s
        while (k.signum() > 0) {
            if (k.testBit(0)) q = add(q, p)
            p = add(p, p)
            k = k.shiftRight(1)
        }
        return q
    }

    private fun equal(p: Pt, q: Pt): Boolean =
        (p.x * q.z - q.x * p.z).mod(P).signum() == 0 && (p.y * q.z - q.y * p.z).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = ((y * y - BigInteger.ONE) * inv(D * y * y + BigInteger.ONE)).mod(P)
        if (x2.signum() == 0) return if (sign == 1) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - x2).mod(P).signum() != 0) x = x * SQRT_M1 % P
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P - x
        return x
    }

    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
    private fun toLe(n: BigInteger, len: Int = 32): ByteArray {
        val be = n.toByteArray().let { if (it.size > len) it.copyOfRange(it.size - len, it.size) else it }
        val out = ByteArray(len)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }

    private fun compress(p: Pt): ByteArray {
        val zi = inv(p.z)
        val x = p.x * zi % P; val y = p.y * zi % P
        val out = toLe(y)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decompress(b: ByteArray): Pt? {
        if (b.size != 32) return null
        val sign = (b[31].toInt() shr 7) and 1
        val yb = b.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        val y = le(yb)
        val x = recoverX(y, sign) ?: return null
        return Pt(x, y, BigInteger.ONE, x * y % P)
    }

    private fun sha512(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run { parts.forEach(::update); digest() }

    private fun expand(seed: ByteArray): Pair<BigInteger, ByteArray> {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val h = sha512(seed)
        val a = h.copyOfRange(0, 32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = ((a[31].toInt() and 127) or 64).toByte()
        return le(a) to h.copyOfRange(32, 64)
    }

    /** Raw 32-byte public key for a 32-byte seed (private key). */
    fun publicKey(seed: ByteArray): ByteArray = compress(mul(expand(seed).first, G))

    fun sign(seed: ByteArray, msg: ByteArray): ByteArray {
        val (a, prefix) = expand(seed)
        val pub = compress(mul(a, G))
        val r = le(sha512(prefix, msg)).mod(L)
        val rs = compress(mul(r, G))
        val h = le(sha512(rs, pub, msg)).mod(L)
        val s = (r + h * a).mod(L)
        return rs + toLe(s)
    }

    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = runCatching {
        if (pub.size != 32 || sig.size != 64) return false
        val a = decompress(pub) ?: return false
        val rb = sig.copyOfRange(0, 32)
        val r = decompress(rb) ?: return false
        val s = le(sig.copyOfRange(32, 64))
        if (s >= L) return false
        val h = le(sha512(rb, pub, msg)).mod(L)
        equal(mul(s, G), add(r, mul(h, a)))
    }.getOrDefault(false)
}
