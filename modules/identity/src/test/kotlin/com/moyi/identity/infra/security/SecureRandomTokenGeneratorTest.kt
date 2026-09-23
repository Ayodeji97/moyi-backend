package com.moyi.identity.infra.security

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

internal class SecureRandomTokenGeneratorTest {
    private val generator = SecureRandomTokenGenerator()

    @Test
    fun `a secret is 43 URL-safe characters`() {
        // 32 bytes, base64url, no padding. The alphabet matters as much as the
        // length: a `+` or `/` would need escaping in the query string the
        // link puts it in, and a trailing `=` gets eaten by some mail clients.
        repeat(100) {
            val secret = generator.verificationSecret().value
            secret.length shouldBe SecureRandomTokenGenerator.ENCODED_LENGTH
            secret shouldMatch Regex("^[A-Za-z0-9_-]{43}$")
        }
    }

    @Test
    fun `secrets do not repeat`() {
        // Not a proof of randomness — nothing in a unit test is — but a
        // generator that reused its buffer or seeded from a constant would
        // fail this immediately, and that is the bug worth catching.
        List(1_000) { generator.verificationSecret().value }.toSet() shouldHaveSize 1_000
    }
}
