package com.moyi.export.service

import com.moyi.bond.api.ExportedBond
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.export.api.DataExportJobs
import com.moyi.export.infra.ExportTestApplication
import com.moyi.export.infra.FakeStorage
import com.moyi.export.infra.People
import com.moyi.export.infra.database.DataExports
import com.moyi.gratitude.api.ExportedDay
import com.moyi.gratitude.api.ExportedEntry
import com.moyi.identity.api.ExportedAccount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.sql.DataSource

/**
 * What a failing build leaves behind (ADR-0059 decision 9): a checked `IOException` is an attempt
 * spent like any other, one export's failure does not stop the batch, an export whose attempts are
 * spent is FAILED and never claimed again, and a build can give back only its own claim.
 */
@SpringBootTest(classes = [ExportTestApplication::class])
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class ExportFailuresTest(
    @Autowired private val jobs: DataExportJobs,
    @Autowired private val requests: DataExportRequests,
    @Autowired private val exports: DataExports,
    @Autowired private val people: People,
    @Autowired private val storage: FakeStorage,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        clear()
        clock.set(ExportTestApplication.START)
        listOf(ada, bea).forEach { people.accounts[it] = account(it) }
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE data_exports")
        people.clear()
        storage.clear()
    }

    @Test
    fun `a storage stream cut off part-way is an attempt spent, and the next export in the batch is still built`() {
        val photo = UUID.randomUUID()
        withPhoto(ada, photo)
        storage.unreadable += photo
        val adas = requests.request(ada).export.id
        clock.advance(Duration.ofSeconds(1))
        val beas = requests.request(bea).export.id

        jobs.prepareDue(5) shouldBe 1

        row(adas) shouldBe ("PREPARING" to 1)
        statusOf(beas) shouldBe "READY"
        repeat(2) { jobs.prepareDue(5) }
        row(adas) shouldBe ("FAILED" to 3)
    }

    @Test
    fun `a checked IOException from the storage is given back, and FAILED on the third`() {
        val id = requests.request(ada).export.id
        storage.failingWith = IOException("No space left on device (made to by the test)")

        jobs.prepareDue(5) shouldBe 0

        row(id) shouldBe ("PREPARING" to 1)
        claimOf(id) shouldBe null
        repeat(2) { jobs.prepareDue(5) }
        row(id) shouldBe ("FAILED" to 3)
    }

    @Test
    fun `an export whose attempts were spent by a build that died is FAILED, never claimed again`() {
        val id = requests.request(ada).export.id
        // The third attempt's process died: its claim lapsed with the row still PREPARING.
        jdbc.update("UPDATE data_exports SET attempts = 3, claimed_until = ? WHERE id = ?", java.sql.Timestamp.from(clock.instant()), id)
        clock.advance(Duration.ofMinutes(1))

        jobs.prepareDue(5) shouldBe 0

        row(id) shouldBe ("FAILED" to 3)
        storage.archives.isEmpty() shouldBe true
        // And a new request is a new export, not the stuck one.
        (requests.request(ada).export.id == id) shouldBe false
    }

    @Test
    fun `a build gives back only its own claim, not one taken after its own was`() {
        val id = requests.request(ada).export.id
        val first = clock.instant().plus(Duration.ofMinutes(15))
        exports.claim(id, clock.instant(), first, 3) shouldBe 1
        // A withdrawal restarted it, and another runner claimed it again.
        exports.invalidateFor(listOf(ada), clock.instant())
        val second = clock.instant().plus(Duration.ofMinutes(16))
        exports.claim(id, clock.instant(), second, 3) shouldBe 1

        exports.release(id, first)
        exports.fail(id, first, clock.instant())

        row(id) shouldBe ("PREPARING" to 1)
        claimOf(id) shouldBe second
    }

    private fun withPhoto(
        user: UUID,
        photo: UUID,
    ) {
        val bond = UUID.randomUUID()
        people.bonds[user] = listOf(bondOf(bond))
        people.days[user to bond] =
            listOf(
                ExportedDay(
                    LocalDate.parse("2026-10-01"),
                    "REVEALED",
                    listOf(
                        ExportedEntry(
                            UUID.randomUUID(),
                            mine = true,
                            status = "REVEALED",
                            text = "words",
                            createdAt = clock.instant(),
                            intendedAt = clock.instant(),
                            favourited = false,
                            reaction = null,
                            imageId = photo,
                            voiceId = null,
                        ),
                    ),
                ),
            )
        storage.media[photo] = user to "a photo".toByteArray()
    }

    private fun row(id: UUID): Pair<String, Int> =
        jdbc.queryForObject("SELECT status, attempts FROM data_exports WHERE id = ?", { rs, _ -> rs.getString(1) to rs.getInt(2) }, id)!!

    private fun statusOf(id: UUID): String = row(id).first

    private fun claimOf(id: UUID): Instant? =
        jdbc.queryForObject("SELECT claimed_until FROM data_exports WHERE id = ?", java.sql.Timestamp::class.java, id)?.toInstant()

    private fun account(id: UUID) =
        ExportedAccount(id, "$id@example.com", clock.instant(), "Someone", "en", "ACTIVE", clock.instant(), null, emptyList())

    private fun bondOf(id: UUID) =
        ExportedBond(
            id = id,
            name = "Us",
            type = "COUPLE",
            status = "ACTIVE",
            createdAt = clock.instant(),
            archivedAt = null,
            anchorTimezone = "Africa/Lagos",
            revealTimeLocal = null,
            strictMode = false,
            semanticSearchEnabled = false,
            role = "OWNER",
            joinedAt = clock.instant(),
            leftAt = null,
            reminderTimeLocal = LocalTime.of(20, 0),
            reminderTimezone = "Africa/Lagos",
            quietHoursStart = null,
            quietHoursEnd = null,
            nicknameForOther = null,
            partnerDisplayName = "Bea",
            blockedAt = null,
            withdrewEntriesAt = null,
        )
}
