package com.moyi.notification.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.PushPlatform
import com.moyi.notification.api.PushToken
import com.moyi.notification.api.PushTokenRegistered
import com.moyi.notification.api.PushTokenRegistration
import com.moyi.notification.api.PushTokens
import com.moyi.notification.infra.NotificationTestApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `notification.api.PushTokens` against the real table: one token per device,
 * one device per token, and the fold ADR-0025 waited for.
 */
@SpringBootTest(classes = [NotificationTestApplication::class])
internal class PushTokenRegistryTest(
    @Autowired private val pushTokens: PushTokens,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
) : PostgresIntegrationTest() {
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val start = Instant.parse("2026-10-27T18:00:00Z")

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE push_tokens")
        clock.set(start)
    }

    @Test
    fun `a device holds the token it registered, with its platform, and a new one replaces it`() {
        val phone = UUID.randomUUID()
        register(phone, ada, "token-1", PushPlatform.IOS) shouldBe PushTokenRegistered(emptySet())
        clock.advance(Duration.ofMinutes(5))
        register(phone, ada, "token-2", PushPlatform.IOS) shouldBe PushTokenRegistered(emptySet())

        val row = jdbc.queryForMap("SELECT * FROM push_tokens WHERE device_id = ?", phone)
        row["token"] shouldBe "token-2"
        row["platform"] shouldBe "IOS"
        row["user_id"] shouldBe ada
        row["updated_at"] shouldBe java.sql.Timestamp.from(start.plus(Duration.ofMinutes(5)))
        count() shouldBe 1
    }

    @Test
    fun `a token registered by a second sign-in of the same person moves to it, and the first device is folded`() {
        // ADR-0025: two sign-ins from one phone were two device rows until a token said they were one.
        val earlier = UUID.randomUUID()
        val later = UUID.randomUUID()
        register(earlier, ada, "token-phone")

        register(later, ada, "token-phone") shouldBe PushTokenRegistered(setOf(earlier))

        devicesHolding("token-phone") shouldContainExactlyInAnyOrder listOf(later)
    }

    @Test
    fun `a token registered by somebody else's device moves to it, and the earlier holder is not reported`() {
        // A phone that changed hands without a sign-out: Ada's pushes must not reach Bea's lock screen.
        val adas = UUID.randomUUID()
        val beas = UUID.randomUUID()
        register(adas, ada, "token-shared-phone")

        register(beas, bea, "token-shared-phone") shouldBe PushTokenRegistered(emptySet())

        devicesHolding("token-shared-phone") shouldContainExactlyInAnyOrder listOf(beas)
    }

    @Test
    fun `forgetting a device removes its token and no other`() {
        val phone = UUID.randomUUID()
        val tablet = UUID.randomUUID()
        register(phone, ada, "token-phone")
        register(tablet, ada, "token-tablet")

        transactions.execute { pushTokens.forget(phone) }
        transactions.execute { pushTokens.forget(UUID.randomUUID()) }

        devicesHolding("token-phone").shouldBeEmpty()
        devicesHolding("token-tablet") shouldContainExactlyInAnyOrder listOf(tablet)
    }

    @Test
    fun `forgetting a person removes every token of theirs and nobody else's`() {
        register(UUID.randomUUID(), ada, "token-ada-1")
        register(UUID.randomUUID(), ada, "token-ada-2")
        val beas = UUID.randomUUID()
        register(beas, bea, "token-bea")

        transactions.execute { pushTokens.forgetAllOf(ada) }

        count() shouldBe 1
        devicesHolding("token-bea") shouldContainExactlyInAnyOrder listOf(beas)
    }

    @Test
    fun `nothing changes a token outside the caller's transaction`() {
        // A logout's forget must commit with the logout, or not at all.
        val registration = PushTokenRegistration(UUID.randomUUID(), ada, PushPlatform.ANDROID, PushToken("token-x"))
        shouldThrow<IllegalTransactionStateException> { pushTokens.register(registration) }
        shouldThrow<IllegalTransactionStateException> { pushTokens.forget(registration.deviceId) }
        shouldThrow<IllegalTransactionStateException> { pushTokens.forgetAllOf(ada) }
        count() shouldBe 0
    }

    private fun register(
        deviceId: UUID,
        userId: UUID,
        token: String,
        platform: PushPlatform = PushPlatform.ANDROID,
    ): PushTokenRegistered =
        transactions.execute { pushTokens.register(PushTokenRegistration(deviceId, userId, platform, PushToken(token))) }!!

    private fun devicesHolding(token: String): List<UUID> =
        jdbc.queryForList("SELECT device_id FROM push_tokens WHERE token = ?", UUID::class.java, token).filterNotNull()

    private fun count(): Int = jdbc.queryForObject("SELECT count(*) FROM push_tokens", Int::class.java)!!
}
