package com.moyi.identity.infra.security

import com.moyi.common.security.BloomFilter
import com.moyi.common.security.PwnedPasswordDigest
import org.springframework.test.context.DynamicPropertyRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.outputStream

/**
 * A corpus of a dozen entries, built by the tests that need one.
 *
 * The real filter is 17 MB, downloaded from a release asset and pinned by
 * digest — none of which a test should wait for, and a committed binary
 * fixture would be a file nobody could regenerate or read. This builds one
 * from a list of passwords in plain sight, using the same [BloomFilter] the
 * service loads, so the fixture exercises the real format rather than
 * standing in for it.
 *
 * [BREACHED] are genuinely among the most-leaked passwords in the HIBP corpus
 * and every one of them clears the 8-character floor, which matters: a
 * password rejected for being short would never reach the corpus check, and a
 * test using one would pass without testing anything.
 */
internal object TestBreachCorpus {
    val BREACHED =
        listOf(
            "password",
            "12345678",
            "qwerty123",
            "iloveyou1",
            "sunshine1",
            "princess1",
            "football1",
            "babygirl1",
        )

    /**
     * Not a real corpus entry — invented for the normalisation test, because
     * every genuinely top-leaked password is ASCII and ASCII cannot show
     * whether NFKC ran. Written here in its *composed* form; the test submits
     * the decomposed one.
     */
    const val BREACHED_NON_ASCII = "caf\u00E9 au lait"

    /** Long, unremarkable, and not in any corpus — the password the happy paths use. */
    const val SAFE = "correct horse battery"

    /** A path, not a classpath entry, so it cannot collide with the packaged corpus. */
    fun writeTo(
        directory: Path,
        builtAt: Instant = Instant.now(),
    ): Path {
        val filter =
            BloomFilter.create(
                expectedInsertions = BREACHED.size + 1L,
                falsePositiveRate = FALSE_POSITIVE_RATE,
                source = "test fixture",
                builtAt = builtAt,
            )
        (BREACHED + BREACHED_NON_ASCII).forEach { filter.add(sha1(it)) }

        val file = directory.resolve("test-corpus.bloom")
        file.outputStream().use(filter::writeTo)
        return file
    }

    /** Points the application at a fixture written into a fresh temporary directory. */
    fun register(
        registry: DynamicPropertyRegistry,
        builtAt: Instant = Instant.now(),
    ) {
        val file = writeTo(Files.createTempDirectory("moyi-test-corpus"), builtAt)
        registry.add("moyi.security.breach-corpus.resource") { file.toUri().toString() }
        // A fixture of nine entries is exactly the kind of file
        // `minimumDigests` exists to refuse, so the bound has to come down for
        // it. Lowered here rather than defaulted low: production's default is
        // the safe one, and the only thing that overrides it is a test saying
        // so in writing.
        registry.add("moyi.security.breach-corpus.minimum-digests") { "1" }
    }

    /**
     * The same [PwnedPasswordDigest] the service uses, deliberately.
     *
     * A fixture that derived its digests independently would still pass every
     * test while agreeing with nothing — it would be testing that the fixture
     * agrees with itself. Sharing the definition is what makes these tests say
     * something about the code under test.
     */
    private fun sha1(value: String): ByteArray = PwnedPasswordDigest.of(value)

    /**
     * Far tighter than production's 0.001, because eight members in a filter
     * sized for eight is a regime where a false positive is plausible, and a
     * test asserting that a safe password is accepted must not flake.
     */
    private const val FALSE_POSITIVE_RATE = 1e-9
}
