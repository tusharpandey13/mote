package dev.mote.remote.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing tokens (the HMAC keys). Preferred: import the token into the Android Keystore as a
 * non-exportable HmacSHA256 key, so it never re-enters app memory after pairing. Fallback, for
 * Keystores that refuse the import: AES-256-GCM wrap it with a Keystore key and keep the ciphertext
 * in [SavedHost.wrapped]. The app excludes its data from backup, so neither moves to a new phone.
 */
object Vault {
    private const val TAG = "MoteVault"
    private const val KS = "AndroidKeyStore"
    private const val WRAP = "mote.wrap"
    private fun alias(hostId: String) = "mote.hmac.$hostId"
    private fun keystore() = KeyStore.getInstance(KS).apply { load(null) }

    /** Stores [token] for [hostId]. Returns null when imported, else the wrapped blob to persist. */
    fun store(hostId: String, token: ByteArray): String? {
        val ks = keystore()
        try {
            ks.setEntry(
                alias(hostId), KeyStore.SecretKeyEntry(SecretKeySpec(token, KeyProperties.KEY_ALGORITHM_HMAC_SHA256)),
                KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build(),
            )
            // Prove it works before relying on it (some OEM Keystores accept the import but can't use it).
            val probe = "mote-probe"
            if (sign(ks.getKey(alias(hostId), null) as SecretKey, probe) == Proto.hmacHex(token, probe)) return null
            Log.w(TAG, "imported HMAC key gave a different MAC; wrapping instead")
        } catch (e: Exception) {
            Log.w(TAG, "HMAC key import failed; wrapping instead", e)
        }
        runCatching { ks.deleteEntry(alias(hostId)) }
        return wrap(token)
    }

    /** A signer for the hello nonce, or null if the credential is gone (re-pair). */
    fun signer(host: SavedHost): ((String) -> String)? {
        host.wrapped?.let { blob ->
            return wrappedSigner(blob, ::unwrap) ?: run { Log.w(TAG, "wrapped token for ${host.hostId} no longer decrypts; re-pair"); null }
        }
        val key = runCatching { keystore().getKey(alias(host.hostId), null) as? SecretKey }.getOrNull() ?: return null
        return { nonce -> sign(key, nonce) }
    }

    /**
     * A signer over a wrapped token, or null when the blob no longer decrypts (wrap key gone from the
     * Keystore, corrupt blob). Checked up front: failing inside auth looked like a dropped socket, so
     * the phone reconnected forever showing "Connecting…" instead of asking to pair again.
     */
    internal fun wrappedSigner(blob: String, unwrap: (String) -> ByteArray): ((String) -> String)? {
        runCatching { unwrap(blob).fill(0) }.getOrElse { return null }
        return { nonce -> unwrap(blob).let { t -> try { Proto.hmacHex(t, nonce) } finally { t.fill(0) } } }
    }

    fun forget(hostId: String) { runCatching { keystore().deleteEntry(alias(hostId)) } }

    private fun sign(key: SecretKey, nonce: String): String =
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(nonce.toByteArray(Charsets.US_ASCII)).toHex()

    private fun wrapKey(): SecretKey {
        (keystore().getKey(WRAP, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        g.init(
            KeyGenParameterSpec.Builder(WRAP, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    private fun wrap(token: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        val ct = c.doFinal(token)
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun unwrap(blob: String): ByteArray {
        val b = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, b, 0, 12))
        return c.doFinal(b, 12, b.size - 12)
    }
}
