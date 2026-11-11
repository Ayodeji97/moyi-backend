package com.moyi.admin.infra

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.spec.SecretKeySpec

internal class TotpTest {
    private val secret = "12345678901234567890".toByteArray()

    private fun totpAt(epochSecond: Long) = Totp(Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC))

    private fun codeAt(epochSecond: Long) =
        TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6)
            .generateOneTimePasswordString(SecretKeySpec(secret, "HmacSHA1"), Instant.ofEpochSecond(epochSecond))

    // RFC 6238 Appendix B, SHA-1: T=59 -> 94287082; T=1111111109 -> 07081804; T=1111111111 -> 14050471
    @Test
    fun `RFC 6238 vector at T=59 is step 1`() {
        assertEquals(1L, totpAt(59).matchingStep(secret, "287082"))
    }

    @Test
    fun `adjacent steps match and return their own step`() {
        // clock at step 1 (T=59): step 0 code is the one for T=0, step 2 for T=60
        val now = totpAt(59)
        val previous = codeAt(0)
        val next = codeAt(60)
        assertEquals(0L, now.matchingStep(secret, previous))
        assertEquals(2L, now.matchingStep(secret, next))
    }

    @Test
    fun `two steps away is refused`() {
        val clock = totpAt(300) // step 10
        assertNull(clock.matchingStep(secret, codeAt(240))) // step 8
        assertNull(clock.matchingStep(secret, codeAt(360))) // step 12
        assertEquals(9L, clock.matchingStep(secret, codeAt(270)))
    }

    @Test
    fun `malformed codes give null`() {
        val now = totpAt(59)
        assertNull(now.matchingStep(secret, "28708"))
        assertNull(now.matchingStep(secret, "2870822"))
        assertNull(now.matchingStep(secret, "28708a"))
        assertNull(now.matchingStep(secret, ""))
    }

    @Test
    fun `otpauth uri has the fixed shape`() {
        val uri = totpAt(0).otpauthUri(secret, "op@example.com", "Moyi admin")
        assertEquals(
            "otpauth://totp/Moyi%20admin:op%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" + // gitleaks:allow (RFC 6238 test seed)
                "&issuer=Moyi%20admin&algorithm=SHA1&digits=6&period=30",
            uri,
        )
    }

    @Test
    fun `a new secret is 20 random bytes`() {
        val totp = totpAt(0)
        assertEquals(20, totp.newSecret().size)
        assertTrue(!totp.newSecret().contentEquals(totp.newSecret()))
    }
}
