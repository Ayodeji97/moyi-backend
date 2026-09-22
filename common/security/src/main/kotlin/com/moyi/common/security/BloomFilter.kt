package com.moyi.common.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A fixed-size Bloom filter over pre-computed hash digests, with a versioned
 * on-disk format.
 *
 * This exists to answer one question — *is this password already known to
 * attackers* (ADR-0012, FR-001, T-03) — over a corpus of roughly ten million
 * SHA-1 digests. A Bloom filter is the right shape for it because the answer
 * only has to be trustworthy in one direction: **there are no false
 * negatives**, so a `false` from [mightContain] means the password is
 * genuinely absent from the corpus, while a `true` is right 999 times in 1000
 * and otherwise rejects a safe password. That asymmetry is exactly the one a
 * security control wants, and it buys a 17 MB structure in place of a 400 MB
 * exact set.
 *
 * ## Why this is hand-written rather than Guava's `BloomFilter`
 *
 * Guava's is excellent and was the default choice. Two things decided against
 * it, and neither is "we could write it ourselves":
 *
 * 1. **The file format is the artefact**, not the object. The filter is built
 *    by a CI job (`tools:breach-corpus`) and read months later by a running
 *    service, so the bytes between them are a contract that needs a magic
 *    number, a format version, and the provenance of the corpus it was built
 *    from. Guava's `writeTo` is stable but carries none of that, so it would
 *    have needed a wrapper format around it anyway — at which point the only
 *    part being reused is ~40 lines of bit-setting.
 * 2. **The input is already a hash.** Guava applies Murmur3 to whatever a
 *    `Funnel` feeds it; our input is a 160-bit SHA-1 digest that is already
 *    uniformly distributed, so re-hashing it is work with no benefit. Taking
 *    the index derivation straight from the digest is both faster and easier
 *    to reason about.
 *
 * Doc 25 D5's "no hand-written crypto, ever" is not in tension with this: the
 * digest arrives already computed (by HIBP for the corpus, by
 * `MessageDigest` at runtime), and everything here is bit-mixing over it.
 *
 * ## Thread safety
 *
 * **Reads are safe to share; writes are not.** [add] is a read-modify-write
 * on a shared `LongArray`, so two threads adding at once can lose an update —
 * and a lost update is a *false negative*, which is the one failure this
 * structure is supposed to be incapable of. The builder therefore fans out
 * the downloads and funnels every insertion through a single thread rather
 * than locking this class. After construction the array is never written
 * again, so any number of threads may call [mightContain].
 */
class BloomFilter private constructor(
    private val words: LongArray,
    /** `m` — the number of bits, which is not generally a multiple of 64. */
    val bitCount: Long,
    /** `k` — how many bits each member sets. */
    val hashCount: Int,
    val metadata: BloomFilterMetadata,
    insertedCount: Long,
) {
    /**
     * How many members are actually in the filter — which is not the number it
     * was *sized* for. The builder sizes from a measured count so the two
     * agree, but they are separate facts and the reader is told the real one:
     * a filter holding twice what it was sized for is still a valid filter,
     * just a much less useful one, and that has to be observable rather than
     * inferred.
     */
    var insertedCount: Long = insertedCount
        private set

    /** How much heap this filter occupies, for the startup log — `10` §2.1 budgets ~17 MB. */
    val sizeInBytes: Long get() = words.size.toLong() * Long.SIZE_BYTES

    /**
     * True if [digest] may be in the corpus; false if it certainly is not.
     *
     * [digest] is a raw hash — 20 bytes for SHA-1 — not a password. Keeping
     * this class ignorant of passwords is deliberate: nothing here can log,
     * print or accidentally retain plaintext, because plaintext never arrives.
     */
    fun mightContain(digest: ByteArray): Boolean {
        forEachBitIndex(digest) { index ->
            if (!bitAt(index)) return false
        }
        return true
    }

    /** Adds a member. See the class KDoc — **single-threaded only**. */
    fun add(digest: ByteArray) {
        insertedCount++
        forEachBitIndex(digest) { index ->
            val word = (index ushr WORD_SHIFT).toInt()
            words[word] = words[word] or (1L shl (index and WORD_MASK).toInt())
        }
    }

    private fun bitAt(index: Long): Boolean = words[(index ushr WORD_SHIFT).toInt()] and (1L shl (index and WORD_MASK).toInt()) != 0L

    /**
     * Derives [hashCount] bit positions from the digest by **enhanced double
     * hashing** (Dillinger & Manolios): `g(i) = h1 + i·h2 + i²`, where `h1`
     * and `h2` are the first and second 64-bit words of the digest.
     *
     * Two independent values are enough to generate `k` of them without
     * degrading the false-positive rate — Kirsch & Mitzenmacher's result, and
     * the reason this needs 128 bits rather than `k × log2(m)` ≈ 271 of them.
     * The `i²` term is the enhancement: plain double hashing can walk a short
     * cycle when `h2` shares a factor with `m`, which for a power-of-two `m`
     * happens whenever `h2` is even. Our `m` is never a power of two, so the
     * term is belt-and-braces — it costs one multiply and removes a class of
     * bug that would show up as a quietly elevated false-positive rate rather
     * than as anything that looks like a failure.
     *
     * Arithmetic is allowed to overflow and is then reduced **unsigned**: a
     * negative `Long` here is a perfectly good 64-bit pattern, and
     * `Math.floorMod` on the signed value would fold the space unevenly.
     */
    private inline fun forEachBitIndex(
        digest: ByteArray,
        action: (Long) -> Unit,
    ) {
        require(digest.size >= DIGEST_MIN_BYTES) {
            "a digest of at least $DIGEST_MIN_BYTES bytes is required, got ${digest.size}"
        }
        val h1 = digest.longAt(0)
        val h2 = digest.longAt(Long.SIZE_BYTES)
        for (i in 0 until hashCount) {
            val combined = h1 + i * h2 + i.toLong() * i.toLong()
            action(java.lang.Long.remainderUnsigned(combined, bitCount))
        }
    }

    /**
     * Writes the filter in the format [readFrom] reads. Big-endian throughout
     * — `DataOutputStream`'s own order, and the one that makes a hex dump of
     * the header readable.
     */
    fun writeTo(output: OutputStream) {
        DataOutputStream(output.buffered()).run {
            writeInt(MAGIC)
            writeInt(FORMAT_VERSION)
            writeLong(bitCount)
            writeInt(hashCount)
            writeLong(insertedCount)
            writeDouble(metadata.falsePositiveRate)
            writeLong(metadata.builtAt.epochSecond)
            writeUTF(metadata.source)
            writeInt(words.size)
            words.forEach(::writeLong)
            flush()
        }
    }

    companion object {
        /** `"MYBL"` — Moyi BLoom. Four bytes so a truncated or wrong file fails on read, not on use. */
        private const val MAGIC = 0x4D59424C

        /**
         * Bumped whenever the layout below changes. A reader that does not
         * recognise the version refuses the file rather than reading the old
         * layout as the new one — which, for a filter, would fail as a
         * *silently useless* filter rather than as an error.
         */
        const val FORMAT_VERSION = 1

        /** 64-bit words: `index / 64` and `index % 64` as a shift and a mask. */
        private const val WORD_SHIFT = 6
        private const val WORD_MASK = 63L

        /** Two 64-bit values are taken from the digest; SHA-1 supplies 20 bytes. */
        private const val DIGEST_MIN_BYTES = 16

        /** Above this, `k` costs more in probes than it saves in collisions. */
        private const val MAX_HASH_COUNT = 30

        private val LN2_SQUARED = ln(2.0) * ln(2.0)

        /**
         * `m = -n·ln(p) / (ln 2)²` — the bit count that minimises size for a
         * target false-positive rate. At n = 10M and p = 0.001 this is
         * ~143.8 million bits, or the ~17 MB `09` §3 and `10` §2.1 budget for.
         */
        fun optimalBitCount(
            expectedInsertions: Long,
            falsePositiveRate: Double,
        ): Long {
            require(expectedInsertions > 0) { "expectedInsertions must be positive, got $expectedInsertions" }
            require(falsePositiveRate > 0.0 && falsePositiveRate < 1.0) {
                "falsePositiveRate must be between 0 and 1 exclusive, got $falsePositiveRate"
            }
            return max(1L, ceil(-expectedInsertions * ln(falsePositiveRate) / LN2_SQUARED).toLong())
        }

        /**
         * `k = (m/n)·ln 2` — the probe count that minimises the false-positive
         * rate for a given size. Around 10 at our ratio.
         */
        fun optimalHashCount(
            bitCount: Long,
            expectedInsertions: Long,
        ): Int {
            require(expectedInsertions > 0) { "expectedInsertions must be positive, got $expectedInsertions" }
            return (bitCount.toDouble() / expectedInsertions * ln(2.0)).roundToInt().coerceIn(1, MAX_HASH_COUNT)
        }

        /** An empty filter sized for [expectedInsertions] members at [falsePositiveRate]. */
        fun create(
            expectedInsertions: Long,
            falsePositiveRate: Double,
            source: String,
            builtAt: Instant,
        ): BloomFilter {
            val bitCount = optimalBitCount(expectedInsertions, falsePositiveRate)
            val hashCount = optimalHashCount(bitCount, expectedInsertions)
            return BloomFilter(
                words = LongArray(wordCountFor(bitCount)),
                bitCount = bitCount,
                hashCount = hashCount,
                metadata =
                    BloomFilterMetadata(
                        falsePositiveRate = falsePositiveRate,
                        builtAt = builtAt,
                        source = source,
                    ),
                insertedCount = 0,
            )
        }

        /**
         * Reads a filter, rejecting anything it does not fully recognise.
         *
         * Every check here exists because the failure it prevents is
         * *invisible*: a filter read from the wrong bytes does not throw, it
         * answers `false` to everything and turns the breach check off while
         * continuing to look like it is running.
         */
        fun readFrom(input: InputStream): BloomFilter {
            val data = DataInputStream(input.buffered())

            val magic = data.readInt()
            require(magic == MAGIC) { "not a Moyi bloom filter: expected magic %08X, got %08X".format(MAGIC, magic) }
            val version = data.readInt()
            require(version == FORMAT_VERSION) { "unsupported format version $version, expected $FORMAT_VERSION" }

            val bitCount = data.readLong()
            require(bitCount > 0) { "bitCount must be positive, got $bitCount" }
            val hashCount = data.readInt()
            require(hashCount in 1..MAX_HASH_COUNT) { "hashCount out of range: $hashCount" }

            val insertedCount = data.readLong()
            require(insertedCount >= 0) { "insertedCount must not be negative, got $insertedCount" }
            val metadata =
                BloomFilterMetadata(
                    falsePositiveRate = data.readDouble(),
                    builtAt = Instant.ofEpochSecond(data.readLong()),
                    source = data.readUTF(),
                )

            val declaredWords = data.readInt()
            val expectedWords = wordCountFor(bitCount)
            require(declaredWords == expectedWords) {
                "word count $declaredWords does not match bitCount $bitCount, which needs $expectedWords"
            }

            // readLong, not a bulk read into a buffer we size ourselves: it
            // throws EOFException the moment the file runs out, where a
            // partial read would leave the tail of the filter zeroed — and a
            // zeroed tail does not look broken, it looks like "not breached"
            // for every password whose bits happen to land there.
            val words = LongArray(expectedWords)
            for (i in words.indices) words[i] = data.readLong()

            return BloomFilter(words, bitCount, hashCount, metadata, insertedCount)
        }

        private fun wordCountFor(bitCount: Long): Int = ((bitCount + WORD_MASK) / Long.SIZE_BITS).toInt()

        /** Big-endian, and unsigned per byte — `Byte` is signed in Kotlin and sign extension would corrupt the value. */
        private fun ByteArray.longAt(offset: Int): Long {
            var value = 0L
            for (i in 0 until Long.SIZE_BYTES) {
                value = (value shl Byte.SIZE_BITS) or (this[offset + i].toLong() and BYTE_MASK)
            }
            return value
        }

        private const val BYTE_MASK = 0xFFL
    }
}

/**
 * What the filter was built from, carried inside the file.
 *
 * Provenance is not decoration here. ADR-0012's "revisit when" includes *the
 * pinned corpus is more than a year stale*, and a filter that cannot say when
 * it was built or what threshold produced it cannot be checked against that.
 * The service logs these at startup.
 */
data class BloomFilterMetadata(
    val falsePositiveRate: Double,
    val builtAt: Instant,
    /** Free text, e.g. `"HIBP pwned passwords, SHA-1, minCount=700"`. */
    val source: String,
)
