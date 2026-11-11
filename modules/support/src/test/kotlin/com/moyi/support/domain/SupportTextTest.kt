package com.moyi.support.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** ADR-0057 decision 3: one rule for both fields, measured in code points, as V45's `char_length` measures it. */
internal class SupportTextTest {
    @Test
    fun `a subject is 1 to 120 code points, an emoji counting once`() {
        SupportSubject.of("a").value shouldBe "a"
        SupportSubject.of("x".repeat(SupportSubject.MAX_CHARACTERS)).value.length shouldBe 120
        SupportSubject.of("😀".repeat(SupportSubject.MAX_CHARACTERS)).value.length shouldBe 240
        shouldThrow<IllegalArgumentException> { SupportSubject.of("x".repeat(SupportSubject.MAX_CHARACTERS + 1)) }
            .message shouldBe "must be at most 120 characters"
    }

    @Test
    fun `a body is 1 to 5000 code points`() {
        SupportBody.of("y".repeat(SupportBody.MAX_CHARACTERS)).value.length shouldBe 5000
        shouldThrow<IllegalArgumentException> { SupportBody.of("y".repeat(SupportBody.MAX_CHARACTERS + 1)) }
            .message shouldBe "must be at most 5000 characters"
    }

    @Test
    fun `empty, blank, NUL and an unpaired surrogate are refused, and the refusal never repeats the text`() {
        listOf("", " ", " \n\t", "a\u0000b", "\uD800x", "x\uDC00").forEach { raw ->
            val refused = shouldThrow<IllegalArgumentException> { SupportBody.of(raw) }
            if (raw.isNotEmpty() && raw.isNotBlank()) refused.message!! shouldNotContain raw
            shouldThrow<IllegalArgumentException> { SupportSubject.of(raw) }
        }
    }

    @Test
    fun `the text is kept exactly as sent, and printed by its length alone`() {
        val raw = "  My partner's account is locked …  "
        SupportBody.of(raw).value shouldBe raw
        SupportBody.of(raw).toString() shouldBe "SupportBody(36 code points)"
        SupportSubject.of(raw).toString() shouldNotContain "locked"
    }
}
