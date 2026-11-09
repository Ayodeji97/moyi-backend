package com.moyi.bond.api

import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * The bonds a person is in or was in, as their data export carries them (FR-009, ADR-0059): what
 * the app shows them of each bond, and their own settings in it. `export` asks; this module knows.
 *
 * **Only what is the person's, or what the app already shows them** (T-20). Their partner appears
 * by the display name the app prints, never by anything the partner keeps to themselves: not the
 * name the partner gave *them* (`nickname_for_other` is the partner's words), and **not whether
 * the partner left or blocked**, which the app never tells them either (ADR-0028's discreet exit,
 * doc 26 §2.1). The person's own block and their own withdrawal are theirs, and are in it.
 */
interface BondExports {
    /** Every bond [userId] holds or held a membership row in, newest first; empty for nobody. */
    fun bondsOf(userId: UUID): List<ExportedBond>

    /**
     * Every account that holds or held a membership row in [bondId]: whose exports a withdrawal in
     * that bond makes stale (ADR-0059 decision 12). **Runs no guard**, so it is one of
     * `ArchitectureTest`'s unguarded reads, for the work that has no caller, and must never be
     * reachable from a request. Empty for no such bond.
     */
    fun accountsInBond(bondId: UUID): Set<UUID>

    companion object {
        /**
         * The event a withdrawal publishes (FR-029a, ADR-0035), from the commit of the leave or block
         * that withdrew. Its references are [BOND_ID] and the withdrawing member's `memberId`.
         */
        const val ENTRIES_WITHDRAWN = "EntriesWithdrawn"
        const val BOND_ID = "bondId"
    }
}

/** One bond, as its member's export carries it. [toString] prints the id only: the name is the couple's words. */
@Suppress("LongParameterList") // A bond and the member's own place in it, each named where it is built.
class ExportedBond(
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
    /** `OWNER` or `MEMBER`. */
    val role: String,
    val joinedAt: Instant,
    val leftAt: Instant?,
    val reminderTimeLocal: LocalTime,
    val reminderTimezone: String,
    val quietHoursStart: LocalTime?,
    val quietHoursEnd: LocalTime?,
    /** The name this person gave their partner, theirs to keep. */
    val nicknameForOther: String?,
    /** The partner's display name as the app prints it (`Former member` for an account that is gone); null while there was never one. */
    val partnerDisplayName: String?,
    /** When this person blocked their partner here, if they did. */
    val blockedAt: Instant?,
    /** When this person withdrew their entries here (FR-029a), if they did. */
    val withdrewEntriesAt: Instant?,
) {
    override fun toString(): String = "ExportedBond(id=$id)"
}
