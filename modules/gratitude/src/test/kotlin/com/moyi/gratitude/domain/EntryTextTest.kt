package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

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

    /**
     * Ruling P12, spec §3.2, doc 04 §7: "NFKC-normalised before counting …
     * stored raw and unmodified". NFKC is lossy — each of these is rewritten
     * by it, permanently — so it is used to *decide*, never to store.
     */
    @Test
    fun `text is kept exactly as written - nothing NFKC would rewrite is rewritten`() {
        // ellipsis, superscript two, trade mark, the fi ligature, one half,
        // a full-width letter, a non-breaking space between words, and a
        // decomposed e-acute (NFKC would compose it).
        val written = listOf("wait\u2026", "x\u00b2", "Moyi\u2122", "\ufb01ne", "\u00bd a cup", "\uff21", "a\u00a0b", "cafe\u0301")
        for (text in written) {
            Normalizer.normalize(text, Normalizer.Form.NFKC) shouldNotBe text
            EntryText.of(text).value shouldBe text
        }
        // No case folding, no collapsing of internal whitespace, no stripping of emoji.
        EntryText.of("Thank  YOU \uD83D\uDE4F").value shouldBe "Thank  YOU \uD83D\uDE4F"
    }

    @Test
    fun `leading and trailing whitespace is kept - trimming only decides whether the entry is blank`() {
        EntryText.of("  thank you  ").value shouldBe "  thank you  "
        EntryText.of("\nthank you\n\n").value shouldBe "\nthank you\n\n"
        // Blank once normalised and trimmed, though no single check on the raw
        // string says so: an ideographic space and a non-breaking space.
        shouldThrow<IllegalArgumentException> { EntryText.of("\u3000\u00a0 ") }.message shouldBe "an entry must say something"
    }

    @Test
    fun `a flag and a combining sequence each count as one character`() {
        // Spec §3.2 names both. A flag is two regional-indicator code points
        // (four UTF-16 units, eight octets); the combining sequence is `e`
        // followed by U+0301 and U+0323 — three code points, one character.
        val flag = "\uD83C\uDDF3\uD83C\uDDEC"
        val stacked = "e\u0301\u0323"
        for (one in listOf(flag, stacked)) {
            EntryText.of(one.repeat(500)).value shouldBe one.repeat(500)
            shouldThrow<IllegalArgumentException> { EntryText.of(one.repeat(501)) }.message shouldBe "an entry is at most 500 characters"
        }
    }

    /**
     * **The spec's choice, flagged to the owner (ADR-0031, P12's open
     * question).** The 500 is counted on the NFKC form, and NFKC turns a
     * typographic ellipsis into three full stops — so a text of exactly 500
     * characters as its author sees them is refused if one of them is `…`.
     * A client's counter has to normalise the same way or it will say "fits"
     * for a text the server answers `422`. Kept because spec §3.2 says
     * "normalised before counting"; this test is what changes if the owner
     * decides otherwise.
     */
    @Test
    fun `the 500 is counted on the NFKC form, so an ellipsis counts as three`() {
        val fits = "a".repeat(497) + "\u2026"
        val looksLikeItFits = "a".repeat(499) + "\u2026"
        graphemesOf(looksLikeItFits) shouldBe 500

        EntryText.of(fits).value shouldBe fits
        shouldThrow<IllegalArgumentException> { EntryText.of(looksLikeItFits) }.message shouldBe "an entry is at most 500 characters"
    }

    @Test
    fun `the octet cap is measured on the stored bytes, not on a trimmed or normalised form`() {
        // 324 four-person family emoji are 8,100 octets and 324 characters;
        // a hundred leading spaces make the stored text 8,200. Trimmed it
        // would fit — but what is stored is not trimmed, and V12's
        // `entries_text_octets_check` measures what is stored.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val text = " ".repeat(100) + family.repeat(324)
        text.trim().toByteArray(Charsets.UTF_8).size shouldBe 8100
        text.toByteArray(Charsets.UTF_8).size shouldBe 8200

        shouldThrow<IllegalArgumentException> { EntryText.of(text) }.message shouldBe "an entry is at most 8192 bytes"
        val atTheCap = EntryText.of(" ".repeat(92) + family.repeat(324)).value
        atTheCap.toByteArray(Charsets.UTF_8).size shouldBe 8192
    }

    @Test
    fun `the NUL character is refused - Postgres text cannot hold it`() {
        for (text in listOf("thank you\u0000", "\u0000", "thank\u0000you")) {
            shouldThrow<IllegalArgumentException> { EntryText.of(text) }.message shouldBe "an entry cannot contain the NUL character"
        }
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

    /** Characters as a person counts them, on the string as written — no normalisation. */
    private fun graphemesOf(text: String): Int {
        val boundary = BreakIterator.getCharacterInstance(Locale.ROOT)
        boundary.setText(text)
        var count = 0
        while (boundary.next() != BreakIterator.DONE) count++
        return count
    }
}
