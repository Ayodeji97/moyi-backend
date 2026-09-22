package com.moyi.tools.breachcorpus

import com.moyi.common.security.BloomFilter
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream

/**
 * Builds the breached-password Bloom filter ADR-0012 requires, from the live
 * Have I Been Pwned corpus.
 *
 * ## How "the top ~10 million" became a number
 *
 * ADR-0012 asks for the ten million most prevalent breached hashes. HIBP does
 * not expose a global ranking — it serves 1,048,576 prefix ranges, each sorted
 * only within itself — so a top-N cut would mean downloading and sorting all
 * ~2.1 billion entries. A **prevalence threshold** produces the same set
 * without the sort, and the threshold was calibrated by actually running this
 * builder over 300 ranges on 2026-09-22 and extrapolating by the ratio:
 *
 * | appears at least | ≈ corpus size |
 * |---|---|
 * | 550 times | 11.6M |
 * | **600 times** | **10.5M** |
 * | 650 times | 9.8M |
 * | 700 times | 9.0M |
 *
 * So [BuildOptions.minCount] defaults to **600**. Ten million sits between 600
 * and 650, and the tie goes upward: a slightly larger corpus is slightly more
 * coverage of the attack ADR-0012 says is the dominant one, at a cost that is
 * a few hundred kilobytes of a filter sized from the count it actually got.
 *
 * The estimate is still an extrapolation from 0.03% of the ranges, so the real
 * total will not be exactly 10.5M — which is why the filter is sized from the
 * **measured** count after the download rather than from the target, and why
 * the actual figure is written into the file's metadata rather than assumed by
 * whatever reads it.
 *
 * ## Why two passes over a temporary file
 *
 * A Bloom filter's size is a function of how many members it will hold, and
 * that number is not known until the corpus has been read. The alternatives
 * were to size it for a guess (and be wrong about the false-positive rate in
 * whichever direction the guess erred) or to hold ~10 million digests in
 * memory (~400 MB with object overhead, on a runner with 7 GB and a JVM that
 * also has to hold the filter). Spilling raw 20-byte digests to disk costs
 * ~200 MB of a runner's scratch space and makes the sizing exact.
 *
 * ## Why the downloads fan out and the writes do not
 *
 * Each of the 1,048,576 requests spends almost all of its time waiting, so the
 * job is bound by round trips rather than CPU — hence [BuildOptions.concurrency]
 * virtual threads, each pulling the next prefix from a shared counter. The
 * *writes* stay single-file-single-lock, because appending from many threads
 * would interleave partial records, and `BloomFilter.add` is a
 * read-modify-write whose lost update is a **false negative** — the one answer
 * the structure is supposed to be incapable of giving.
 */
internal class BreachCorpusBuilder(
    private val options: BuildOptions,
    private val client: RangeSource = HibpRangeClient(),
    private val log: (String) -> Unit = ::println,
) {
    fun build(): BuildReport {
        val startedAt = Instant.now()
        val workFile = Files.createTempFile("moyi-breach-digests", ".bin")
        try {
            val download = downloadDigests(workFile)
            val filter = fillFilter(workFile, download, startedAt)
            options.output.parent?.let(Files::createDirectories)
            options.output.outputStream().use(filter::writeTo)
            verifyReadBack(filter)

            return BuildReport(
                insertedCount = download.digestCount,
                entriesScanned = download.entriesSeen,
                malformedLines = download.malformedLines,
                bitCount = filter.bitCount,
                hashCount = filter.hashCount,
                fileSizeBytes = options.output.fileSize(),
                elapsed = Duration.between(startedAt, Instant.now()),
            )
        } finally {
            Files.deleteIfExists(workFile)
        }
    }

    private fun downloadDigests(workFile: Path): DownloadStats {
        val prefixes = PwnedRange.allPrefixes().take(options.prefixLimit).toList()
        log("Downloading ${prefixes.size} ranges at concurrency ${options.concurrency}, keeping count >= ${options.minCount}")

        val progress = Progress()
        val factory = Thread.ofVirtual().name("range-", 0).factory()

        workFile.outputStream().buffered(WRITE_BUFFER_BYTES).use { sink ->
            val writer = Any()
            val workers =
                List(options.concurrency) {
                    factory.newThread { drainPrefixes(prefixes, progress, sink, writer) }
                }
            workers.forEach(Thread::start)
            workers.forEach(Thread::join)
        }

        // A worker that exhausts its retries throws, and a thread's exception
        // does not travel through `join` — so the failure is captured
        // explicitly rather than being inferred from a count that came up
        // short. An incomplete corpus must not produce a file that looks
        // finished.
        progress.failure.get()?.let { throw IOException("the corpus download did not complete", it) }
        check(progress.completed.get() == prefixes.size) {
            "only ${progress.completed.get()} of ${prefixes.size} ranges were fetched"
        }
        return DownloadStats(progress.digests.get(), progress.entries.get(), progress.malformed.get())
    }

    /**
     * One worker: take the next prefix, fetch it, append what qualifies.
     *
     * The prefix index is a shared counter rather than a partition per worker,
     * so a range that happens to be slow does not leave one worker still
     * running long after the others have finished their slice.
     */
    private fun drainPrefixes(
        prefixes: List<String>,
        progress: Progress,
        sink: java.io.OutputStream,
        writer: Any,
    ) {
        while (progress.failure.get() == null) {
            val index = progress.next.getAndIncrement()
            if (index >= prefixes.size) return
            val prefix = prefixes[index]
            try {
                val parsed = PwnedRange.qualifyingDigests(prefix, client.fetch(prefix), options.minCount)
                progress.entries.addAndGet(parsed.entriesSeen.toLong())
                progress.malformed.addAndGet(parsed.malformedLines.toLong())
                progress.digests.addAndGet(parsed.digests.size.toLong())
                // The only shared mutable state a worker touches. Appending
                // from several threads at once would interleave partial
                // 20-byte records, which is not a crash — it is a file full of
                // digests that are not digests.
                synchronized(writer) { parsed.digests.forEach { sink.write(it) } }
                reportProgress(progress.completed.incrementAndGet(), prefixes.size, progress.digests.get())
            } catch (failure: IOException) {
                progress.failure.compareAndSet(null, failure)
                return
            }
        }
    }

    private fun fillFilter(
        workFile: Path,
        download: DownloadStats,
        startedAt: Instant,
    ): BloomFilter {
        val onDisk = workFile.fileSize() / DIGEST_BYTES
        check(onDisk == download.digestCount) {
            "wrote ${workFile.fileSize()} bytes ($onDisk digests) but counted ${download.digestCount}"
        }
        check(onDisk > 0) {
            "no digests met the count >= ${options.minCount} threshold — check the parser before the threshold"
        }
        // The second pass indexes with an Int. Ten million is nowhere near it,
        // but a threshold typo of 6 instead of 600 would put ~2.1 billion
        // digests here, and a silently truncated read is worse than a stop.
        check(onDisk <= Int.MAX_VALUE) { "$onDisk digests is more than one pass can index — raise the threshold" }

        log("Sizing the filter for $onDisk digests at a ${options.falsePositiveRate} false-positive rate")
        val filter =
            BloomFilter.create(
                expectedInsertions = onDisk,
                falsePositiveRate = options.falsePositiveRate,
                source = "HIBP pwned passwords (SHA-1), minCount=${options.minCount}, ranges=${options.prefixLimit}",
                builtAt = startedAt,
            )

        DataInputStream(Files.newInputStream(workFile).buffered(WRITE_BUFFER_BYTES)).use { source ->
            val digest = ByteArray(DIGEST_BYTES)
            repeat(onDisk.toInt()) {
                source.readFully(digest)
                filter.add(digest)
            }
        }
        return filter
    }

    /**
     * Reads the file back and asks it questions only a correct corpus can
     * answer.
     *
     * The first version of this check lived in the workflow and compared the
     * file's SHA-256 against the digest this tool had printed for it seconds
     * earlier — a comparison of a file with itself, which cannot fail. It was
     * called "verify the published file reads back" and verified nothing of
     * the sort.
     *
     * This one goes through the format: it reopens the written bytes with
     * [BloomFilter.readFrom] and looks for passwords that must be in any real
     * corpus. That is what catches the failure worth catching here — a
     * **plausible but useless** filter. The likeliest cause is the prefix: HIBP
     * returns only the 35-character suffix, so forgetting to re-attach the
     * 5-character prefix produces twenty valid-looking bytes per entry that
     * match no password in existence. Every count in the report would still be
     * right, the file would still be well-formed, and the control would be off.
     */
    private fun verifyReadBack(built: BloomFilter) {
        val written = Files.newInputStream(options.output).use(BloomFilter::readFrom)

        check(written.insertedCount == built.insertedCount && written.bitCount == built.bitCount) {
            "the corpus did not survive being written: ${written.insertedCount}/${written.bitCount} read back " +
                "from ${built.insertedCount}/${built.bitCount}"
        }

        // Only on a full run: a range-limited smoke build legitimately covers
        // a fraction of the space, and these two live at prefixes 7C4A8 and
        // 5BAA6 — indices 509,096 and 375,974 of 1,048,576, which a build
        // limited to the first few hundred ranges never reaches.
        if (options.prefixLimit < PwnedRange.TOTAL_PREFIXES) {
            log("Skipping the known-password check: this is a ${options.prefixLimit}-range build, not a corpus")
            return
        }

        val missing = CorpusSentinels.missingFrom(written)
        check(missing.isEmpty()) {
            "the corpus does not contain ${missing.joinToString()}, which appear tens of millions of times in " +
                "any real breach corpus. The filter is well-formed and worthless. Suspect the prefix being " +
                "dropped before the digest is assembled."
        }
        log("Read back and verified: ${written.insertedCount} digests, sentinels present")
    }

    private fun reportProgress(
        completed: Int,
        total: Int,
        digests: Long,
    ) {
        if (completed % PROGRESS_EVERY != 0 && completed != total) return
        log("  $completed/$total ranges, $digests digests kept")
    }

    private companion object {
        const val DIGEST_BYTES = 20
        const val WRITE_BUFFER_BYTES = 1 shl 16
        const val PROGRESS_EVERY = 20_000
    }
}

internal data class BuildOptions(
    val output: Path,
    /** Calibrated to ~10.5M members — see the class KDoc for the measurement it came from. */
    val minCount: Int = DEFAULT_MIN_COUNT,
    val falsePositiveRate: Double = DEFAULT_FALSE_POSITIVE_RATE,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    /** Fewer than every range, for a smoke test that finishes in seconds rather than hours. */
    val prefixLimit: Int = PwnedRange.TOTAL_PREFIXES,
) {
    companion object {
        const val DEFAULT_MIN_COUNT = 600
        const val DEFAULT_FALSE_POSITIVE_RATE = 0.001
        const val DEFAULT_CONCURRENCY = 64
    }
}

internal data class DownloadStats(
    val digestCount: Long,
    val entriesSeen: Long,
    val malformedLines: Long,
)

internal data class BuildReport(
    val insertedCount: Long,
    val entriesScanned: Long,
    val malformedLines: Long,
    val bitCount: Long,
    val hashCount: Int,
    val fileSizeBytes: Long,
    val elapsed: Duration,
)

/** The counters the workers share. Grouped so a worker takes one parameter rather than six. */
private class Progress {
    val next = AtomicInteger(0)
    val completed = AtomicInteger(0)
    val entries = AtomicLong(0)
    val malformed = AtomicLong(0)
    val digests = AtomicLong(0)
    val failure =
        java.util.concurrent.atomic
            .AtomicReference<IOException?>(null)
}

/**
 * Passwords that must be in any real breach corpus, and the check that says so.
 *
 * Its own object because it is a rule rather than a step: it is what separates
 * a corpus from a **plausible but useless** filter, and a rule that can only be
 * exercised by a 1,048,576-range build is a rule nobody tests. Extracted so it
 * can be handed a filter directly.
 */
internal object CorpusSentinels {
    /**
     * The two most-used passwords on earth by a wide margin. Checked against
     * the live endpoint on 2026-09-22: `123456` appears **210,461,208** times
     * and `password` **52,372,427**, against a threshold of 600. Any threshold
     * a sane person would set keeps them, so their absence means the corpus is
     * wrong rather than that the threshold was strict.
     */
    val PASSWORDS = listOf("123456", "password")

    /** Which of [PASSWORDS] the filter does not have. Empty is the only acceptable answer. */
    fun missingFrom(filter: BloomFilter): List<String> = PASSWORDS.filterNot { filter.mightContain(digestOf(it)) }

    /**
     * SHA-1 of the UTF-8 bytes, which is how HIBP keys its corpus — so this has
     * to agree with the digest [PwnedRange] assembles from a prefix and a
     * suffix, or the check tests its own arithmetic instead of the corpus.
     */
    fun digestOf(password: String): ByteArray = MessageDigest.getInstance("SHA-1").digest(password.toByteArray(Charsets.UTF_8))
}
