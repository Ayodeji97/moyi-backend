package com.moyi.identity.infra.security

import com.moyi.common.security.BloomFilter
import com.moyi.identity.domain.BreachedPasswordCorpus
import com.moyi.identity.domain.Password
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.io.IOException
import java.security.MessageDigest
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

    override fun contains(password: Password): Boolean = filter.mightContain(sha1(password.value))

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
     * SHA-1 — because that is what the corpus is made of, not because it is a
     * reasonable way to handle a password.
     *
     * HIBP publishes SHA-1 digests, so the lookup key is fixed by the data.
     * Nothing derived here is stored, transmitted or used to authenticate
     * anyone; the digest exists for the microsecond it takes to index into a
     * bit array, and the password's real hash is Argon2id
     * ([Argon2idPasswordHasher]). A scanner flagging this line is right about
     * the algorithm and wrong about the use.
     *
     * A fresh [MessageDigest] per call because they are not thread-safe, and
     * because a few microseconds is nothing next to the ~150 ms of Argon2id
     * that follows on the same request.
     *
     * One honest limitation: [Password.value] is NFKC-normalised and the
     * corpus is not, so a breached password whose only representation in the
     * wild is decomposed would not match. Every realistic entry in the corpus
     * is ASCII, where NFKC is the identity function, so this is a theoretical
     * gap rather than a practical one — but it is a gap, and normalising is
     * still the right call, because the alternative locks users out of their
     * own accounts across devices.
     */
    @Suppress("InsecureHash")
    private fun sha1(value: String): ByteArray = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))

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
