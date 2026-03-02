package com.slick.tactical.engine.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.slick.tactical.util.SlickConstants
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts and decrypts Protobuf payload bytes using AES-256-GCM backed by Android Keystore.
 *
 * All P2P packets must pass through this manager before transmission.
 * IV (12 bytes) is prepended to the ciphertext for the receiver to extract.
 */
@Singleton
class SecurePayloadManager @Inject constructor() {

    private val algorithm = "AES/GCM/NoPadding"
    private val keyAlias = "slick_p2p_payload_key_v1"

    /**
     * Encrypts [plaintext] bytes using AES-256-GCM.
     * Returns: [IV (12 bytes)] + [ciphertext + GCM tag].
     *
     * @return Result containing encrypted payload, or failure with cause
     */
    fun encrypt(plaintext: ByteArray): Result<ByteArray> {
        return try {
            val key = getOrCreateKey().getOrThrow()
            val cipher = Cipher.getInstance(algorithm)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            Result.success(iv + ciphertext)
        } catch (e: Exception) {
            Timber.e(e, "Payload encryption failed")
            Result.failure(Exception("Encryption failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Decrypts payload bytes produced by [encrypt].
     * Extracts the prepended IV, then decrypts the remaining ciphertext.
     *
     * @return Result containing plaintext bytes, or failure with cause
     */
    fun decrypt(encryptedPayload: ByteArray): Result<ByteArray> {
        if (encryptedPayload.size <= SlickConstants.AES_GCM_IV_LENGTH_BYTES) {
            return Result.failure(IllegalArgumentException("Payload too short to contain IV"))
        }
        return try {
            val key = getOrCreateKey().getOrThrow()
            val iv = encryptedPayload.sliceArray(0 until SlickConstants.AES_GCM_IV_LENGTH_BYTES)
            val ciphertext = encryptedPayload.sliceArray(SlickConstants.AES_GCM_IV_LENGTH_BYTES until encryptedPayload.size)
            val cipher = Cipher.getInstance(algorithm)
            val spec = GCMParameterSpec(SlickConstants.AES_GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            Result.success(cipher.doFinal(ciphertext))
        } catch (e: Exception) {
            Timber.e(e, "Payload decryption failed")
            Result.failure(Exception("Decryption failed: ${e.localizedMessage}", e))
        }
    }

    /**
     * Retrieves the AES key from Android Keystore, generating it if it doesn't exist.
     * Never call this in Application.onCreate() -- Keystore may not be unlocked yet.
     */
    private fun getOrCreateKey(): Result<SecretKey> {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                val key = (keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
                Result.success(key)
            } else {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                keyGen.init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                val key = keyGen.generateKey()
                Timber.d("P2P encryption key generated in AndroidKeyStore")
                Result.success(key)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to access AndroidKeyStore")
            Result.failure(Exception("KeyStore access failed: ${e.localizedMessage}", e))
        }
    }
}
