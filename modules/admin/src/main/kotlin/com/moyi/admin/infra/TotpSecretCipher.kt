package com.moyi.admin.infra

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals TOTP secrets at rest (spec §6): AES-256-GCM, a fresh 96-bit nonce per
 * encryption. Stored form is `base64(nonce || ciphertext || tag)`. A changed byte
 * or a wrong key fails the tag check with `AEADBadTagException`.
 */
internal class TotpSecretCipher(
    key: ByteArray,
) {
    private val key = SecretKeySpec(key.also { require(it.size == KEY_BYTES) { "AES-256 key must be $KEY_BYTES bytes" } }, "AES")
    private val random = SecureRandom()

    fun encrypt(secret: ByteArray): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce)) }
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(secret))
    }

    fun decrypt(stored: String): ByteArray {
        val raw = Base64.getDecoder().decode(stored)
        require(raw.size > NONCE_BYTES) { "sealed secret is too short" }
        val cipher =
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES))
            }
        return cipher.doFinal(raw, NONCE_BYTES, raw.size - NONCE_BYTES)
    }

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
