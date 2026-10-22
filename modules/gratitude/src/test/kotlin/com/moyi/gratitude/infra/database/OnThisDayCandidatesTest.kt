package com.moyi.gratitude.infra.database

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.web.ArchiveRig
import com.moyi.gratitude.web.EntryState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * `ArchiveDays.onThisDay` (ADR-0039 decisions 15 and 16): the days of earlier years on today's
 * month and day that hold **an entry the caller may read in full as far as rows can say**:
 * theirs or revealed (`SEEN_ROW`), not erased (`LIVE_ROW`), its author not known to have
 * withdrawn. Rows by hand; no bond is needed for a statement that takes ids.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
internal class OnThisDayCandidatesTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
    @Autowired json: ObjectMapper,
    @Autowired private val archive: ArchiveDays,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)
    private val bond = UUID.randomUUID().toString()
    private val me = UUID.randomUUID()
    private val partner = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clear() {
        rig.bonds.clear()
    }

    @Test
    fun `earlier years on today's month and day, newest first, and never today or a neighbour`() {
        listOf("2023-09-15", "2025-09-15", "2024-09-15", "2025-09-14", "2025-09-16", "2026-09-15").forEach { both(it) }

        dates(archive.onThisDay(UUID.fromString(bond), me, TODAY, emptySet())) shouldBe
            listOf("2025-09-15", "2024-09-15", "2023-09-15")
    }

    @Test
    fun `a day of tombstones is no candidate, and a day with one live entry is`() {
        day("2025-09-15", mine = EntryState.ERASED_AFTER_REVEAL, partners = EntryState.ERASED_AFTER_REVEAL)
        day("2024-09-15", mine = EntryState.ERASED_AFTER_REVEAL, partners = EntryState.REVEALED)
        day("2023-09-15", mine = EntryState.ERASED_BEFORE_REVEAL, partners = EntryState.NONE)

        dates(archive.onThisDay(UUID.fromString(bond), me, TODAY, emptySet())) shouldBe listOf("2024-09-15")
    }

    @Test
    fun `the partner's unrevealed entry is no candidate for the caller, and the caller's own is`() {
        day("2025-09-15", mine = EntryState.NONE, partners = EntryState.LIVE_UNREVEALED)
        day("2024-09-15", mine = EntryState.LIVE_UNREVEALED, partners = EntryState.NONE)

        dates(archive.onThisDay(UUID.fromString(bond), me, TODAY, emptySet())) shouldBe listOf("2024-09-15")
        dates(archive.onThisDay(UUID.fromString(bond), partner, TODAY, emptySet())) shouldBe listOf("2025-09-15")
    }

    @Test
    fun `a day whose only live entry is a known withdrawn author's is no candidate`() {
        day("2025-09-15", mine = EntryState.NONE, partners = EntryState.REVEALED)
        day("2024-09-15", mine = EntryState.REVEALED, partners = EntryState.REVEALED)

        dates(archive.onThisDay(UUID.fromString(bond), me, TODAY, setOf(partner))) shouldBe listOf("2024-09-15")
        dates(archive.onThisDay(UUID.fromString(bond), me, TODAY, emptySet())) shouldBe listOf("2025-09-15", "2024-09-15")
    }

    @Test
    fun `29 February is a candidate on 29 February only, and 28 February on 28 February only`() {
        listOf("2024-02-28", "2024-02-29", "2025-02-28", "2027-03-01").forEach { both(it) }

        dates(archive.onThisDay(UUID.fromString(bond), me, LocalDate.of(2028, 2, 29), emptySet())) shouldBe listOf("2024-02-29")
        dates(archive.onThisDay(UUID.fromString(bond), me, LocalDate.of(2028, 2, 28), emptySet())) shouldBe
            listOf("2025-02-28", "2024-02-28")
        dates(archive.onThisDay(UUID.fromString(bond), me, LocalDate.of(2028, 3, 1), emptySet())) shouldBe listOf("2027-03-01")
    }

    @Test
    fun `another bond's day on today's date is no candidate`() {
        both("2025-09-15")
        val other = UUID.randomUUID().toString()
        val theirs = rig.insertDay(other, LocalDate.parse("2024-09-15"), "REVEALED")
        rig.insertEntry(theirs, other, UUID.randomUUID(), EntryState.REVEALED, "a stranger's", Instant.parse("2024-09-15T10:00:00Z"))

        // The gate would refuse its entries (NOT_A_MEMBER) and the reader drop the day; this is the statement's own bound.
        dates(archive.onThisDay(UUID.fromString(bond), me, TODAY, emptySet())) shouldBe listOf("2025-09-15")
    }

    private fun both(date: String) = day(date, mine = EntryState.REVEALED, partners = EntryState.REVEALED)

    private fun day(
        date: String,
        mine: EntryState,
        partners: EntryState,
    ) {
        val on = LocalDate.parse(date)
        val day = rig.insertDay(bond, on, "REVEALED")
        val at = Instant.parse("${date}T10:00:00Z")
        rig.insertEntry(day, bond, me, mine, "mine on $date", at)
        rig.insertEntry(day, bond, partner, partners, "theirs on $date", at)
    }

    private fun dates(days: List<ArchiveDay>) = days.map { it.date.toString() }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 15)
    }
}
