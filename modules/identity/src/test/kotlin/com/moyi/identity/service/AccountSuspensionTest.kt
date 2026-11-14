package com.moyi.identity.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.identity.api.AccountDeletions
import com.moyi.identity.api.AccountStanding
import com.moyi.identity.api.AccountStatus
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.api.SuspensionResult
import com.moyi.identity.api.SuspensionSpan
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.identity.web.LoginEndpointTest
import com.moyi.identity.web.RecordingEmailSender
import com.moyi.identity.web.SessionsEndpointTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * Admin console A3's member effect, in `identity` (spec rulings 4–6 and 15): suspending and
 * lifting through the port, against a real database, with accounts made the way people make
 * them (registered, verified, signed in over HTTP), never inserted rows. Each port call runs
 * inside a transaction, as the console's does, and after every one the invariant is checked:
 * a live account is `SUSPENDED` exactly when it has an open span.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(LoginEndpointTest.RecordingConfiguration::class, SessionsEndpointTest.TimeConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // One port's every outcome; split, each half would re-state the set-up.
internal class AccountSuspensionTest(
    @Autowired private val suspension: AccountSuspension,
    @Autowired private val standing: AccountStanding,
    @Autowired private val deletion: AccountDeletion,
    @Autowired private val deletions: AccountDeletions,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: JsonMapper,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun start() {
        clock.set(START)
    }

    @AfterEach
    fun clear() {
        jdbc.execute(
            "TRUNCATE TABLE account_suspensions, deletion_requests, refresh_tokens, devices, verification_tokens, " +
                "consent_records, credentials, users, outbox_deliveries, outbox_events, push_tokens CASCADE",
        )
        emails.sent.clear()
    }

    // --- suspending ---

    @Test
    fun `suspending an active account signs it out everywhere and opens one span at the act`() {
        val ada = signUp(verified = true)
        val phone = login(ada, device = PIXEL)
        registerPushToken(phone)
        login(ada)
        liveRefreshTokens(ada) shouldBe 2
        pushTokens(ada) shouldBe 1
        clock.advance(Duration.ofMinutes(5))
        val act = clock.instant()

        val result = suspend(ada.id)

        result.outcome shouldBe SuspensionOutcome.SUSPENDED
        result.statusBefore shouldBe AccountStatus.ACTIVE
        result.deletionDueAt.shouldBeNull()
        status(ada.id) shouldBe "SUSPENDED"
        // The next whole second: a token's `iat` is whole seconds (ADR-0019), so one minted in the act's second dies too.
        tokensInvalidBefore(ada.id) shouldBe act.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
        liveRefreshTokens(ada) shouldBe 0
        pushTokens(ada) shouldBe 0
        spans(ada.id) shouldBe listOf(act to null)
    }

    @Test
    fun `a pending-verification account is suspended too`() {
        val ada = signUp(verified = false)

        val result = suspend(ada.id)

        result.outcome shouldBe SuspensionOutcome.SUSPENDED
        result.statusBefore shouldBe AccountStatus.PENDING_VERIFICATION
        status(ada.id) shouldBe "SUSPENDED"
        spans(ada.id).single().second.shouldBeNull()
    }

    @Test
    fun `suspending twice changes nothing the second time`() {
        val ada = signUp(verified = true)
        suspend(ada.id)
        val bumped = tokensInvalidBefore(ada.id)
        val row = userRow(ada.id)
        clock.advance(Duration.ofMinutes(10))

        val second = suspend(ada.id)

        second.outcome shouldBe SuspensionOutcome.ALREADY_SUSPENDED
        second.statusBefore shouldBe AccountStatus.SUSPENDED
        tokensInvalidBefore(ada.id) shouldBe bumped
        userRow(ada.id) shouldBe row
        spans(ada.id).size shouldBe 1
    }

    // --- lifting ---

    @Test
    fun `lifting a verified account makes it active, closes the span at the act, and restores no session`() {
        val ada = signUp(verified = true)
        login(ada)
        val suspendedAt = clock.instant()
        suspend(ada.id)
        val bumped = tokensInvalidBefore(ada.id)
        clock.advance(Duration.ofHours(3))
        val liftedAt = clock.instant()

        val result = lift(ada.id)

        result.outcome shouldBe SuspensionOutcome.LIFTED
        result.statusBefore shouldBe AccountStatus.SUSPENDED
        status(ada.id) shouldBe "ACTIVE"
        spans(ada.id) shouldBe listOf(suspendedAt to liftedAt)
        liveRefreshTokens(ada) shouldBe 0
        tokensInvalidBefore(ada.id) shouldBe bumped
    }

    @Test
    fun `lifting an account that never verified returns it to pending verification`() {
        val ada = signUp(verified = false)
        suspend(ada.id)

        lift(ada.id).outcome shouldBe SuspensionOutcome.LIFTED

        status(ada.id) shouldBe "PENDING_VERIFICATION"
    }

    @Test
    fun `an account verified while suspended stays suspended, and is active once lifted`() {
        val ada = signUp(verified = false)
        suspend(ada.id)

        verify(ada.secret!!)

        status(ada.id) shouldBe "SUSPENDED"
        jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE id = ?", Boolean::class.java, ada.id) shouldBe true
        invariantHolds()
        lift(ada.id).outcome shouldBe SuspensionOutcome.LIFTED
        status(ada.id) shouldBe "ACTIVE"
    }

    @Test
    fun `lifting an active account is NOT_SUSPENDED and writes nothing`() {
        val ada = signUp(verified = true)
        val row = userRow(ada.id)

        val result = lift(ada.id)

        result.outcome shouldBe SuspensionOutcome.NOT_SUSPENDED
        result.statusBefore shouldBe AccountStatus.ACTIVE
        userRow(ada.id) shouldBe row
        spans(ada.id).shouldBeEmpty()
    }

    // --- accounts that are gone, or never were ---

    @Test
    fun `a deleted account is GONE both ways and nothing is written`() {
        val ada = signUp(verified = true)
        deletion.request(UserId(ada.id), PASSWORD)
        clock.advance(Duration.ofDays(14))
        deletions.executeDue(LIMIT) shouldBe 1
        val row = userRow(ada.id)

        val suspended = suspend(ada.id)
        val lifted = lift(ada.id)

        suspended.outcome shouldBe SuspensionOutcome.GONE
        suspended.statusBefore shouldBe AccountStatus.DELETED
        suspended.deletionDueAt.shouldBeNull()
        lifted.outcome shouldBe SuspensionOutcome.GONE
        lifted.statusBefore shouldBe AccountStatus.DELETED
        userRow(ada.id) shouldBe row
        spans(ada.id).shouldBeEmpty()
    }

    @Test
    fun `PENDING_DELETION, which no code path writes, is GONE both ways`() {
        val ada = signUp(verified = true)
        // ADR-0058 decision 2: nothing sets it any more, so only SQL can make the case.
        jdbc.update("UPDATE users SET status = 'PENDING_DELETION' WHERE id = ?", ada.id)
        val row = userRow(ada.id)

        suspend(ada.id).let {
            it.outcome shouldBe SuspensionOutcome.GONE
            it.statusBefore shouldBe AccountStatus.PENDING_DELETION
        }
        lift(ada.id).outcome shouldBe SuspensionOutcome.GONE
        userRow(ada.id) shouldBe row
        spans(ada.id).shouldBeEmpty()
    }

    @Test
    fun `an id that names nobody is NO_SUCH_ACCOUNT with no status`() {
        val nobody = UUID.randomUUID()

        suspend(nobody).let {
            it.outcome shouldBe SuspensionOutcome.NO_SUCH_ACCOUNT
            it.statusBefore.shouldBeNull()
            it.deletionDueAt.shouldBeNull()
        }
        lift(nobody).let {
            it.outcome shouldBe SuspensionOutcome.NO_SUCH_ACCOUNT
            it.statusBefore.shouldBeNull()
        }
        jdbc.queryForObject("SELECT count(*) FROM account_suspensions", Int::class.java) shouldBe 0
    }

    // --- a pending deletion (ruling 15) ---

    @Test
    fun `a pending deletion is said with its due instant, and is carried out on time while suspended`() {
        val ada = signUp(verified = true)
        val request = deletion.request(UserId(ada.id), PASSWORD)
        clock.advance(Duration.ofDays(2))

        val result = suspend(ada.id)

        result.outcome shouldBe SuspensionOutcome.SUSPENDED
        result.deletionDueAt shouldBe request.cancellableUntil
        clock.set(request.cancellableUntil)
        deletions.executeDue(LIMIT) shouldBe 1
        status(ada.id) shouldBe "DELETED"
        // Plan ruling 2: erasure leaves the span as it was, dates and an anonymised id.
        spans(ada.id).single().second.shouldBeNull()
    }

    @Test
    fun `lifting says the pending deletion too, and an already-suspended answer does`() {
        val ada = signUp(verified = true)
        suspend(ada.id)
        val request = deletion.request(UserId(ada.id), PASSWORD)

        suspend(ada.id).deletionDueAt shouldBe request.cancellableUntil
        lift(ada.id).deletionDueAt shouldBe request.cancellableUntil
        lift(ada.id).deletionDueAt shouldBe request.cancellableUntil
    }

    // --- the read port ---

    @Test
    fun `AccountStanding answers the open spans among the ids asked, and every span of them`() {
        val ada = signUp(verified = true)
        val bea = signUp(verified = true)
        val cy = signUp(verified = true)
        val adaFrom = clock.instant()
        suspend(ada.id)
        clock.advance(Duration.ofHours(1))
        val beaFrom = clock.instant()
        suspend(bea.id)
        clock.advance(Duration.ofHours(1))
        val beaTo = clock.instant()
        lift(bea.id)
        clock.advance(Duration.ofHours(1))
        val beaAgain = clock.instant()
        suspend(bea.id)
        val dee = signUp(verified = true)
        suspend(dee.id)

        val asked = listOf(ada.id, bea.id, cy.id, UUID.randomUUID())
        transactions.execute { standing.suspendedAmong(asked) } shouldBe setOf(ada.id, bea.id)
        transactions.execute { standing.suspensionsOf(asked) }!! shouldContainExactlyInAnyOrder
            listOf(
                SuspensionSpan(ada.id, adaFrom, null),
                SuspensionSpan(bea.id, beaFrom, beaTo),
                SuspensionSpan(bea.id, beaAgain, null),
            )
        standing.suspendedAmong(emptyList()).shouldBeEmpty()
        standing.suspensionsOf(emptySet()).shouldBeEmpty()
    }

    // --- the boundary ---

    @Test
    fun `either write outside a transaction is refused, and nothing is written`() {
        val ada = signUp(verified = true)

        shouldThrow<IllegalTransactionStateException> { suspension.suspend(ada.id) }
        shouldThrow<IllegalTransactionStateException> { suspension.lift(ada.id) }

        status(ada.id) shouldBe "ACTIVE"
        spans(ada.id).shouldBeEmpty()
    }

    @Test
    fun `the result prints its outcome and no instant`() {
        val ada = signUp(verified = true)
        val request = deletion.request(UserId(ada.id), PASSWORD)

        val printed = suspend(ada.id).toString()

        printed shouldNotContain request.cancellableUntil.toString()
        printed shouldBe "SuspensionResult(outcome=SUSPENDED, statusBefore=ACTIVE)"
    }

    // --- helpers ---

    /** Each port call in a transaction of its own, as the console's, and the invariant checked after it. */
    private fun suspend(userId: UUID): SuspensionResult =
        checkNotNull(transactions.execute { suspension.suspend(userId) }).also { invariantHolds() }

    private fun lift(userId: UUID): SuspensionResult =
        checkNotNull(transactions.execute { suspension.lift(userId) }).also { invariantHolds() }

    /** Spec §7: for every live account, `status = SUSPENDED` exactly when an open span exists. */
    private fun invariantHolds() {
        jdbc
            .queryForList(
                "SELECT u.id FROM users u WHERE u.status <> 'DELETED' AND (u.status = 'SUSPENDED') <> EXISTS " +
                    "(SELECT 1 FROM account_suspensions s WHERE s.user_id = u.id AND s.lifted_at IS NULL)",
                UUID::class.java,
            ).shouldBeEmpty()
    }

    private fun status(userId: UUID): String? = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String::class.java, userId)

    private fun tokensInvalidBefore(userId: UUID): Instant? =
        jdbc.queryForObject("SELECT tokens_invalid_before FROM users WHERE id = ?", Timestamp::class.java, userId)?.toInstant()

    private fun userRow(userId: UUID): String? =
        jdbc.queryForObject("SELECT row_to_json(u)::text FROM users u WHERE id = ?", String::class.java, userId)

    private fun spans(userId: UUID): List<Pair<Instant, Instant?>> =
        jdbc.query(
            "SELECT suspended_at, lifted_at FROM account_suspensions WHERE user_id = ? ORDER BY suspended_at",
            { rs, _ -> rs.getTimestamp("suspended_at").toInstant() to rs.getTimestamp("lifted_at")?.toInstant() },
            userId,
        )

    private fun liveRefreshTokens(account: Account): Int =
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Int::class.java, account.id)!!

    private fun pushTokens(account: Account): Int =
        jdbc.queryForObject("SELECT count(*) FROM push_tokens WHERE user_id = ?", Int::class.java, account.id)!!

    /** Registered over HTTP (and verified by the emailed link, when asked): the account as a person makes it. */
    private fun signUp(verified: Boolean): Account {
        val email = "u-${UUID.randomUUID()}@example.com"
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"$PASSWORD","displayName":"Ada","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        val id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID::class.java, email)!!
        val secret = awaitSecretFor(email)
        if (verified) verify(secret)
        return Account(id, email, if (verified) null else secret)
    }

    private fun awaitSecretFor(email: String): String {
        var secret: String? = null
        await().atMost(WAIT).until {
            secret =
                emails.sent
                    .toList()
                    .filter { it.to == email }
                    .firstNotNullOfOrNull { Regex("""token=([A-Za-z0-9_-]+)""").find(it.text)?.groupValues?.get(1) }
            secret != null
        }
        return secret.shouldNotBeNull()
    }

    private fun verify(secret: String) {
        mockMvc
            .post("/api/v1/auth/verify-email") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"token": "$secret"}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun login(
        account: Account,
        device: String? = null,
    ): String {
        val response =
            mockMvc
                .post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"${account.email}","password":"$PASSWORD"${device?.let { ""","device":$it""" } ?: ""}}"""
                }.andReturn()
                .response
        response.status shouldBe 200
        return json.readTree(response.contentAsString)["accessToken"].asText()
    }

    private fun registerPushToken(access: String) {
        mockMvc
            .put("/api/v1/me/devices/current/push-token") {
                header(HttpHeaders.AUTHORIZATION, "Bearer $access")
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"push-${UUID.randomUUID()}"}"""
            }.andReturn()
            .response.status shouldBe 204
    }

    private class Account(
        val id: UUID,
        val email: String,
        /** The emailed verification secret, kept for an account not yet verified. */
        val secret: String?,
    )

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val LIMIT = 100
        const val PIXEL = """{"platform":"ANDROID","appVersion":"1.0.3","osVersion":"16"}"""

        /** Fractional, so a bump set from the instant itself rather than its next whole second would show. */
        val START: Instant = Instant.parse("2026-11-13T10:00:00.123456Z")
        val WAIT: Duration = Duration.ofSeconds(5)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
