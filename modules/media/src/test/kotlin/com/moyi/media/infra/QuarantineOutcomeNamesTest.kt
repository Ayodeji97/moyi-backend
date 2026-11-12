package com.moyi.media.infra

import com.moyi.media.api.BlockOutcome
import com.moyi.media.api.DeleteOutcome
import com.moyi.media.api.ReleaseOutcome
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource
import kotlin.enums.EnumEntries

/**
 * The port's outcome enums are the functions' answers, both ways (A2 spec §6.1): every
 * `RETURN '…'` literal in the function's source, as the migrated database holds it, is a name
 * in its enum, and every name in the enum is one of those literals or a refusal of the port's
 * own, listed here. A function that grows an answer, or an enum that grows a name no function
 * gives, fails here rather than at a moderator's click.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
internal class QuarantineOutcomeNamesTest(
    @Autowired dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `release's outcomes are media_quarantine_release's answers and media_give_back's, which it returns through`() {
        matches(ReleaseOutcome.entries, listOf("media_quarantine_release", "media_give_back"), portOnly = emptySet())
    }

    @Test
    fun `block's outcomes are media_quarantine_block's answers and the port's report refusal`() {
        matches(BlockOutcome.entries, listOf("media_quarantine_block"), portOnly = setOf("REPORT_HELD"))
    }

    @Test
    fun `delete's outcomes are media_quarantine_delete's answers and the port's two refusals`() {
        matches(DeleteOutcome.entries, listOf("media_quarantine_delete"), portOnly = setOf("EVIDENCE_SERVER_ONLY", "REPORT_HELD"))
    }

    private fun matches(
        outcomes: EnumEntries<*>,
        functions: List<String>,
        portOnly: Set<String>,
    ) {
        val names = outcomes.map { it.name }.toSet()
        val answers = functions.flatMap(::answersOf).toSet()
        answers.shouldNotBeEmpty()

        (answers - names).shouldBeEmpty()
        (names - answers - portOnly).shouldBeEmpty()
        // A port-only refusal the function itself now answers would hide which of the two said it.
        (portOnly intersect answers).shouldBeEmpty()
    }

    /** Every `RETURN '…'` literal in the current definition of [function] (each has one overload). */
    private fun answersOf(function: String): List<String> {
        val source =
            jdbc.queryForObject(
                "SELECT pg_get_functiondef(p.oid) FROM pg_proc p WHERE p.proname = ?",
                String::class.java,
                function,
            )!!
        return RETURN_LITERAL.findAll(source).map { it.groupValues[1] }.toList()
    }

    private companion object {
        val RETURN_LITERAL = Regex("""RETURN\s+'([A-Z_]+)'""")
    }
}
