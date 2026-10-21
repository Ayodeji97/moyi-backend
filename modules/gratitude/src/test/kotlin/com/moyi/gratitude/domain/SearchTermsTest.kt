package com.moyi.gratitude.domain

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** `q` (ADR-0039 decision 9): 1 to 100 graphemes as an entry counts them, 1,024 octets, never blank, never NUL. */
internal class SearchTermsTest {
    @Test
    fun `the words are kept exactly as sent`() {
        SearchTerms.of(" Café, ação ")!!.value shouldBe " Café, ação "
    }

    @Test
    fun `blank is not a search, by the Unicode reading of blank`() {
        listOf("", " ", "\t\n", " ", " ", "　").forEach { withClue(it.codePoints().toArray().toList()) { SearchTerms.of(it).shouldBeNull() } }
    }

    @Test
    fun `one to a hundred characters, counted as a person counts them and after trimming`() {
        SearchTerms.of("z").shouldNotBeNull()
        SearchTerms.of("z".repeat(100)).shouldNotBeNull()
        SearchTerms.of("z".repeat(101)).shouldBeNull()
        // A letter and a combining accent is one character: NFKC makes it one, and so does the count.
        SearchTerms.of("é".repeat(100)).shouldNotBeNull()
        SearchTerms.of(" ".repeat(50) + "z" + " ".repeat(50)).shouldNotBeNull()
        SearchTerms.of(FAMILY.repeat(40)).shouldNotBeNull()
    }

    @Test
    fun `at most 1024 octets, whatever the count of characters`() {
        // Forty-one characters and 1,025 octets.
        SearchTerms.of(FAMILY.repeat(41)).shouldBeNull()
        // A hundred characters either way (the acutes all sit on the last z, which NFKC makes one ź):
        // 100 + 2 × 462 = 1,024 octets is taken, and 1,026 is not.
        SearchTerms.of("z".repeat(100) + "́".repeat(462)).shouldNotBeNull()
        SearchTerms.of("z".repeat(100) + "́".repeat(463)).shouldBeNull()
    }

    @Test
    fun `NUL is never a search, because Postgres text cannot hold it`() {
        SearchTerms.of("zq\u0000zq").shouldBeNull()
    }

    @Test
    fun `what was searched for is never printed`() {
        val terms = SearchTerms.of("secret words")!!
        terms.toString() shouldBe "SearchTerms(redacted)"
        "$terms" shouldNotContain "secret"
    }

    private companion object {
        /** One user-perceived character, twenty-five octets. */
        const val FAMILY = "👨‍👩‍👧‍👦"
    }
}
