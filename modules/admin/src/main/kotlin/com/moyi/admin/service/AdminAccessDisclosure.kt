package com.moyi.admin.service

import com.moyi.admin.domain.Disclosure
import com.moyi.admin.infra.AdminAccessRows
import com.moyi.export.api.AdminAccess
import com.moyi.export.api.AdminAccessLog
import com.moyi.media.api.QuarantineModeration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [AdminAccessLog] (ADR-0059 decision 8; A2 spec §9.3): what a member's data export lists of the
 * administrator access recorded about them, decided **when the export is built** and written
 * nowhere. Each audit row about the member's account or their media goes through
 * [Disclosure.shows], a function of the row alone, so a later event (a block, a report) can never
 * change what an earlier export said, and nothing disappears from one export to the next.
 *
 * The export shows the fixed word [Disclosure.shows] gives and **never a justification**
 * (`justification = null`): a moderator's note, or a `SAFETY`/`LEGAL` lookup's, is written for the
 * operator's record and may name a report or a case.
 *
 * **Not** `@ConditionalOnAdminConsole` (ADR-0055 ruling D): the audit log is persistence and
 * outlives the console being switched off, so with the console off an export still says the log
 * is kept, and lists what it holds.
 *
 * `@Transactional(readOnly = true)`: the export build holds no transaction (`PrepareExports`), so
 * this opens its own, and [QuarantineModeration.ownedBy], which is `SUPPORTS`, joins it.
 */
@Service
internal class AdminAccessDisclosure(
    private val rows: AdminAccessRows,
    private val media: QuarantineModeration,
) : AdminAccessLog {
    @Transactional(readOnly = true)
    override fun accessesTo(userId: UUID): List<AdminAccess> =
        rows.about(userId, media.ownedBy(userId)).mapNotNull { row ->
            val action = row.action
            val outcome = row.outcome
            if (action == null || outcome == null) {
                null
            } else {
                Disclosure
                    .shows(action, outcome, row.targetType, row.purpose, row.reportDriven)
                    ?.let { AdminAccess(row.at, it, justification = null) }
            }
        }
}
