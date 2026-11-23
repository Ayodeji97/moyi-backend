package com.moyi.gratitude.service

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.core.FeatureFlag
import com.moyi.common.core.FeatureFlags
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.domain.VectorTable
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * [QueryEmbedder] on its own, with ports and timeouts of each test's choosing: what it does when
 * the provider hangs, fails or answers wrongly. Never throws; `null` is keywords only. The
 * membership is a real one: only `bond` can make one.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class QueryEmbedderTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val access: BondAccess,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val meters = SimpleMeterRegistry()
    private val embedders = mutableListOf<QueryEmbedder>()
    private lateinit var membership: BondMembership

    @BeforeEach
    fun setUp() {
        rig.clear()
        val ada = users.verified("Ada")
        membership = access.membershipOf(ada, UUID.fromString(rig.idOf(rig.create(ada))))
    }

    @AfterEach
    fun clear() {
        embedders.forEach(QueryEmbedder::close)
        rig.clear()
        users.clear()
    }

    @Test
    fun `a provider that hangs is cancelled at the timeout, and the search goes on without it`() {
        val interrupted = CountDownLatch(1)
        val hanging =
            port { _ ->
                try {
                    Thread.sleep(Duration.ofMinutes(1))
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
                error("not reached")
            }
        val started = System.nanoTime()

        embedder(hanging, timeout = Duration.ofMillis(TIMEOUT_MS)).matchFor(membership, "calm light").shouldBeNull()

        Duration.ofNanos(System.nanoTime() - started) shouldBeLessThan Duration.ofSeconds(2)
        // The provider call does not run on: cancelling the future interrupts it.
        interrupted.await(AWAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        counted("degraded") shouldBe 1.0
    }

    @Test
    fun `a provider error, expected or not, is keywords only and never thrown`() {
        listOf(EmbeddingUnavailableException("provider error"), IllegalStateException("a bug")).forEach { failure ->
            embedder(port { throw failure }).matchFor(membership, "calm light").shouldBeNull()
        }
        counted("degraded") shouldBe 2.0
    }

    @Test
    fun `a vector of the wrong size is keywords only, not a failed statement`() {
        embedder(port { FloatArray(3) }).matchFor(membership, "calm light").shouldBeNull()
        counted("degraded") shouldBe 1.0
    }

    /** Review of Task 7: a request that arrives while the bean is shutting down must still be answered. */
    @Test
    fun `a refused submit, the executor shut down, is keywords only and never thrown`() {
        val embedder = embedder(port { FloatArray(SPACE.dimensions) })
        embedder.close()

        embedder.matchFor(membership, "calm light").shouldBeNull()

        counted("degraded") shouldBe 1.0
    }

    @Test
    fun `with AI_EMBEDDINGS off the provider is not asked, on this thread, and the search counts disabled`() {
        var asked = false
        val off = FeatureFlags { it != FeatureFlag.AI_EMBEDDINGS }

        embedder(
            port {
                asked = true
                FloatArray(SPACE.dimensions)
            },
            flags = off,
        ).matchFor(membership, "calm light").shouldBeNull()

        asked shouldBe false
        counted("disabled") shouldBe 1.0
        counted("degraded") shouldBe 0.0
    }

    @Test
    fun `a model with no threshold is not asked`() {
        val asked = mutableListOf<String>()
        val answering = port { text -> FloatArray(SPACE.dimensions).also { asked += text } }

        embedder(answering, maxDistance = emptyMap()).matchFor(membership, "calm light").shouldBeNull()

        asked shouldBe emptyList()
        counted("disabled") shouldBe 1.0
    }

    @Test
    fun `an answer is the match, with the model's threshold, and does not print the vector`() {
        val vector = FloatArray(SPACE.dimensions) { 0.123f }

        val match = embedder(port { vector }).matchFor(membership, "calm light").shouldNotBeNull()

        match.table shouldBe VectorTable.OPENAI_1536
        match.vector shouldBe vector
        match.maxDistance shouldBe MAX_DISTANCE
        match.toString() shouldNotContain "0.123"
        counted("used") shouldBe 1.0
    }

    /**
     * ADR-0070: `BondResponse.semanticSearchActive` is the bond's setting and [SemanticSearchReadiness.isAvailable];
     * search asks by meaning exactly then. Every combination of the four inputs, so that neither can drift from the
     * other: a provider answering every call, so only the predicate decides whether there is a match.
     */
    @Test
    fun `a match is made exactly when the setting is on and the readiness the bond reports is available`() {
        val bondId = membership.bondId
        val off =
            run {
                jdbc.update("UPDATE bonds SET semantic_search_enabled = false WHERE id = ?", bondId)
                access.membershipOf(membership.userId, bondId).also { it.semanticSearchEnabled shouldBe false }
            }
        jdbc.update("UPDATE bonds SET semantic_search_enabled = true WHERE id = ?", bondId)
        val on = access.membershipOf(membership.userId, bondId).also { it.semanticSearchEnabled shouldBe true }
        val combinations =
            listOf(true, false).flatMap { flag ->
                listOf(SPACE, null).flatMap { space ->
                    listOf(mapOf(SPACE to MAX_DISTANCE), emptyMap()).map { threshold -> Triple(flag, space, threshold) }
                }
            }
        combinations.size shouldBe 8
        combinations.forEach { (flag, space, threshold) ->
            val port =
                object : EmbeddingPort {
                    override val space = space

                    override fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(SPACE.dimensions) }
                }
            val properties = SemanticSearchProperties(Duration.ofSeconds(AWAIT_SECONDS), threshold)
            val readiness = SemanticSearchReadiness(port, properties, FeatureFlags { !(it == FeatureFlag.AI_EMBEDDINGS && !flag) })
            val embedder = QueryEmbedder(port, properties, readiness, meters).also { embedders += it }
            val available = flag && space != null && threshold.isNotEmpty()

            readiness.isAvailable() shouldBe available
            (embedder.matchFor(on, "calm light") != null) shouldBe available
            embedder.matchFor(off, "calm light").shouldBeNull()
        }
    }

    private fun counted(outcome: String) = meters.counter("gratitude.search.semantic", "outcome", outcome).count()

    private fun embedder(
        port: EmbeddingPort,
        timeout: Duration = Duration.ofSeconds(AWAIT_SECONDS),
        maxDistance: Map<EmbeddingSpace, Double> = mapOf(SPACE to MAX_DISTANCE),
        flags: FeatureFlags = FeatureFlags { true },
    ): QueryEmbedder {
        val properties = SemanticSearchProperties(timeout, maxDistance)
        return QueryEmbedder(port, properties, SemanticSearchReadiness(port, properties, flags), meters).also { embedders += it }
    }

    private fun port(answer: (String) -> FloatArray): EmbeddingPort =
        object : EmbeddingPort {
            override val space = SPACE

            override fun embed(texts: List<String>): List<FloatArray> = texts.map(answer)
        }

    private companion object {
        val SPACE = EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL
        const val MAX_DISTANCE = 0.35
        const val TIMEOUT_MS = 200L
        const val AWAIT_SECONDS = 5L
    }
}
