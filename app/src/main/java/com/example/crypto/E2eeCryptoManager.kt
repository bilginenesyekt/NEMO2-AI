package com.example.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val ciphertextB64: String,
    val ivB64: String
)

/**
 * Client-side End-to-End Encryption (AES-256-GCM) vault manager.
 *
 * - Generates a 256-bit Data Encryption Key (DEK) wrapped by an Android Keystore Master Key.
 * - Supports PBKDF2WithHmacSHA256 passphrase derivation for portable cross-device cloud backup & recovery.
 * - All chat titles, previews, messages, persona instructions, and recent actions are encrypted before
 *   leaving the device.
 */
class E2eeCryptoManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    @Volatile
    private var cachedDek: SecretKey? = null

    private fun getOrCreateKeystoreMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    @Synchronized
    private fun getOrCreateDek(): SecretKey {
        cachedDek?.let { return it }

        val wrappedB64 = prefs.getString(KEY_WRAPPED_DEK, null)
        val wrapIvB64 = prefs.getString(KEY_WRAPPED_DEK_IV, null)

        if (!wrappedB64.isNullOrBlank() && !wrapIvB64.isNullOrBlank()) {
            try {
                val masterKey = getOrCreateKeystoreMasterKey()
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                val iv = Base64.decode(wrapIvB64, Base64.NO_WRAP)
                cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                val dekBytes = cipher.doFinal(Base64.decode(wrappedB64, Base64.NO_WRAP))
                val restored = SecretKeySpec(dekBytes, "AES")
                cachedDek = restored
                return restored
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unwrap stored DEK, regenerating vault key", e)
            }
        }

        val rawDek = ByteArray(32).also { secureRandom.nextBytes(it) }
        val newDek = SecretKeySpec(rawDek, "AES")
        persistDekWrapped(newDek)
        cachedDek = newDek
        return newDek
    }

    private fun persistDekWrapped(dek: SecretKey) {
        val masterKey = getOrCreateKeystoreMasterKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val wrapped = cipher.doFinal(dek.encoded)
        val ivB64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val wrappedB64 = Base64.encodeToString(wrapped, Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_WRAPPED_DEK, wrappedB64)
            .putString(KEY_WRAPPED_DEK_IV, ivB64)
            .apply()
    }

    fun encrypt(plainText: String): EncryptedPayload {
        val dek = getOrCreateDek()
        return encryptWithKey(plainText, dek)
    }

    fun decrypt(ciphertextB64: String, ivB64: String): String {
        if (ciphertextB64.isBlank() || ivB64.isBlank()) return ""
        return try {
            val dek = getOrCreateDek()
            decryptWithKey(ciphertextB64, ivB64, dek)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed for payload", e)
            "[Encrypted with another vault key]"
        }
    }

    fun encryptWithKey(plainText: String, key: SecretKey): EncryptedPayload {
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            ciphertextB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    fun decryptWithKey(ciphertextB64: String, ivB64: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(Base64.decode(ciphertextB64, Base64.NO_WRAP))
        return String(plainBytes, Charsets.UTF_8)
    }

    /**
     * Derives a 256-bit AES key from a user E2EE recovery passphrase and stores it wrapped in Keystore,
     * returning (saltB64, verifierCipherB64, verifierIvB64) for cloud verification.
     */
    @Synchronized
    fun configurePassphraseKey(passphrase: String): Triple<String, String, String> {
        require(passphrase.length >= 6) { "Passphrase must be at least 6 characters" }
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val derived = deriveKeyFromPassphrase(passphrase, salt)
        persistDekWrapped(derived)
        cachedDek = derived
        val verifier = encryptWithKey(VERIFIER_TOKEN, derived)
        return Triple(
            Base64.encodeToString(salt, Base64.NO_WRAP),
            verifier.ciphertextB64,
            verifier.ivB64
        )
    }

    /**
     * Unlocks an existing cloud backup using the user's E2EE recovery passphrase.
     */
    @Synchronized
    fun unlockWithPassphrase(
        passphrase: String,
        saltB64: String,
        verifierCipherB64: String,
        verifierIvB64: String
    ): Boolean {
        return try {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val candidate = deriveKeyFromPassphrase(passphrase, salt)
            val decrypted = decryptWithKey(verifierCipherB64, verifierIvB64, candidate)
            if (decrypted == VERIFIER_TOKEN) {
                persistDekWrapped(candidate)
                cachedDek = candidate
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Passphrase unlock failed", e)
            false
        }
    }

    fun exportRecoveryKeyHex(): String {
        val dek = getOrCreateDek()
        return Base64.encodeToString(dek.encoded, Base64.NO_WRAP)
    }

    @Synchronized
    fun importRecoveryKeyBase64(keyB64: String): Boolean {
        return try {
            val bytes = Base64.decode(keyB64.trim(), Base64.NO_WRAP)
            if (bytes.size != 32) return false
            val imported = SecretKeySpec(bytes, "AES")
            persistDekWrapped(imported)
            cachedDek = imported
            true
        } catch (e: Exception) {
            Log.w(TAG, "Invalid recovery key import", e)
            false
        }
    }

    fun getKeyFingerprint(): String {
        val dek = getOrCreateDek()
        val digest = MessageDigest.getInstance("SHA-256").digest(dek.encoded)
        return digest.take(8).joinToString(":") { "%02X".format(it) }
    }

    private fun deriveKeyFromPassphrase(passphrase: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    companion object {
        private const val TAG = "E2eeCrypto"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "nemo_keystore_master_key_v1"
        private const val PREFS_NAME = "nemo_e2ee_vault"
        private const val KEY_WRAPPED_DEK = "wrapped_dek"
        private const val KEY_WRAPPED_DEK_IV = "wrapped_dek_iv"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val PBKDF2_ITERATIONS = 100_000
        private const val VERIFIER_TOKEN = "NEMO_E2EE_VERIFIED_V1"
    }
}
