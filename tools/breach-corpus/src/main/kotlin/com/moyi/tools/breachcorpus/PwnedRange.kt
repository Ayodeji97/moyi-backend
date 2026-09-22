package com.moyi.tools.breachcorpus

/**
 * Parsing for one Have I Been Pwned range response.
 *
 * Separated from the HTTP client so the rules below are testable without a
 * network: what a line looks like, what counts as prevalent enough, and what
 * to do with a line that does not parse. Everything here is pure.
 *
 * A range response is the body of `GET /range/{prefix}`, one entry per line:
 *
 * ```
 * 0005AD76BD555C1D6D771DE417A4B87E4B4:58
 * ```
 *
 * The prefix is the first 5 hex characters of the SHA-1 and is *not* repeated
 * in the body — that is the k-anonymity property the endpoint is built around,
 * and it means the full digest only exists once the prefix is put back.
 */
internal object PwnedRange {
    const val PREFIX_LENGTH = 5
    const val SUFFIX_LENGTH = 35
    private const val DIGEST_HEX_LENGTH = PREFIX_LENGTH + SUFFIX_LENGTH

    /**
     * The digests in [body] that appear at least [minCount] times, as raw
     * 20-byte SHA-1 values.
     *
     * **[minCount] is how "the top ~10M" is actually expressed.** ADR-0012
     * asks for the ten million most prevalent hashes, but HIBP serves 1,048,576
     * prefix ranges that are not ordered against each other, so there is no
     * global rank to take a top-N from without downloading and sorting the
     * whole ~2.1-billion-entry corpus. A prevalence threshold gets the same
     * set without the sort. The number is calibrated by measurement, not by
     * guessing — see `BreachCorpusBuilder`.
     *
     * A malformed line is skipped rather than fatal. One bad line in a
     * 1,048,576-request job must not throw away the other two hours of work,
     * and the count of skipped lines is reported so "skipped a few" and
     * "skipped everything" cannot look the same.
     */
    fun qualifyingDigests(
        prefix: String,
        body: String,
        minCount: Int,
    ): ParsedRange {
        require(prefix.length == PREFIX_LENGTH) { "prefix must be $PREFIX_LENGTH characters, got '$prefix'" }

        val digests = mutableListOf<ByteArray>()
        var seen = 0
        var malformed = 0

        for (line in body.lineSequence()) {
            if (line.isBlank()) continue
            seen++
            // Uppercased because HIBP serves uppercase and `hexToBytes` is
            // strict. Accepting either costs nothing here and means a change
            // at their end is not a silent zero-result run.
            val digest = entryDigest(prefix.uppercase(), line, minCount)
            when (digest) {
                Entry.Malformed -> malformed++
                Entry.BelowThreshold -> Unit
                is Entry.Qualifying -> digests += digest.digest
            }
        }

        return ParsedRange(digests = digests, entriesSeen = seen, malformedLines = malformed)
    }

    /**
     * One line, classified. A sealed result rather than a nullable `ByteArray`
     * because "did not parse" and "parsed but is not prevalent enough" are
     * different outcomes that need different counters — collapsing them is how
     * a parser change that breaks every line reports as a successful run that
     * simply found nothing.
     */
    private sealed interface Entry {
        data class Qualifying(
            val digest: ByteArray,
        ) : Entry {
            // Generated equals/hashCode on a data class compare a ByteArray by
            // identity. Nothing compares these, but leaving the trap armed for
            // the next person to use the type is not a saving.
            override fun equals(other: Any?): Boolean = this === other || (other is Qualifying && digest.contentEquals(other.digest))

            override fun hashCode(): Int = digest.contentHashCode()
        }

        data object BelowThreshold : Entry

        data object Malformed : Entry
    }

    private fun entryDigest(
        prefix: String,
        line: String,
        minCount: Int,
    ): Entry {
        val separator = line.indexOf(':')
        val suffix = line.take(separator.coerceAtLeast(0))
        val count = if (separator > 0) line.substring(separator + 1).trim().toIntOrNull() else null

        return when {
            separator <= 0 || count == null || suffix.length != SUFFIX_LENGTH -> Entry.Malformed
            count < minCount -> Entry.BelowThreshold
            else -> hexToBytes(prefix + suffix.uppercase())?.let(Entry::Qualifying) ?: Entry.Malformed
        }
    }

    /** Null rather than an exception: the caller counts malformed input, it does not abort on it. */
    fun hexToBytes(hex: String): ByteArray? {
        // Validated in full before any byte is built, so the loop below has no
        // failure path of its own and can be an expression.
        if (hex.length != DIGEST_HEX_LENGTH || hex.any { Character.digit(it, HEX_RADIX) < 0 }) return null

        return ByteArray(DIGEST_HEX_LENGTH / 2) { i ->
            val high = Character.digit(hex[i * 2], HEX_RADIX)
            val low = Character.digit(hex[i * 2 + 1], HEX_RADIX)
            ((high shl BITS_PER_HEX_DIGIT) or low).toByte()
        }
    }

    /** Every prefix from `00000` to `FFFFF` — 16^5 = 1,048,576 of them. */
    fun allPrefixes(): Sequence<String> = (0 until TOTAL_PREFIXES).asSequence().map { "%05X".format(it) }

    const val TOTAL_PREFIXES = 1 shl 20

    private const val HEX_RADIX = 16
    private const val BITS_PER_HEX_DIGIT = 4
}

internal data class ParsedRange(
    val digests: List<ByteArray>,
    val entriesSeen: Int,
    val malformedLines: Int,
)
