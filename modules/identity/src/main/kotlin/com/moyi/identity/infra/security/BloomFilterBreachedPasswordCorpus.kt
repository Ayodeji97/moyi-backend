package com.moyi.identity.infra.security

import com.moyi.common.security.BloomFilter
import com.moyi.common.security.PwnedPasswordDigest
import com.moyi.identity.domain.BreachedPasswordCorpus
import com.moyi.identity.domain.Password
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.io.IOException
import java.time.Clock
import java.time.Duration

/**
 * The corpus, as a Bloom filter read once at startup and held in heap.
 *
 * **It loads in the constructor, and a failure there stops the application.**
 * That is the design, not an oversight. ADR-0012 chose an offline filter over
 * the HIBP range API specifically because it removes the fail-open/fail-closed
 * question, and a service that boots happily without its corpus answers that
 * question fail-open — invisibly, because every registration still succeeds
 * and the only symptom is a control that is not running. Spring turns a
 * constructor failure into a refusal to start, which is the loudest signal
 * available and the one that cannot be missed.
 *
 * ~17 MB on heap against the 1.5 GB in `10` §2.1, which ADR-0012 already
 * costed and declined to give a budget line. Memory-mapping it would be
 * cheaper still, but a classpath entry inside a jar is not a file to map, and
 * putting it outside the jar would trade a heap line for a deployment path
 * that has to be configured on every environment.
 */
@Component
internal class BloomFilterBreachedPasswordCorpus(
    properties: BreachCorpusProperties,
    resourceLoader: ResourceLoader,
    clock: Clock,
) : BreachedPasswordCorpus {
    private val log = LoggerFactory.getLogger(javaClass)
    private val filter = load(properties, resourceLoader).also { verifyIsACorpus(it, properties) }

    init {
        val age = Duration.between(filter.metadata.builtAt, clock.instant())
        log.info(
            "Breached-password corpus loaded: {} digests, {} MiB, built {} from {}",
            filter.insertedCount,
            filter.sizeInBytes / BYTES_PER_MIB,
            filter.metadata.builtAt,
            filter.metadata.source,
        )
        if (age.toDays() > properties.warnAfterDays) {
            // ADR-0012's "revisit when": the corpus ages out of usefulness
            // quietly, because nothing about a stale filter looks wrong.
            log.warn(
                "The breached-password corpus is {} days old (ADR-0012 revisits past {}). " +
                    "The twice-yearly rebuild has probably been failing.",
                age.toDays(),
                properties.warnAfterDays,
            )
        }
    }

    override fun contains(password: Password): Boolean = filter.mightContain(digestOf(password))

    /**
     * Refuses a file that is a valid filter but not a corpus.
     *
     * The builder can produce a range-limited smoke filter, and that file is
     * indistinguishable from the real thing by every property except how much
     * of the space it covers. The workflow will not publish one — but a check
     * that only lives in CI is a check that protects CI, and the thing worth
     * protecting is the running service.
     */
    private fun verifyIsACorpus(
        filter: BloomFilter,
        properties: BreachCorpusProperties,
    ) {
        check(filter.insertedCount >= properties.minimumDigests) {
            "The breached-password corpus at '${properties.resource}' holds only ${filter.insertedCount} digests, " +
                "below the ${properties.minimumDigests} a real corpus must have (ADR-0016 builds ~10.5M). This is " +
                "almost certainly a range-limited smoke build, which is a working-looking filter that covers a " +
                "fraction of the space. Refusing to start rather than run a control that is not there."
        }
    }

    /**
     * The corpus's lookup key, from the one place that defines it.
     *
     * This held its own `MessageDigest.getInstance("SHA-1")` until CodeQL
     * flagged it — the second copy of a line that [PwnedPasswordDigest] was
     * created to be the only instance of, left behind when that type was
     * introduced. Precisely the drift centralising it was supposed to prevent,
     * and the reason the failure mode is worth restating: two digests computed
     * different ways do not throw, they simply never match, and the
     * breached-password check is off while reporting that it is on.
     *
     * [PwnedPasswordDigest] normalises before hashing and [Password.value] is
     * already normalised; NFKC is idempotent, so applying it twice is a
     * no-op rather than a correctness question.
     */
    private fun digestOf(password: Password): ByteArray = PwnedPasswordDigest.of(password.value)

    private fun load(
        properties: BreachCorpusProperties,
        resourceLoader: ResourceLoader,
    ): BloomFilter {
        val resource = resourceLoader.getResource(properties.resource)
        check(resource.exists()) {
            "No breached-password corpus at '${properties.resource}'. ADR-0012 makes this control the price of " +
                "the 8-character minimum, so the application will not start without it. If this is a local build, " +
                "run `./gradlew :modules:identity:downloadBreachCorpus`; see ADR-0016."
        }
        return try {
            resource.inputStream.use(BloomFilter::readFrom)
        } catch (unreadable: IOException) {
            throw IllegalStateException("The breached-password corpus at '${properties.resource}' could not be read", unreadable)
        } catch (malformed: IllegalArgumentException) {
            // BloomFilter.readFrom rejects a wrong magic, an unknown format
            // version, a mismatched word count. Each of those, read leniently,
            // would produce a filter that answers "not breached" to everything.
            throw IllegalStateException("The breached-password corpus at '${properties.resource}' is not usable", malformed)
        }
    }

    private companion object {
        const val BYTES_PER_MIB = 1024 * 1024
    }
}
