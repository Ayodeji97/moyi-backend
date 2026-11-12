package com.moyi.gratitude.infra

import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.api.DismissOutcome
import com.moyi.gratitude.api.ReclassifyOutcome
import com.moyi.gratitude.api.TakedownOutcome
import com.moyi.gratitude.infra.database.ReportModerationStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * The port's outcomes are the functions' answers, both ways (A2 spec §6.1), as
 * `QuarantineOutcomeNamesTest` holds media's: every answer literal in the function's source,
 * as the migrated database holds it, is a name the port knows, and every name the port knows
 * is one of those literals or a refusal of the port's own, listed here. A function that grows
 * an answer, or a port that grows a name no function gives, fails here rather than at a
 * moderator's click.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class ReportOutcomeNamesTest(
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `dismiss's outcomes are entry_report_dismiss's answers and the port's stale refusal`() {
        matches(DismissOutcome.entries.map { it.name }, returnsOf("entry_report_dismiss"), portOnly = setOf("STALE"))
    }

    @Test
    fun `reclassify's outcomes are entry_report_reclassify's answers and the port's stale refusal`() {
        matches(ReclassifyOutcome.entries.map { it.name }, returnsOf("entry_report_reclassify"), portOnly = setOf("STALE"))
    }

    /** `entry_report_uphold` answers its two refusals as a row, `outcome := '…'`, not by `RETURN`. */
    @Test
    fun `uphold's refusals are entry_report_uphold's own answers`() {
        matches(
            ReportModerationStore.UPHOLD_REFUSALS.keys,
            assignedOf("entry_report_uphold", "outcome"),
            portOnly = emptySet(),
        )
    }

    /**
     * `media_report_takedown` answers each asset with a literal of its own (`v_outcome := '…'`)
     * or with what a function it calls returns (`v_outcome := media_quarantine_block(…)`); the
     * functions are read from its source, so one it starts calling is read too.
     */
    @Test
    fun `an upheld asset's outcomes are media_report_takedown's answers and those of the functions it answers with`() {
        val source = definitionOf("media_report_takedown")
        val called = CALLED_INTO_OUTCOME.findAll(source).map { it.groupValues[1] }.toList()
        called shouldContainExactlyInAnyOrder listOf("media_quarantine_block", "media_quarantine_delete")
        val answers = assignedOf("media_report_takedown", "v_outcome") + called.flatMap(::returnsOf)

        matches(TakedownOutcome.entries.map { it.name }, answers, portOnly = emptySet())
    }

    private fun matches(
        known: Collection<String>,
        answers: Collection<String>,
        portOnly: Set<String>,
    ) {
        val names = known.toSet()
        val given = answers.toSet()
        given.shouldNotBeEmpty()

        (given - names).shouldBeEmpty()
        (names - given - portOnly).shouldBeEmpty()
        // A port-only refusal the function itself now answers would hide which of the two said it.
        (portOnly intersect given).shouldBeEmpty()
    }

    /** Every `RETURN '…'` literal in [function]'s current definition. */
    private fun returnsOf(function: String): List<String> =
        RETURN_LITERAL.findAll(definitionOf(function)).map { it.groupValues[1] }.toList()

    /** Every `<variable> := '…'` literal in [function]'s current definition. */
    private fun assignedOf(
        function: String,
        variable: String,
    ): List<String> = Regex("""\b$variable\s*:=\s*'([A-Z_]+)'""").findAll(definitionOf(function)).map { it.groupValues[1] }.toList()

    /** Each of these functions has one overload. */
    private fun definitionOf(function: String): String =
        jdbc.queryForObject(
            "SELECT pg_get_functiondef(p.oid) FROM pg_proc p WHERE p.proname = ?",
            String::class.java,
            function,
        )!!

    private companion object {
        val RETURN_LITERAL = Regex("""RETURN\s+'([A-Z_]+)'""")
        val CALLED_INTO_OUTCOME = Regex("""\bv_outcome\s*:=\s*([a-z_]+)\(""")
    }
}
