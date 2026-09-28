package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

internal class EntryTextTest {
    @Test
    fun `five hundred graphemes are accepted however many code points they cost`() {
        // A ZWJ couple emoji ("man" + U+200D + "woman") is one grapheme built
        // from three code points, 11 UTF-8 bytes. FR-041 names the case a
        // naive code-point count would get wrong: 500 of these is 1,500 code
        // points, well past a count-based limit tuned for plain text, and
        // legitimately typed all the same — so the cap has to count graphemes,
        // not code points, and 500 of them (5,500 bytes) has to fit under the
        // octet cap too, with room to spare.
        val couple = "👨‍👩"
        EntryText.of(couple.repeat(500)).value.shouldNotBeEmpty()
        shouldThrow<IllegalArgumentException> { EntryText.of(couple.repeat(501)) }
    }

    @Test
    fun `an entry that is only whitespace is refused, whatever kind of space it is`() {
        // ADR-0029 §13: `U+00A0` is not blank to Java and is blank to Kotlin.
        for (blank in listOf("", " ", " ", " ", "\n\t")) {
            shouldThrow<IllegalArgumentException> { EntryText.of(blank) }
        }
    }

    @Test
    fun `the octet cap refuses what the grapheme count allows`() {
        // Graphemes are unbounded in byte length. Without this a single entry
        // could reach megabytes and flow into text_search, the stored tsvector,
        // the outbox payload and the 256 KB response cap.
        val heavy = "👨‍👩‍👧‍👦"
        val text = heavy.repeat(400)
        text.toByteArray(Charsets.UTF_8).size shouldBeGreaterThan EntryText.MAX_OCTETS
        shouldThrow<IllegalArgumentException> { EntryText.of(text) }
    }

    @Test
    fun `text is NFKC-normalised and trimmed, and otherwise left exactly as written`() {
        EntryText.of("  thank you  ").value shouldBe "thank you"
        // Doc 04 §7: stored raw and unmodified. No case folding, no collapsing
        // of internal whitespace, no stripping of emoji.
        EntryText.of("Thank  YOU 🙏").value shouldBe "Thank  YOU 🙏"
    }

    @Test
    fun `an entry never prints itself`() {
        // Doc 18 §5/§9. Mirrors PasswordTest's `a password never prints
        // itself` — the same leak, the same fix, for the other kind of
        // sensitive text this codebase wraps in a value class.
        val entry = EntryText.of("something I am grateful for and nobody else's business")

        "$entry" shouldBe "EntryText(redacted)"
        entry.toString() shouldNotContain "grateful"
    }
}
