package com.moyi.admin.domain

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Spec §9.3's list, both sides: what a member's export shows of an audit row, and everything
 * it withholds. The last test walks every combination of the four inputs, so a row the list
 * does not name can only ever be withheld.
 */
internal class DisclosureTest {
    @Test
    fun `a successful lookup for the member's request or for operations is shown as ACCOUNT_LOOKED_UP`() {
        listOf(LookupPurpose.MEMBER_REQUEST, LookupPurpose.OPERATIONS).forEach { purpose ->
            withClue(purpose) {
                Disclosure.shows(AuditAction.USER_LOOKED_UP, AuditOutcome.SUCCEEDED, purpose, null) shouldBe "ACCOUNT_LOOKED_UP"
            }
        }
    }

    @Test
    fun `a lookup for SAFETY or LEGAL is withheld`() {
        listOf(LookupPurpose.SAFETY, LookupPurpose.LEGAL).forEach { purpose ->
            withClue(purpose) {
                Disclosure.shows(AuditAction.USER_LOOKED_UP, AuditOutcome.SUCCEEDED, purpose, null).shouldBeNull()
            }
        }
    }

    @Test
    fun `each purpose says whether it is disclosed`() {
        LookupPurpose.entries.associateWith { it.disclosed } shouldBe
            mapOf(
                LookupPurpose.MEMBER_REQUEST to true,
                LookupPurpose.OPERATIONS to true,
                LookupPurpose.SAFETY to false,
                LookupPurpose.LEGAL to false,
            )
    }

    @Test
    fun `a lookup with no purpose, written before A2, is withheld`() {
        Disclosure.shows(AuditAction.USER_LOOKED_UP, AuditOutcome.SUCCEEDED, null, null).shouldBeNull()
    }

    @Test
    fun `a removal from a moderation quarantine is shown as MEDIA_REMOVED, and one a report drove is withheld`() {
        Disclosure.shows(AuditAction.MEDIA_DELETED, AuditOutcome.SUCCEEDED, null, false) shouldBe "MEDIA_REMOVED"
        Disclosure.shows(AuditAction.MEDIA_DELETED, AuditOutcome.SUCCEEDED, null, true).shouldBeNull()
    }

    @Test
    fun `a removal whose row does not say whether a report drove it is withheld`() {
        Disclosure.shows(AuditAction.MEDIA_DELETED, AuditOutcome.SUCCEEDED, null, null).shouldBeNull()
    }

    @Test
    fun `views and releases are withheld whatever their facts`() {
        everyRow().filter { it.action in listOf(AuditAction.MEDIA_VIEWED, AuditAction.MEDIA_RELEASED) }.forEach { row ->
            withClue(row) { Disclosure.shows(row.action, row.outcome, row.purpose, row.reportDriven).shouldBeNull() }
        }
    }

    @Test
    fun `a block is withheld, report-driven or not`() {
        listOf(true, false).forEach { reportDriven ->
            Disclosure.shows(AuditAction.MEDIA_BLOCKED, AuditOutcome.SUCCEEDED, null, reportDriven).shouldBeNull()
        }
    }

    @Test
    fun `a refused or failed lookup or removal is withheld`() {
        listOf(AuditOutcome.REFUSED, AuditOutcome.FAILED).forEach { outcome ->
            withClue(outcome) {
                Disclosure.shows(AuditAction.USER_LOOKED_UP, outcome, LookupPurpose.MEMBER_REQUEST, null).shouldBeNull()
                Disclosure.shows(AuditAction.MEDIA_DELETED, outcome, null, false).shouldBeNull()
            }
        }
    }

    @Test
    fun `an uphold's takedown and the report decisions are withheld`() {
        listOf(
            AuditAction.REPORT_UPHELD,
            AuditAction.REPORT_DISMISSED,
            AuditAction.REPORT_RECLASSIFIED,
            AuditAction.REPORT_READ,
        ).forEach { action ->
            withClue(action) { Disclosure.shows(action, AuditOutcome.SUCCEEDED, null, false).shouldBeNull() }
        }
    }

    @Test
    fun `of every combination of action, outcome, purpose and reportDriven, only the listed lookups and the removal are shown`() {
        val shown =
            everyRow().mapNotNull { row ->
                Disclosure.shows(row.action, row.outcome, row.purpose, row.reportDriven)?.let {
                    Shown(row.action, row.purpose, row.reportDriven, it)
                }
            }

        // A lookup's word does not depend on reportDriven (a lookup has none), so each disclosed
        // purpose is shown with any value of it; a removal's does not depend on the purpose.
        shown shouldContainExactlyInAnyOrder
            listOf(LookupPurpose.MEMBER_REQUEST, LookupPurpose.OPERATIONS).flatMap { purpose ->
                listOf(true, false, null).map { Shown(AuditAction.USER_LOOKED_UP, purpose, it, "ACCOUNT_LOOKED_UP") }
            } +
            (LookupPurpose.entries + null).map { Shown(AuditAction.MEDIA_DELETED, it, false, "MEDIA_REMOVED") }
    }

    /** Every combination of the four inputs [Disclosure.shows] reads. */
    private fun everyRow(): List<Row> =
        AuditAction.entries.flatMap { action ->
            AuditOutcome.entries.flatMap { outcome ->
                (LookupPurpose.entries + null).flatMap { purpose ->
                    listOf(true, false, null).map { Row(action, outcome, purpose, it) }
                }
            }
        }

    private data class Row(
        val action: AuditAction,
        val outcome: AuditOutcome,
        val purpose: LookupPurpose?,
        val reportDriven: Boolean?,
    )

    private data class Shown(
        val action: AuditAction,
        val purpose: LookupPurpose?,
        val reportDriven: Boolean?,
        val word: String,
    )
}
