package com.moyi.bond.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * FR-022's human-readable code. The alphabet is a product decision
 * (`states.md` §2) with a security consequence (T-06), so both are asserted:
 * the excluded characters, and the size of the space they leave.
 */
internal class InviteCodeTest {
    @Test
    fun `the alphabet is states-md section 2's thirty symbols, without 0 O 1 I L U`() {
        InviteCode.ALPHABET shouldBe "23456789ABCDEFGHJKMNPQRSTVWXYZ"
        InviteCode.ALPHABET.length shouldBe 30
        // The pairs people mis-transcribe reading a code down a phone line,
        // plus U so six random characters cannot spell something unfortunate.
        "0O1ILU".none { it in InviteCode.ALPHABET } shouldBe true
    }

    @Test
    fun `a random code is six characters of the alphabet, every time`() {
        repeat(1_000) { seed ->
            val code = InviteCode.random(Random(seed.toLong()))

            code.value shouldHaveLength InviteCode.LENGTH
            code.value.all { it in InviteCode.ALPHABET } shouldBe true
        }
    }

    @Test
    fun `the generator is a parameter, so a seeded one is reproducible`() {
        // Why `random` is injected rather than a static SecureRandom call: a
        // test can name the code it expects, exactly as DeterministicIdGenerator
        // lets one name an id.
        InviteCode.random(Random(42)) shouldBe InviteCode.random(Random(42))
    }

    @Test
    fun `parse trims and upper-cases what a person typed`() {
        // The code-entry field capitalises automatically (states.md §2), but a
        // pasted link or a lowercase transcription must still work.
        InviteCode.parse("  7kq4mz ") shouldBe InviteCode("7KQ4MZ")
    }

    @Test
    fun `the wrong length is refused by length, before the alphabet is consulted`() {
        shouldThrow<IllegalArgumentException> { InviteCode.parse("7KQ4M") }.message shouldBe "must be 6 characters"
        shouldThrow<IllegalArgumentException> { InviteCode.parse("7KQ4MZZ") }.message shouldBe "must be 6 characters"
    }

    @Test
    fun `an excluded character is refused with a sentence that names the rule`() {
        // states.md §2: tolerate the excluded characters by rejecting them with
        // a clear message rather than silently mapping or failing.
        shouldThrow<IllegalArgumentException> { InviteCode.parse("7KQ40Z") }.message shouldBe
            "may only use the digits 2 to 9 and letters A to Z, never 0, O, 1, I, L or U"
    }
}
