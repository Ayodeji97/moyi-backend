package com.moyi.gratitude.domain

/**
 * Code points that are rendered as nothing: Unicode's **`Default_Ignorable_Code_Point`**
 * property, the set Unicode itself defines as invisible when a renderer does not support it
 * otherwise. What "zero-width" means in the owner's ruling of 2026-10-21 (ADR-0031, ruling 2):
 * an entry made only of these, and whitespace, says nothing ([EntryText.of]).
 *
 * **Copied, not derived.** The JDK exposes no such property, and `Character.getType ==
 * FORMAT` is not it: that misses the variation selectors, U+034F (combining grapheme joiner),
 * the Hangul fillers (U+115F, U+1160, U+3164, U+FFA0) and the reserved points of the tag
 * block, and it includes format characters that are not ignorable (U+0600's Arabic number
 * sign, for one, which is visible). [RANGES] is `DerivedCoreProperties-16.0.0.txt`'s
 * `Default_Ignorable_Code_Point` section, line for line in its own notation: Unicode 16.0 is
 * the version JDK 25 ships (`Character.UnicodeBlock.GARAY`, new in 16.0, is there; 17.0's
 * blocks are not). That file ends the section with "Total code points: 4174", and
 * `EntryTextTest` counts this set against that total. When the JDK moves to a newer Unicode,
 * compare this list with that version's file.
 *
 * The one statement of the set. [SearchTerms] does not use it: whether a search follows the
 * entry rulings is ADR-0039's question 11, open.
 */
internal object Invisible {
    /** `DerivedCoreProperties-16.0.0.txt`, `Default_Ignorable_Code_Point`: hex, `first..last` for a range. */
    private const val RANGES =
        "00AD 034F 061C 115F..1160 17B4..17B5 180B..180D 180E 180F 200B..200F 202A..202E " +
            "2060..2064 2065 2066..206F 3164 FE00..FE0F FEFF FFA0 FFF0..FFF8 1BCA0..1BCA3 " +
            "1D173..1D17A E0000 E0001 E0002..E001F E0020..E007F E0080..E00FF E0100..E01EF E01F0..E0FFF"

    private const val HEX = 16

    private val ranges: List<IntRange> =
        RANGES.split(' ').map { entry ->
            val (first, last) = if (".." in entry) entry.split("..") else listOf(entry, entry)
            first.toInt(HEX)..last.toInt(HEX)
        }

    fun isDefaultIgnorable(codePoint: Int): Boolean = ranges.any { codePoint in it }
}
