package com.moyi.gratitude.infra.database

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.domain.SearchTerms
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
 * `ArchiveDays.matching` asks erasure of the row that matched (`LIVE_ROW`), and does not rely
 * on the projection having been emptied (final review, concurrency I1).
 *
 * A build from before V25 does not map `text_search`, so an entry it erases keeps its words
 * there and in `search_vector`, during a blue/green overlap or after a rollback across V25.
 * The gate would still drop such a day, so no HTTP answer can show the difference: only the
 * statement can. Rows by hand; no bond is needed for a statement that takes ids.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
internal class SearchCandidatesTest(
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
    fun `an erased entry whose projection an older build left in place is never a match`() {
        val live = rig.insertDay(bond, LocalDate.parse("2026-09-10"), "REVEALED")
        rig.insertEntry(live, bond, me, EntryState.REVEALED, "the lamp on the stairs", at("2026-09-10"))
        val erased = rig.insertDay(bond, LocalDate.parse("2026-09-12"), "REVEALED")
        listOf(EntryState.ERASED_AFTER_REVEAL, EntryState.ERASED_BEFORE_REVEAL).forEach { state ->
            val id = rig.insertEntry(erased, bond, me, state, "", at("2026-09-12"))!!
            // What an older build's erasure leaves: text gone, the projection (and so the vector) not.
            jdbc.update("UPDATE entries SET text_search = 'the lamp in the hall' WHERE id = ?", id) shouldBe 1
        }
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_day_id = ? AND search_vector @@ plainto_tsquery('simple', 'lamp')",
            Long::class.java,
            erased,
        ) shouldBe 2L

        archive.matching(UUID.fromString(bond), me, lamp(), null, LIMIT, emptySet()).map { it.date.toString() } shouldBe
            listOf("2026-09-10")
    }

    @Test
    fun `either mark of an erasure is enough`() {
        val day = rig.insertDay(bond, LocalDate.parse("2026-09-12"), "REVEALED")
        val deletedAt = rig.insertEntry(day, bond, partner, EntryState.REVEALED, "the lamp", at("2026-09-12"))!!
        val deletedStatus = rig.insertEntry(day, bond, me, EntryState.REVEALED, "the lamp", at("2026-09-12"))!!
        jdbc.update("UPDATE entries SET text = NULL, deleted_at = now() WHERE id = ?", deletedAt) shouldBe 1
        jdbc.update("UPDATE entries SET text = NULL, status = 'DELETED' WHERE id = ?", deletedStatus) shouldBe 1

        archive.matching(UUID.fromString(bond), me, lamp(), null, LIMIT, emptySet()) shouldBe emptyList()
    }

    private fun lamp() = SearchTerms.of("lamp")!!

    private fun at(date: String) = Instant.parse("${date}T10:00:00Z")

    private companion object {
        const val LIMIT = 10
    }
}
