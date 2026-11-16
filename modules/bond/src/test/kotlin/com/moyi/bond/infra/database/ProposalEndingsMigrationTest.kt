package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.ProposalId
import com.moyi.bond.domain.ProposalOutcome
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeAccountStanding
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.bond.infra.NoNotifications
import com.moyi.common.events.EventConsumer
import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.api.AccountStanding
import com.moyi.identity.api.UserDirectory
import com.moyi.notification.api.Notifications
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * V70's backfill, run against proposals that already ended (issue #205, spec §6.1).
 *
 * Every other test database has no proposal when V70 runs, so its two `UPDATE`s have only
 * ever updated nothing. On a deployed database they decide what each old ending reads as, and
 * the rule is that **a cause is written only where it is certain**: a wrong "declined" is
 * worse than "unknown", so whatever a member called off before V70 reads `UNRECORDED`.
 *
 * So, [AnchorIntervalBackfillTest]'s pattern: a database of its own, migrated to just before
 * V70 — `spring.flyway.target = 68`, the last version before it on this module's classpath
 * (V68 is `notification`'s; V69 is `admin`'s, which `bond` cannot see, and V70 reads nothing
 * either creates) — rows inserted by SQL in the shape the code wrote them before V70, then V70
 * for real, then the application's own reader, [ProposalStore.findInBond] and
 * [com.moyi.bond.domain.Proposal.outcome].
 *
 * **Its own Postgres**: every other context in this JVM has already migrated the shared one
 * past V70. `ddl-auto` is off because `BondProposalEntity` maps columns V70 has yet to add.
 */
@SpringBootTest(
    classes = [ProposalEndingsMigrationTest.BeforeV70::class],
    properties = ["spring.flyway.target=68", "spring.jpa.hibernate.ddl-auto=none"],
)
internal class ProposalEndingsMigrationTest(
    @Autowired private val flyway: Flyway,
    @Autowired private val proposals: ProposalStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) {
    private val jdbc = JdbcTemplate(dataSource)

    private val bondId = UUID.randomUUID()
    private val ada = UUID.randomUUID()
    private val ben = UUID.randomUUID()

    /** Archived at this instant; `EndBond` cancelled the open proposals at the same one. */
    private val archivedAt = "2026-10-20T12:00:00Z"

    @Test
    fun `V70 records a cause only where it is certain, and the rest read UNRECORDED`() {
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name = 'bond_proposals' AND column_name = 'cancel_cause'",
            Int::class.java,
        ) shouldBe 0
        insertBond()

        // closeLapsed: cancelled after it expired.
        val lapsedClose = insertProposal("TIMEZONE_CHANGE", "2026-09-01T10:00:00Z", cancelledAt = "2026-09-10T09:00:00Z")
        // A bond that ended after the proposal lapsed: lapsed either way, and the first
        // UPDATE says so before the second can call it BOND_ENDED.
        val endedAfterLapse = insertProposal("SEMANTIC_SEARCH_ON", "2026-10-01T10:00:00Z", cancelledAt = archivedAt)
        // cancelLiveOf at the instant the bond was archived, while it was live.
        val endedClose = insertProposal("DELETION", "2026-10-18T10:00:00Z", cancelledAt = archivedAt)
        // A member called it off while it was live: which member, nothing recorded.
        val memberCancel = insertProposal("TIMEZONE_CHANGE", "2026-10-02T10:00:00Z", cancelledAt = "2026-10-03T10:00:00Z")
        // A microsecond before the archive, live: a member's, not the ending's. Not guessed.
        val justBeforeEnd = insertProposal("DELETION", "2026-10-17T10:00:00Z", cancelledAt = "2026-10-20T11:59:59.999999Z")
        // Confirmed: no cause, and V70 must not give it one.
        val confirmed = insertProposal("TIMEZONE_CHANGE", "2026-09-20T10:00:00Z", confirmedAt = "2026-09-21T10:00:00Z")

        Flyway
            .configure()
            .configuration(flyway.configuration)
            .target(MigrationVersion.fromVersion("70"))
            .load()
            .migrate()

        cause(lapsedClose) shouldBe "LAPSED"
        outcome(lapsedClose) shouldBe ProposalOutcome.LAPSED
        cause(endedAfterLapse) shouldBe "LAPSED"
        outcome(endedAfterLapse) shouldBe ProposalOutcome.LAPSED
        cause(endedClose) shouldBe "BOND_ENDED"
        outcome(endedClose) shouldBe ProposalOutcome.BOND_ENDED
        cause(memberCancel).shouldBeNull()
        outcome(memberCancel) shouldBe ProposalOutcome.UNRECORDED
        cause(justBeforeEnd).shouldBeNull()
        outcome(justBeforeEnd) shouldBe ProposalOutcome.UNRECORDED
        cause(confirmed).shouldBeNull()
        outcome(confirmed) shouldBe ProposalOutcome.ACCEPTED
        // No member is ever named by the backfill: it cannot know one.
        jdbc.queryForObject(
            "SELECT count(*) FROM bond_proposals WHERE cancelled_by_member_id IS NOT NULL",
            Int::class.java,
        ) shouldBe 0
    }

    private fun cause(id: UUID): String? =
        jdbc.queryForObject("SELECT cancel_cause FROM bond_proposals WHERE id = ?", String::class.java, id)

    /** The application's own reader: its mapper and `Proposal.init` must accept every backfilled row. */
    private fun outcome(id: UUID): ProposalOutcome =
        transactions
            .execute { proposals.findInBond(BondId(bondId), ProposalId(id)) }
            .shouldNotBeNull()
            .outcome

    private fun insertBond() {
        jdbc.update(
            """
            INSERT INTO bonds (id, name, anchor_timezone, status, created_by, created_at, archived_at)
            VALUES (?, 'Us', 'Africa/Lagos', 'ARCHIVED', ?, '2026-08-01T10:00:00Z'::timestamptz, ?::timestamptz)
            """.trimIndent(),
            bondId,
            UUID.randomUUID(),
            archivedAt,
        )
        listOf(ada to "OWNER", ben to "MEMBER").forEach { (member, role) ->
            jdbc.update(
                """
                INSERT INTO bond_members (id, bond_id, user_id, role, joined_at, left_at, reminder_timezone)
                VALUES (?, ?, ?, ?, '2026-08-01T10:00:00Z'::timestamptz,
                        CASE WHEN ? = 'MEMBER' THEN ?::timestamptz END, 'Africa/Lagos')
                """.trimIndent(),
                member,
                bondId,
                UUID.randomUUID(),
                role,
                role,
                archivedAt,
            )
        }
    }

    /** As the code wrote a proposal before V70: seven days to answer, and no word on why it closed. */
    private fun insertProposal(
        kind: String,
        proposedAt: String,
        cancelledAt: String? = null,
        confirmedAt: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO bond_proposals
                (id, bond_id, kind, payload, proposed_by_member_id, proposed_at, expires_at,
                 confirmed_by_member_id, confirmed_at, cancelled_at)
            VALUES (?, ?, ?, CASE WHEN ? = 'TIMEZONE_CHANGE' THEN 'Europe/London' END, ?,
                    ?::timestamptz, ?::timestamptz + interval '7 days',
                    CASE WHEN ?::timestamptz IS NOT NULL THEN ?::uuid END, ?::timestamptz, ?::timestamptz)
            """.trimIndent(),
            id,
            bondId,
            kind,
            kind,
            ada,
            proposedAt,
            proposedAt,
            confirmedAt,
            ben,
            confirmedAt,
            cancelledAt,
        )
        return id
    }

    /**
     * [BondTestApplication]'s context without an event consumer, as [AnchorIntervalBackfillTest]'s:
     * a context that starts no consumer touches no table at start-up, so the one migration this
     * test is about is the only thing that changes the schema. Nested, so no other scan finds it.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(
        basePackages = ["com.moyi.common", "com.moyi.bond"],
        excludeFilters = [
            ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
            ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
            ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [EventConsumer::class, BondTestApplication::class]),
        ],
    )
    @ConfigurationPropertiesScan("com.moyi.common", "com.moyi.bond")
    @EntityScan("com.moyi.bond")
    @EnableJpaRepositories("com.moyi.bond")
    internal class BeforeV70 {
        @Bean
        fun userDirectory(): UserDirectory = FakeUserDirectory()

        @Bean
        fun accountStanding(): AccountStanding = FakeAccountStanding()

        @Bean
        fun tokenRevocations(): TokenRevocations = TokenRevocations { TokenRevocation(invalidBefore = null) }

        /** `notification` is not scanned here, and `EndBond` asks for its port (ADR-0069). */
        @Bean
        fun notifications(): Notifications = NoNotifications()
    }

    companion object {
        @Suppress("unused")
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer(PostgresIntegrationTest.IMAGE).also { it.start() }
    }
}
