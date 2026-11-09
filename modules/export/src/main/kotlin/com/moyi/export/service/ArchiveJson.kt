package com.moyi.export.service

import com.moyi.bond.api.ExportedBond
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// The archive's JSON (ADR-0059 decision 7), field for field. Classes rather than maps so that the
// shape is one file to read; none of them prints its contents in toString, since every one of them
// holds somebody's words or personal data.

@Suppress("LongParameterList")
internal class AccountJson(
    val id: UUID,
    val email: String,
    val emailVerifiedAt: Instant?,
    val displayName: String,
    val locale: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
) {
    override fun toString(): String = "AccountJson(id=$id)"
}

internal class ConsentJson(
    val document: String,
    val version: String,
    val acceptedAt: Instant,
)

@Suppress("LongParameterList")
internal class BondJson(
    val id: UUID,
    val name: String,
    val type: String,
    val status: String,
    val createdAt: Instant,
    val archivedAt: Instant?,
    val anchorTimezone: String,
    val revealTimeLocal: LocalTime?,
    val strictMode: Boolean,
    val semanticSearchEnabled: Boolean,
    val partnerDisplayName: String?,
    val me: MembershipJson,
) {
    override fun toString(): String = "BondJson(id=$id)"

    companion object {
        fun of(bond: ExportedBond): BondJson =
            BondJson(
                id = bond.id,
                name = bond.name,
                type = bond.type,
                status = bond.status,
                createdAt = bond.createdAt,
                archivedAt = bond.archivedAt,
                anchorTimezone = bond.anchorTimezone,
                revealTimeLocal = bond.revealTimeLocal,
                strictMode = bond.strictMode,
                semanticSearchEnabled = bond.semanticSearchEnabled,
                partnerDisplayName = bond.partnerDisplayName,
                me =
                    MembershipJson(
                        role = bond.role,
                        joinedAt = bond.joinedAt,
                        leftAt = bond.leftAt,
                        reminderTimeLocal = bond.reminderTimeLocal,
                        reminderTimezone = bond.reminderTimezone,
                        quietHoursStart = bond.quietHoursStart,
                        quietHoursEnd = bond.quietHoursEnd,
                        nicknameForPartner = bond.nicknameForOther,
                        blockedAt = bond.blockedAt,
                        withdrewEntriesAt = bond.withdrewEntriesAt,
                    ),
            )
    }
}

@Suppress("LongParameterList")
internal class MembershipJson(
    val role: String,
    val joinedAt: Instant,
    val leftAt: Instant?,
    val reminderTimeLocal: LocalTime,
    val reminderTimezone: String,
    val quietHoursStart: LocalTime?,
    val quietHoursEnd: LocalTime?,
    val nicknameForPartner: String?,
    val blockedAt: Instant?,
    val withdrewEntriesAt: Instant?,
) {
    override fun toString(): String = "MembershipJson(role=$role)"
}

internal class DayJson(
    val date: LocalDate,
    val status: String,
    val entries: List<EntryJson>,
) {
    override fun toString(): String = "DayJson(date=$date)"
}

@Suppress("LongParameterList")
internal class EntryJson(
    val id: UUID,
    /** `ME` or `PARTNER`. */
    val author: String,
    val status: String,
    val text: String?,
    val createdAt: Instant,
    val intendedAt: Instant,
    val favourited: Boolean,
    val reaction: String?,
    /** The photo's path in the zip. */
    val image: String?,
    /** The voice note's path in the zip. */
    val voice: String?,
) {
    override fun toString(): String = "EntryJson(id=$id)"
}

@Suppress("LongParameterList")
internal class SupportMessageJson(
    val id: UUID,
    val subject: String?,
    val body: String?,
    val status: String,
    val createdAt: Instant,
    val closedAt: Instant?,
) {
    override fun toString(): String = "SupportMessageJson(id=$id)"
}

internal class AdminAccessJson(
    /** Whether an administrator-access log is kept to be read. False until the admin console keeps one (ADR-0059 decision 8). */
    val logKept: Boolean,
    val statement: String,
    val accesses: List<AdminAccessEntryJson>,
)

internal class AdminAccessEntryJson(
    val at: Instant,
    val action: String,
    val justification: String?,
) {
    override fun toString(): String = "AdminAccessEntryJson(at=$at, action=$action)"
}
