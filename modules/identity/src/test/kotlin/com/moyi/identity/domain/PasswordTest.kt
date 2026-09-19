package com.moyi.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.text.Normalizer

internal class PasswordTest {
    @Test
    fun `a password shorter than the floor is rejected`() {
        shouldThrow<IllegalArgumentException> { Password.of("a".repeat(Password.MIN_LENGTH - 1)) }
        Password.of("a".repeat(Password.MIN_LENGTH)).value.length shouldBe Password.MIN_LENGTH
    }

    @Test
    fun `the floor is 12 until the breach corpus lands, not the 8 in ADR-0012`() {
        // ADR-0012 lowers the minimum to 8 and states that the shorter floor
        // is paid for by widening the breached-password corpus, and that "the
        // two halves are one decision and MUST NOT be unbundled". This test
        // is what stops the cheap half shipping alone. It fails — correctly —
        // in the PR that adds the corpus, which is the PR that should be
        // changing this number.
        Password.MIN_LENGTH shouldBe 12
    }

    @Test
    fun `a password longer than the ceiling is rejected`() {
        shouldThrow<IllegalArgumentException> { Password.of("a".repeat(Password.MAX_LENGTH + 1)) }
        Password.of("a".repeat(Password.MAX_LENGTH)).value.length shouldBe Password.MAX_LENGTH
    }

    @Test
    fun `there are no composition rules`() {
        // ADR-0012: composition rules reduce entropy in practice, because
        // they push people towards Password1!. A long lowercase passphrase is
        // a better password than most things a rule would force.
        Password.of("correct horse battery staple").value shouldBe "correct horse battery staple"
    }

    @Test
    fun `spaces and unicode are accepted`() {
        Password.of("  leading and trailing  ").value shouldBe "  leading and trailing  "
        Password.of("паролькоторыйдлинный").value.length shouldBe 20
    }

    @Test
    fun `the byte limit binds where the character limit does not`() {
        // 128 characters is the character ceiling, but a four-byte code point
        // makes 128 characters 512 bytes. This is the limit that actually
        // bounds the work handed to the encoder.
        val fourBytesEach = "😀".repeat(Password.MAX_OCTETS / 4 + 1)

        shouldThrow<IllegalArgumentException> { Password.of(fourBytesEach) }
    }

    @Test
    fun `the same password typed two ways hashes as one password`() {
        // The failure this prevents is a user locked out of their own account
        // on their second device: "é" as one precomposed code point and as
        // "e" plus a combining accent look identical and are different bytes.
        val precomposed = "café au lait x9" // U+00E9
        val decomposed = "café au lait x9" // e + U+0301

        Normalizer.isNormalized(decomposed, Normalizer.Form.NFKC) shouldBe false
        Password.of(precomposed).value shouldBe Password.of(decomposed).value
    }

    @Test
    fun `normalisation happens before the length check, not after`() {
        // NFKC changes length — a single "ﬁ" ligature becomes two characters —
        // so checking first and normalising second would accept a password
        // that is not the length it was measured at, and reject ones that are.
        val ligatures = "ﬁ".repeat(Password.MIN_LENGTH - 1)

        Normalizer.normalize(ligatures, Normalizer.Form.NFKC).length shouldBe (Password.MIN_LENGTH - 1) * 2
        Password.of(ligatures).value.length shouldBe (Password.MIN_LENGTH - 1) * 2
    }

    @Test
    fun `a password never prints itself`() {
        val password = Password.of("correct horse battery")

        "$password" shouldBe "Password(redacted)"
        password.toString() shouldNotContain "horse"
    }
}
