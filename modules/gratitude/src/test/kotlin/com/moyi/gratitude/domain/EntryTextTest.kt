package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
     * **The owner's ruling of 2026-10-21 (ADR-0031, ruling 1).** The 500 are
     * counted as typed: grapheme clusters of the text as sent. This test was
     * `the 500 is counted on the NFKC form, so an ellipsis counts as three`,
     * which pinned the spec's earlier choice: NFKC turns a typographic
     * ellipsis into three full stops, so 499 letters and an ellipsis were 502
     * and refused, though the author's own counter said 500.
     */
    @Test
    fun `the 500 is counted as typed, so an ellipsis counts as one`() {
        val fits = "a".repeat(499) + "\u2026"
        graphemesOf(fits) shouldBe 500
        graphemesOf(Normalizer.normalize(fits, Normalizer.Form.NFKC)) shouldBe 502

        EntryText.of(fits).value shouldBe fits
        val over = "a".repeat(500) + "\u2026"
        shouldThrow<IllegalArgumentException> { EntryText.of(over) }.message shouldBe "an entry is at most 500 characters"
    }

    /**
     * "As sent" is the raw string, untrimmed (ADR-0031's amendment, decision
     * 1): leading and trailing whitespace a client sent is stored, so it is
     * counted, as the client's own text field counts it.
     */
    @Test
    fun `whitespace a client sent is counted - 500 letters and a trailing space are 501`() {
        EntryText.of("a".repeat(500)).value shouldBe "a".repeat(500)
        for (over in listOf("a".repeat(500) + " ", " " + "a".repeat(500), "a".repeat(499) + "\n\n")) {
            shouldThrow<IllegalArgumentException> { EntryText.of(over) }.message shouldBe "an entry is at most 500 characters"
        }
    }

    /**
     * The other direction from the ellipsis: a text whose NFKC form has *more*
     * graphemes than the raw is accepted on the raw. U+FB01 (the `fi`
     * ligature) is one grapheme that NFKC makes two; U+FDFA is one that NFKC
     * makes eighteen characters, spaces among them.
     */
    @Test
    fun `a text whose NFKC form has more graphemes than the raw is decided on the raw`() {
        val ligatures = "\uFB01".repeat(500)
        graphemesOf(ligatures) shouldBe 500
        graphemesOf(Normalizer.normalize(ligatures, Normalizer.Form.NFKC)) shouldBe 1000
        EntryText.of(ligatures).value shouldBe ligatures

        val salawat = "\uFDFA".repeat(400)
        graphemesOf(Normalizer.normalize(salawat, Normalizer.Form.NFKC)) shouldBeGreaterThan 500
        EntryText.of(salawat).value shouldBe salawat

        shouldThrow<IllegalArgumentException> { EntryText.of("\uFB01".repeat(501)) }.message shouldBe
            "an entry is at most 500 characters"
    }

    /**
     * **The owner's ruling of 2026-10-21 (ADR-0031, ruling 2).** An entry of
     * only zero-width characters is refused as empty. "Zero-width" is
     * Unicode's `Default_Ignorable_Code_Point` set, and each case below is a
     * different part of it: the zero-width space, non-joiner and joiner, the
     * word joiner, the byte-order mark, the soft hyphen, a variation selector,
     * the combining grapheme joiner, the Hangul fillers, and a tag character
     * (U+E0041, outside the BMP: a surrogate pair). `Character.getType ==
     * FORMAT` alone would miss the variation selector, U+034F and the fillers.
     */
    @Test
    fun `an entry of only zero-width characters is refused as empty, whichever kind they are`() {
        val invisible =
            listOf(
                "\u200B",
                "\u200C",
                "\u200D",
                "\u2060",
                "\uFEFF",
                "\u00AD",
                "\uFE0F",
                "\u034F",
                "\u3164",
                "\uDB40\uDC41",
                "\u115F\u1160",
                "\uFFA0",
                "\u200B\u200B\u200B",
            )
        for (text in invisible) {
            withClue(text.codePoints().toArray().joinToString { "U+%04X".format(it) }) {
                shouldThrow<IllegalArgumentException> { EntryText.of(text) }.message shouldBe "an entry must say something"
            }
        }
    }

    @Test
    fun `zero-width characters mixed with whitespace are still nothing`() {
        for (text in listOf(" \u200B ", "\n\u200D\t\uFEFF\n", "\u00A0\u2060\u3000", "\u200B \uDB40\uDC41 \uFE0F")) {
            shouldThrow<IllegalArgumentException> { EntryText.of(text) }.message shouldBe "an entry must say something"
        }
    }

    /**
     * The ruling refuses an entry of *only* invisible characters. One beside
     * a visible character is part of what was written and is kept exactly:
     * ZWJs join a family emoji, U+FE0F asks for the emoji form of a heart,
     * and a flag is two regional indicators.
     */
    @Test
    fun `a zero-width character beside a visible one is accepted and stored exactly`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val heart = "\u2764\uFE0F"
        val flag = "\uD83C\uDDF3\uD83C\uDDEC"
        for (text in listOf("a\u200B", "\u200Ba", " \u200D thank you", family, heart, flag, "\uDB40\uDC41x")) {
            EntryText.of(text).value shouldBe text
        }
    }

    /**
     * The one place the set is stated ([Invisible]), checked against the file
     * it was copied from: DerivedCoreProperties-16.0.0.txt (the Unicode
     * version JDK 25 ships) ends its `Default_Ignorable_Code_Point` list with
     * "Total code points: 4174". A range mistyped, dropped or doubled changes
     * the total or an edge.
     */
    @Test
    fun `the default-ignorable set has the 4174 code points Unicode 16 lists`() {
        (0..Character.MAX_CODE_POINT).count(Invisible::isDefaultIgnorable) shouldBe 4174
        for (edge in listOf(0x00AD, 0x180F, 0x2065, 0xFFF8, 0x1BCA3, 0x1D17A, 0xE0000, 0xE0FFF)) {
            Invisible.isDefaultIgnorable(edge) shouldBe true
        }
        for (outside in listOf(0x00AC, 0x00AE, 0x2070, 0xFFF9, 0x1D17B, 0xE1000, 'a'.code, ' '.code, 0x00A0)) {
            Invisible.isDefaultIgnorable(outside) shouldBe false
        }
    }

    /**
     * ADR-0031's amendment, decision 3: both rulings tighten the rule, and
     * development and production databases hold entries written under the
     * old one. Reading such a row back must not fail, so the read path checks
     * only what storage guarantees, and still refuses what no row can hold.
     */
    @Test
    fun `a row the old rule accepted is read back unchanged, though the new rule refuses it`() {
        for (old in listOf("\u200B", "\u200D\uFEFF ", "a".repeat(500) + " ", "\n" + "a".repeat(500))) {
            shouldThrow<IllegalArgumentException> { EntryText.of(old) }
            EntryText.fromStorage(old).value shouldBe old
        }
        shouldThrow<IllegalArgumentException> { EntryText.fromStorage("a\u0000") }
        shouldThrow<IllegalArgumentException> { EntryText.fromStorage("a\uD800") }
        shouldThrow<IllegalArgumentException> { EntryText.fromStorage("\uD83D\uDE00".repeat(2049)) }
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

    /**
     * A surrogate with no partner is not a character: it has no UTF-8 form,
     * so it cannot be "stored exactly as sent". The driver stored `?` for it
     * while the `201` echoed it back — a silent rewrite of somebody's words.
     */
    @Test
    fun `an unpaired surrogate is refused - it has no UTF-8 form to store`() {
        val unpaired =
            listOf(
                "thank you\uD800", // a high surrogate at the end
                "\uDC00 thank you", // a low surrogate at the start
                "thank\uD83Dyou", // a high surrogate followed by a letter
                "thank you \uDE4F\uD83D", // a pair the wrong way round
                "\uD83D\uD83D\uDE4F", // a high surrogate before a good pair
                "\uD83D\uDE4F\uDE4F", // a low surrogate after a good pair
            )
        for (text in unpaired) {
            shouldThrow<IllegalArgumentException> { EntryText.of(text) }.message shouldBe
                "an entry cannot contain an unpaired surrogate"
        }
    }

    @Test
    fun `a well-formed surrogate pair is kept exactly - every emoji outside the BMP is one`() {
        // The last is a ZWJ sequence, the four-person family: four pairs joined by U+200D.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        for (text in listOf("\uD83D\uDE4F", "thank you \uD83D\uDE4F", "\uD83D\uDE4F\uD83D\uDE4F", "a\uD83C\uDDF3\uD83C\uDDECb", family)) {
            EntryText.of(text).value shouldBe text
            EntryText.of(text).value.toByteArray(Charsets.UTF_8) shouldBe text.toByteArray(Charsets.UTF_8)
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
