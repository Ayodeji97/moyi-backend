package com.moyi.admin.infra

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import javax.crypto.AEADBadTagException

class TotpSecretCipherTest {
    private val cipher = TotpSecretCipher(ByteArray(32) { it.toByte() })
    private val secret = "12345678901234567890".toByteArray()

    @Test
    fun `round trips`() {
        assertArrayEquals(secret, cipher.decrypt(cipher.encrypt(secret)))
    }

    @Test
    fun `two encryptions of one secret differ`() {
        assertNotEquals(cipher.encrypt(secret), cipher.encrypt(secret))
    }

    @Test
    fun `a flipped byte fails the tag`() {
        val raw = Base64.getDecoder().decode(cipher.encrypt(secret))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertThrows<AEADBadTagException> { cipher.decrypt(Base64.getEncoder().encodeToString(raw)) }
    }

    @Test
    fun `a different key cannot decrypt`() {
        val other = TotpSecretCipher(ByteArray(32) { (it + 1).toByte() })
        assertThrows<AEADBadTagException> { other.decrypt(cipher.encrypt(secret)) }
    }
}
