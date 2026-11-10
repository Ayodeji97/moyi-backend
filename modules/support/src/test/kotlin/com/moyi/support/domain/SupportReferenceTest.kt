package com.moyi.support.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** ADR-0057 decision 11: the person's error reference, 1 to 64 visible ASCII characters (0x21 to 0x7E), nothing else. */
internal class SupportReferenceTest {
    @Test
    fun `1 to 64 visible ASCII characters, every one of them, kept as sent`() {
        SupportReference.of("a").value shouldBe "a"
        SupportReference.of("4bf92f3577b34da6a3ce929d0e0e4736").value shouldBe "4bf92f3577b34da6a3ce929d0e0e4736"
        val everyVisible = (0x21..0x7E).map { it.toChar() }.joinToString("")
        everyVisible.chunked(SupportReference.MAX_CHARACTERS).forEach { SupportReference.of(it).value shouldBe it }
        SupportReference.of("x".repeat(SupportReference.MAX_CHARACTERS)).value.length shouldBe 64
    }

    @Test
    fun `empty, too long, a space, a control character, DEL and anything past ASCII are refused, never repeating it`() {
        listOf(
            "",
            "x".repeat(SupportReference.MAX_CHARACTERS + 1),
            "abc def",
            " abc",
            "abc\t",
            "abc\n",
            "a\u0000b",
            "a\u007Fb",
            "café",
            " abc",
            "a😀",
            "\uD800",
        ).forEach { raw ->
            val refused = shouldThrow<IllegalArgumentException> { SupportReference.of(raw) }
            refused.message shouldBe "must be 1 to 64 visible ASCII characters"
            if (raw.length > 2) refused.message!! shouldNotContain raw
        }
    }

    @Test
    fun `printed by its length alone`() {
        SupportReference.of("trace-Quixotically-1").toString() shouldBe "SupportReference(20 characters)"
    }
}
