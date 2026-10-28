package com.moyi.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The display-name character rule (ADR-0015, amended 2026-10-28): no `Cc`, and no `Cf` but
 * ZWNJ and ZWJ. Every refused character sits *inside* a name, between two letters, so that
 * none of these cases depends on whether the edge's trim would have removed it.
 */
internal class DisplayNamesTest {
    @ParameterizedTest(name = "U+{0} inside a name is refused")
    @ValueSource(
        strings = [
            // Cc: NUL, tab, line feed, unit separator, delete, next line (C1).
            "0000", "0009", "000A", "001F", "007F", "0085",
            // Cf: zero-width space, left-to-right mark, right-to-left override,
            // left-to-right isolate, byte-order mark, soft hyphen, and a tag
            // character (outside the BMP: one code point, two UTF-16 units).
            "200B", "200E", "202E", "2066", "FEFF", "00AD", "E0041",
        ],
    )
    fun `a control or format character is refused`(hex: String) {
        val name = "Ad" + String(Character.toChars(hex.toInt(HEX))) + "a"

        val refused = shouldThrow<IllegalArgumentException> { DisplayNames.check(name) }

        refused.message shouldBe "display name must not contain control or formatting characters"
        // The rule, never the name: an exception message reaches logs.
        refused.message.orEmpty() shouldNotContain name
    }

    @Test
    fun `a Persian name written with a zero-width non-joiner is accepted`() {
        // ZWNJ is how Persian, Urdu and several Indic scripts are spelt
        // correctly. Refusing it would refuse real names.
        accepted("نیک‌نام")
    }

    @Test
    fun `an emoji family joined by zero-width joiners is accepted`() {
        accepted("👨‍👩‍👧")
    }

    @Test
    fun `a flag is accepted`() {
        // Two regional indicators, So rather than Cf.
        accepted("🇳🇬")
    }

    @Test
    fun `accented letters are accepted, precomposed and combining`() {
        accepted("Zoë")
        // A combining diaeresis is Mn, not Cf.
        accepted("Zoë")
    }

    @Test
    fun `a CJK name is accepted`() {
        accepted("王小明")
    }

    @Test
    fun `an ordinary name with spaces and punctuation is accepted`() {
        accepted("Ada O'Brien-Lovelace")
    }

    private fun accepted(name: String) {
        DisplayNames.check(name)
    }

    private companion object {
        const val HEX = 16
    }
}
