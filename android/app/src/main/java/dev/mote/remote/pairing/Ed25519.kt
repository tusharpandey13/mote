package dev.mote.remote.pairing

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.math.ec.rfc8032.Ed25519 as Bc

/**
 * Ed25519 (RFC 8032) on vetted implementations, no hand-rolled curve math. Verification uses
 * BouncyCastle's lightweight RFC 8032 code on every API level (see [verify]). Seed signing (only the fallback when the Keystore can't hold an Ed25519 key,
 * see [Identity]) uses BouncyCastle: the platform provider can't load a raw seed below API 33.
 */
object Ed25519 {
    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == Bc.SECRET_KEY_SIZE)
        return ByteArray(Bc.PUBLIC_KEY_SIZE).also { Bc.generatePublicKey(seed, 0, it, 0) }
    }

    fun sign(seed: ByteArray, msg: ByteArray): ByteArray {
        require(seed.size == Bc.SECRET_KEY_SIZE)
        return ByteArray(Bc.SIGNATURE_SIZE).also { Bc.sign(seed, 0, msg, 0, msg.size, it, 0) }
    }

    /**
     * BouncyCastle on every API level. 0.6.0 used the platform provider on API 33+, but Android's
     * `KeyFactory("Ed25519")` doesn't take an X.509 Ed25519 key there (it works on the JDK, so JVM
     * tests passed): every helper signature failed as "Bad helper signature" on real phones.
     */
    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = libraryVerify(pub, msg, sig)

    /** BouncyCastle path (API < 33). */
    internal fun libraryVerify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = runCatching {
        pub.size == Bc.PUBLIC_KEY_SIZE && sig.size == Bc.SIGNATURE_SIZE && Bc.verify(sig, 0, pub, 0, msg, 0, msg.size)
    }.getOrDefault(false)

    /** Platform path (API 33+; the JDK's EdDSA in JVM tests). */
    internal fun platformVerify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = runCatching {
        if (pub.size != 32 || sig.size != 64) return false
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + pub))
        Signature.getInstance("Ed25519").run { initVerify(key); update(msg); verify(sig) }
    }.getOrDefault(false)

    /** X.509 SubjectPublicKeyInfo header for a raw Ed25519 key (OID 1.3.101.112). */
    private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
}
