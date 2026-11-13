package com.moyi.gratitude.domain

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A5 spec §4: a report's entry may be read when the report is `OPEN`, its target is `ENTRY`,
 * and its reason is one about words (`HARASSMENT`, `VIOLENCE_OR_SELF_HARM`, `OTHER`). Every
 * reason × target × status the schema allows, each with its answer written out here, so a
 * reason added to [ReportReason] fails this test until someone classifies it.
 */
internal class WordsReadableTest {
    @Test
    fun `every reason is classified here, and no other`() {
        BY_REASON.keys shouldBe ReportReason.entries.toSet()
    }

    @Test
    fun `every reason, target and status answers as the spec's rule`() {
        for ((reason, target, status) in CASES) {
            val expected =
                when {
                    status != "OPEN" -> WordsReadable.NOT_OPEN
                    target == "MEMBER" -> WordsReadable.MEMBER_REPORT
                    else -> BY_REASON.getValue(reason)
                }
            withClue("$reason $target $status") { reason.wordsReadable(target, status) shouldBe expected }
        }
    }

    @Test
    fun `exactly the three reasons about words are readable, on an open entry report`() {
        ReportReason.entries.filter { it.wordsReadable("ENTRY", "OPEN") == WordsReadable.YES }.toSet() shouldBe
            setOf(ReportReason.HARASSMENT, ReportReason.VIOLENCE_OR_SELF_HARM, ReportReason.OTHER)
    }

    private companion object {
        val TARGETS = listOf("ENTRY", "MEMBER")
        val STATUSES = listOf("OPEN", "DISMISSED", "UPHELD")

        /** Every reason × target × status. */
        val CASES = ReportReason.entries.flatMap { r -> TARGETS.flatMap { t -> STATUSES.map { s -> Triple(r, t, s) } } }

        /** An open entry report's answer for each reason: the classification a new reason must be given. */
        val BY_REASON =
            mapOf(
                ReportReason.NON_CONSENSUAL to WordsReadable.IMAGE_REASON,
                ReportReason.MINOR_SEXUAL_CONTENT to WordsReadable.IMAGE_REASON,
                ReportReason.VIOLENCE_OR_SELF_HARM to WordsReadable.YES,
                ReportReason.HARASSMENT to WordsReadable.YES,
                ReportReason.OTHER to WordsReadable.YES,
            )
    }
}
