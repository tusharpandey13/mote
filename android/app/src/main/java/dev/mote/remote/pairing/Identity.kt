package dev.mote.remote.pairing

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's Ed25519 key for Instant pairing. Preferred: a non-exportable Android Keystore key
 * (Ed25519 needs API 33+ and a KeyMint that supports it; probed once by signing and verifying).
 * Fallback: a random 32-byte seed AES-256-GCM-wrapped by a Keystore key, kept in a private pref
 * (app data is excluded from backup). Signing with the fallback uses [Ed25519].
 */
object Identity {
    private const val TAG = "MoteLabsId"
    private const val KS = "AndroidKeyStore"
    private const val SIGN_ALIAS = "mote.labs.ed25519"
    private const val WRAP_ALIAS = "mote.labs.wrap"
    private const val PREFS = "mote_labs_identity"
    private const val SEED = "seed"

    @Volatile private var cached: Signer? = null

    @Synchronized fun get(c: Context): Signer {
        cached?.let { return it }
        val s = keystoreSigner() ?: wrappedSeed(c)
        cached = s
        return s
    }

    private fun keystore() = KeyStore.getInstance(KS).apply { load(null) }

    private fun keystoreSigner(): Signer? {
        if (Build.VERSION.SDK_INT < 33) return null
        return runCatching {
            val ks = keystore()
            if (!ks.containsAlias(SIGN_ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KS).apply {
                    initialize(
                        KeyGenParameterSpec.Builder(SIGN_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
                            .setDigests(KeyProperties.DIGEST_NONE)
                            .build(),
                    )
                }.generateKeyPair()
            }
            val priv = ks.getKey(SIGN_ALIAS, null) as PrivateKey
            val spki = ks.getCertificate(SIGN_ALIAS).publicKey.encoded
            check(spki.size == 44) { "unexpected Ed25519 public key encoding (${spki.size} bytes)" }
            val pub = spki.copyOfRange(12, 44) // X.509 SubjectPublicKeyInfo: 12-byte header + raw key
            val signer = object : Signer {
                override val pub = pub
                override fun sign(msg: ByteArray): ByteArray = Signature.getInstance("Ed25519").run { initSign(priv); update(msg); sign() }
            }
            val probe = "mote-labs-probe".toByteArray()
            check(Ed25519.verify(pub, probe, signer.sign(probe))) { "Keystore Ed25519 signature didn't verify" }
            signer
        }.onFailure {
            Log.w(TAG, "Keystore Ed25519 unavailable; using a wrapped seed", it)
            runCatching { keystore().deleteEntry(SIGN_ALIAS) }
        }.getOrNull()
    }

    private fun wrappedSeed(c: Context): Signer {
        val prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(SEED, null)?.let { blob ->
            runCatching { return SeedSigner(unwrap(blob)) }.onFailure { Log.w(TAG, "seed unwrap failed; new identity", it) }
        }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit { putString(SEED, wrap(seed)) }
        return SeedSigner(seed)
    }

    private fun wrapKey(): SecretKey {
        (keystore().getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS).apply {
            init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build(),
            )
        }.generateKey()
    }

    private fun wrap(b: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        return Base64.encodeToString(c.iv + c.doFinal(b), Base64.NO_WRAP)
    }

    private fun unwrap(blob: String): ByteArray {
        val b = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, b, 0, 12)) }
        return c.doFinal(b, 12, b.size - 12)
    }
}
