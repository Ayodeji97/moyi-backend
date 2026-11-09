package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.NotFoundException
import com.moyi.gratitude.api.ArchiveExports
import com.moyi.gratitude.api.ExportedDay
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The export's reading of a member's archive (FR-009, T-20, ADR-0059 decision 3): their own
 * entries, their partner's once revealed to them, never a locked one, and an entry withdrawn on a
 * block as a tombstone for both members, from the moment the block commits.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
internal class ArchiveExportsTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val exports: ArchiveExports,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entry_favourites, reactions")
        rig.clear()
        users.clear()
    }

    @Test
    fun `a member's export holds their own words and their partner's revealed ones, with their bookmark and reaction`() {
        val bond = written()
        val beasThirteen = idOf(bond, bea, "Bea thirteen")
        rig.react(ada, beasThirteen.toString(), "HEART")
        jdbc.update("INSERT INTO entry_favourites (entry_id, member_id) VALUES (?, ?)", beasThirteen, rig.memberId(bond, ada))

        val days = everyDay(ada, bond)

        days.map { it.date.toString() } shouldBe listOf("2026-09-13")
        val thirteen = days.single().entries
        thirteen.map { it.text to it.mine } shouldBe listOf("Ada thirteen" to true, "Bea thirteen" to false)
        val beas = thirteen.single { !it.mine }
        beas.favourited shouldBe true
        beas.reaction shouldBe "HEART"
        thirteen.single { it.mine }.favourited shouldBe false
    }

    @Test
    fun `a partner's entry not yet revealed to the member is not in it at all`() {
        val bond = written()

        // Bea wrote on the 14th and Ada did not: it is locked to Ada, and Ada's archive has no 14th.
        everyDay(ada, bond).flatMap { it.entries }.map { it.text } shouldBe listOf("Ada thirteen", "Bea thirteen")
        // Bea's own archive has it.
        everyDay(bea, bond).flatMap { it.entries }.mapNotNull { it.text } shouldBe
            listOf("Bea fourteen", "Bea thirteen", "Ada thirteen")
    }

    @Test
    fun `what a blocker withdrew is a tombstone in both exports from the moment the block commits`() {
        val bond = written()

        rig.block(bea, bond, withdraw = true)

        // Before the erasure has run: the gate, not the rows, makes these tombstones.
        val adas = everyDay(ada, bond).flatMap { it.entries }
        adas.single { it.mine }.text shouldBe "Ada thirteen"
        val withdrawn = adas.single { !it.mine }
        withdrawn.status shouldBe "DELETED"
        withdrawn.text.shouldBeNull()
        withdrawn.reaction.shouldBeNull()
        everyDay(bea, bond).flatMap { it.entries }.filter { it.mine }.forEach { it.text.shouldBeNull() }
        everyDay(ada, bond).toString() shouldNotContain "Bea"
    }

    @Test
    fun `a member who left keeps their archive in the export`() {
        val bond = written()

        rig.leave(ada, bond)

        everyDay(ada, bond).flatMap { it.entries }.map { it.text } shouldBe listOf("Ada thirteen", "Bea thirteen")
    }

    @Test
    fun `somebody who was never a member is the guard's one 404`() {
        val bond = written()

        shouldThrow<NotFoundException> { exports.page(users.verified("Cleo"), UUID.fromString(bond), null) }
        shouldThrow<NotFoundException> { exports.page(ada, UUID.randomUUID(), null) }
    }

    @Test
    fun `a bond with nothing written is an empty archive`() {
        clock.set(at(12))
        val bond = rig.pair(ada, bea)

        everyDay(ada, bond).shouldBeEmpty()
    }

    /** Every page, as the export's job walks them. */
    private fun everyDay(
        user: UUID,
        bond: String,
    ): List<ExportedDay> {
        val days = mutableListOf<ExportedDay>()
        var before: java.time.LocalDate? = null
        do {
            val page = exports.page(user, UUID.fromString(bond), before)
            days += page.days
            before = page.next
        } while (before != null)
        return days
    }

    /** Ada and Bea on the 13th (revealed); Bea alone on the 14th (still open, locked to Ada). */
    private fun written(): String {
        clock.set(at(12))
        val bond = rig.pair(ada, bea)
        clock.set(at(13))
        rig.submit(ada, bond, "Ada thirteen")
        rig.submit(bea, bond, "Bea thirteen")
        clock.set(at(14))
        rig.submit(bea, bond, "Bea fourteen")
        return bond
    }

    private fun idOf(
        bond: String,
        author: UUID,
        text: String,
    ): UUID = rig.wholeEntries(bond).single { it["author_member_id"] == rig.memberId(bond, author) && it["text"] == text }["id"] as UUID

    private fun at(day: Int): Instant = Instant.parse("2026-09-${day}T10:00:00Z")
}
