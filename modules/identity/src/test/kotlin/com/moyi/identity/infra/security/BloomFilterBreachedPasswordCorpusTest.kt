package com.moyi.identity.infra.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.identity.domain.Password
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.core.io.DefaultResourceLoader
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.writeText

internal class BloomFilterBreachedPasswordCorpusTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a password in the corpus is found and one outside it is not`() {
        val corpus = load(TestBreachCorpus.writeTo(directory))

        TestBreachCorpus.BREACHED.forEach { corpus.contains(Password.of(it)) shouldBe true }
        corpus.contains(Password.of(TestBreachCorpus.SAFE)) shouldBe false
    }

    @Test
    fun `the lookup matches on the normalised password, as the hash will`() {
        // Whatever the corpus is keyed on has to be what `Password.value`
        // holds, or every lookup misses and the control is off while appearing
        // to run. The fixture stores the *composed* form; this submits the
        // decomposed one — the same password typed on two keyboards.
        val corpus = load(TestBreachCorpus.writeTo(directory))
        val decomposed = "cafe\u0301 au lait"

        decomposed shouldNotBe TestBreachCorpus.BREACHED_NON_ASCII
        corpus.contains(Password.of(decomposed)) shouldBe true
    }

    @Test
    fun `a missing corpus stops the application rather than disabling the check`() {
        // ADR-0012 chose an offline filter over the HIBP API specifically to
        // remove the fail-open/fail-closed question. A service that boots
        // without its corpus answers it fail-open, invisibly: registrations
        // keep succeeding and the only symptom is a control that is not there.
        val failure = shouldThrow<IllegalStateException> { load(directory.resolve("absent.bloom")) }

        failure.message shouldContain "No breached-password corpus"
        // The message has to say what to do about it — this is the error a
        // developer meets on a fresh clone.
        failure.message shouldContain "downloadBreachCorpus"
    }

    @Test
    fun `a corpus that is not a corpus stops the application too`() {
        // A wrong magic, an unknown format version, a mismatched word count:
        // read leniently, each produces a filter that answers "not breached"
        // to everything.
        val notACorpus = directory.resolve("rubbish.bloom").also { it.writeText("this is not a bloom filter") }

        val failure = shouldThrow<IllegalStateException> { load(notACorpus) }

        failure.message shouldContain "is not usable"
    }

    @Test
    fun `a smoke-test filter is refused, however well-formed it is`() {
        // The builder can produce a range-limited filter for smoke tests. It is
        // a perfectly valid Bloom filter — right magic, right format, readable
        // — covering a fraction of the 1,048,576 prefix ranges, so it would
        // wave through nearly every breached password while looking exactly
        // like a working control. The workflow refuses to publish one; this is
        // the layer that holds when something upstream goes wrong anyway.
        val smokeSized = TestBreachCorpus.writeTo(directory)

        val failure = shouldThrow<IllegalStateException> { load(smokeSized, minimumDigests = 1_000_000) }

        failure.message shouldContain "holds only 9 digests"
        failure.message shouldContain "range-limited smoke build"
    }

    @Test
    fun `a stale corpus warns, naming the age`() {
        // ADR-0012's "revisit when the pinned corpus is more than a year
        // stale". Nothing about an old filter looks wrong, so the age has to
        // be said out loud. It is a warning rather than a refusal because the
        // twice-yearly rebuild is the real mechanism — staleness means that
        // job has been failing, and taking production down for it would be
        // worse. 400 days is past the threshold with a whole missed run of
        // slack already spent, which is the point of keeping the threshold at
        // ADR-0012's year rather than at the cadence.
        val appender = captureLogs()

        load(TestBreachCorpus.writeTo(directory, builtAt = NOW.minus(Duration.ofDays(400))))

        val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        warnings.size shouldBe 1
        warnings.single() shouldContain "400 days old"
    }

    @Test
    fun `a fresh corpus does not warn`() {
        val appender = captureLogs()

        load(TestBreachCorpus.writeTo(directory, builtAt = NOW.minus(Duration.ofDays(30))))

        appender.list.none { it.level == Level.WARN } shouldBe true
    }

    private fun load(
        file: Path,
        minimumDigests: Long = 1,
    ) = BloomFilterBreachedPasswordCorpus(
        properties = BreachCorpusProperties(resource = file.toUri().toString(), minimumDigests = minimumDigests),
        resourceLoader = DefaultResourceLoader(),
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun captureLogs(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(BloomFilterBreachedPasswordCorpus::class.java) as Logger
        // Added rather than swapped in: detaching the inherited appenders
        // would silence the console for every later test in the same JVM, and
        // each test reads only the appender it created.
        return ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T12:00:00Z")
    }
}
