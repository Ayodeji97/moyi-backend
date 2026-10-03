package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondId
import com.moyi.bond.infra.BondTestApplication
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * V13's backfill, run against bonds that exist (final whole-branch review,
 * B1).
 *
 * Every other test database is empty when V13 runs, so its
 * `INSERT … SELECT FROM bonds` has only ever inserted nothing. On a deployed
 * database it writes one row per bond, and the application then loads that
 * row through `AnchorInterval`, whose `init` refuses a `first_label` that is
 * not the date at `effective_from` in `zone`. A backfill that disagreed with
 * that — Postgres's `AT TIME ZONE … ::date` and `java.time` are two
 * implementations of one question — would make every `GET /today` and
 * `POST /entries` for the bond a `500`.
 *
 * So: a database of its own, migrated to just before V13 — `spring.flyway.target
 * = 11`, the last version before it on this module's classpath: V12 is
 * `gratitude`'s, which `bond` cannot see, and V13 reads nothing V12 creates —
 * bonds inserted by SQL in every state and in the zones where a
 * date is easiest to get wrong, then V13 for real, then the application's own
 * loader, [AnchorIntervalStore.timelineOf].
 *
 * **Its own Postgres**, not the shared one: every other context in this JVM
 * has already migrated that one to V13. `ddl-auto` is off because the table
 * `AnchorIntervalEntity` maps does not exist when this context starts.
 */
@SpringBootTest(
    classes = [BondTestApplication::class],
    properties = ["spring.flyway.target=11", "spring.jpa.hibernate.ddl-auto=none"],
)
internal class AnchorIntervalBackfillTest(
    @Autowired private val flyway: Flyway,
    @Autowired private val store: AnchorIntervalStore,
    @Autowired dataSource: DataSource,
) {
    private val jdbc = JdbcTemplate(dataSource)

    private data class Existing(
        val status: String,
        val zone: String,
        val createdAt: String,
        /** Worked out by hand, in the comment beside each case — not computed. */
        val firstLabel: String,
        val id: UUID = UUID.randomUUID(),
    )

    private val bonds =
        listOf(
            // Lagos is UTC+1 all year: 11:00 on the 15th.
            Existing("PENDING_MEMBER", "Africa/Lagos", "2026-09-15T10:00:00Z", "2026-09-15"),
            // Kiritimati is UTC+14: its 16th begins at 15T10:00Z. One
            // microsecond before is 23:59:59.999999 on the 15th.
            Existing("ACTIVE", "Pacific/Kiritimati", "2026-09-15T09:59:59.999999Z", "2026-09-15"),
            Existing("ACTIVE", "Pacific/Kiritimati", "2026-09-15T10:00:00Z", "2026-09-16"),
            // Pago Pago is UTC-11: its 15th begins at 15T11:00Z. The UTC date
            // is the 15th on both sides; the bond's date is not.
            Existing("ARCHIVED", "Pacific/Pago_Pago", "2026-09-15T10:59:59.999999Z", "2026-09-14"),
            Existing("ARCHIVED", "Pacific/Pago_Pago", "2026-09-15T11:00:00Z", "2026-09-15"),
            // New York springs forward on 8 March 2026 (EST, UTC-5, until
            // 07:00Z): its 8th begins at 08T05:00Z.
            Existing("PENDING_DELETION", "America/New_York", "2026-03-08T04:59:59.999999Z", "2026-03-07"),
            Existing("PENDING_DELETION", "America/New_York", "2026-03-08T05:00:00Z", "2026-03-08"),
            // ... and falls back on 1 November (EDT, UTC-4, until 06:00Z).
            // 05:30Z is the first 01:30 and 06:30Z the second; both the 1st.
            Existing("ACTIVE", "America/New_York", "2026-11-01T05:30:00Z", "2026-11-01"),
            Existing("ACTIVE", "America/New_York", "2026-11-01T06:30:00Z", "2026-11-01"),
            // New York's 2nd begins at 05:00Z again (EST): 04:59 is still the 1st.
            Existing("ACTIVE", "America/New_York", "2026-11-02T04:59:59.999999Z", "2026-11-01"),
            // London on BST (UTC+1) in October: its 25th begins at 24T23:00Z.
            Existing("ACTIVE", "Europe/London", "2026-10-24T22:59:59.999999Z", "2026-10-24"),
            Existing("ACTIVE", "Europe/London", "2026-10-24T23:00:00Z", "2026-10-25"),
            // Kathmandu is UTC+5:45: its 11th begins at 10T18:15Z.
            Existing("ACTIVE", "Asia/Kathmandu", "2026-06-10T18:14:59.999999Z", "2026-06-10"),
            Existing("ACTIVE", "Asia/Kathmandu", "2026-06-10T18:15:00Z", "2026-06-11"),
            // Chatham on daylight time (UTC+13:45) in January: its 2nd begins at 01T10:15Z.
            Existing("DELETED", "Pacific/Chatham", "2026-01-01T10:15:00Z", "2026-01-02"),
            // A bond from years back: Tokyo is UTC+9, so 08:00 on the 2nd.
            Existing("ARCHIVED", "Asia/Tokyo", "2019-03-01T23:00:00Z", "2019-03-02"),
        )

    @Test
    fun `V13 gives every existing bond one open interval the application can load`() {
        // Before V13: no table, and the bonds go in by SQL, as they would
        // already be in a deployed database.
        jdbc.queryForObject("SELECT to_regclass('public.bond_anchor_intervals')::text", String::class.java).shouldBeNull()
        bonds.forEach(::insertBond)
        // One whose anchor was changed under B5 before V13 existed: the
        // backfill knows only the zone the bond has now.
        val moved = Existing("ACTIVE", "Asia/Tokyo", "2026-09-15T15:30:00Z", "2026-09-16")
        insertBond(moved)
        jdbc.update("UPDATE bonds SET timezone_changed_at = created_at + interval '3 days' WHERE id = ?", moved.id)

        Flyway
            .configure()
            .configuration(flyway.configuration)
            .target(MigrationVersion.fromVersion("13"))
            .load()
            .migrate()

        jdbc.queryForObject("SELECT count(*) FROM bond_anchor_intervals", Int::class.java) shouldBe bonds.size + 1
        (bonds + moved).forEach { bond ->
            val row =
                jdbc.queryForMap(
                    "SELECT zone, first_label::text AS first_label, effective_to, " +
                        "effective_from = b.created_at AS from_creation, i.created_at = b.created_at AS stamped_at_creation " +
                        "FROM bond_anchor_intervals i JOIN bonds b ON b.id = i.bond_id WHERE i.bond_id = ?",
                    bond.id,
                )
            row["zone"] shouldBe bond.zone
            row["first_label"] shouldBe bond.firstLabel
            row["effective_to"].shouldBeNull()
            row["from_creation"] shouldBe true
            row["stamped_at_creation"] shouldBe true

            // The application's own loader. `AnchorInterval.init` throws here
            // if Postgres and java.time disagree about the label.
            val timeline = store.timelineOf(BondId(bond.id))
            val interval = timeline.intervals.single()
            interval.zone shouldBe ZoneId.of(bond.zone)
            interval.effectiveFrom shouldBe Instant.parse(bond.createdAt)
            interval.effectiveTo.shouldBeNull()
            interval.firstLabel shouldBe LocalDate.parse(bond.firstLabel)
            // And it answers: the bond's first day carries that label and
            // begins at the bond's creation, not at a midnight before it.
            val firstDay = timeline.dayBoundsAt(Instant.parse(bond.createdAt)).shouldNotBeNull()
            firstDay.date shouldBe LocalDate.parse(bond.firstLabel)
            firstDay.startsAt shouldBe Instant.parse(bond.createdAt)
        }
    }

    private fun insertBond(bond: Existing) {
        jdbc.update(
            """
            INSERT INTO bonds (id, name, anchor_timezone, status, created_by, created_at, archived_at, deletion_requested_at)
            VALUES (?, 'Us', ?, ?, ?, ?::timestamptz,
                    CASE WHEN ? IN ('ARCHIVED', 'DELETED') THEN ?::timestamptz + interval '1 day' END,
                    CASE WHEN ? = 'PENDING_DELETION' THEN ?::timestamptz + interval '1 day' END)
            """.trimIndent(),
            bond.id,
            bond.zone,
            bond.status,
            UUID.randomUUID(),
            bond.createdAt,
            bond.status,
            bond.createdAt,
            bond.status,
            bond.createdAt,
        )
    }

    companion object {
        @Suppress("unused")
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18").also { it.start() }
    }
}
