package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.BondDayStatus
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Bond-days.
 *
 * On Spring Data's bare [Repository] marker rather than `JpaRepository`, for
 * the reason `BondRepository` gives (`modules/bond`): **what a repository
 * cannot do is part of its design.** Declaring the handful of methods this
 * module actually makes is what keeps every query here scoped to a bond and
 * a date, rather than inheriting a `findAll()` nothing here should ever call.
 */
internal interface BondDayRepository : Repository<BondDayEntity, UUID> {
    /**
     * Writes and flushes. No plain `save()` beside it: every row this
     * module creates goes through [insertIfAbsent] instead (a bond-day is
     * never freshly `INSERT`ed via an entity `save()`), so the only write
     * this interface needs afterwards is the update [BondDayStore.update]
     * makes. The flush is for the same reason `BondRepository.saveAndFlush`
     * gives: `@Version` increments at flush, and a caller that writes and
     * then re-reads to build a response would otherwise see the *old*
     * version.
     */
    fun saveAndFlush(day: BondDayEntity): BondDayEntity

    fun findById(id: UUID): BondDayEntity?

    fun findByBondIdAndDate(
        bondId: UUID,
        date: LocalDate,
    ): BondDayEntity?

    /**
     * Just the status, for [BondDayStore.statusOf] — the question
     * `DayAssignment.dateFor` asks on every offline-draft write (its own
     * KDoc: `{ date -> days.statusOf(bondId, date)?.isClosed == true }`), so
     * it stays a projection rather than loading the whole row.
     */
    @Query("SELECT d.status FROM BondDayEntity d WHERE d.bondId = :bondId AND d.date = :date")
    fun findStatusByBondIdAndDate(
        bondId: UUID,
        date: LocalDate,
    ): BondDayStatus?

    /**
     * The lazy open (doc 04 §3): a compare-and-set against
     * `bond_days_bond_date_key` rather than a check-then-insert (ADR-0027's
     * shape, B2's own invite-creation precedent) — two callers racing to
     * open the same bond's same date both run this, and only one of them
     * writes a row.
     *
     * `status`, `entry_count`, `revealed_at`/`closed_at` and `version` are
     * not parameters: every day this opens starts `OPEN`, empty, unrevealed
     * and unclosed, at `version` `0` — [com.moyi.gratitude.domain.BondDay.open]'s
     * own values, restated here rather than threaded through as arguments
     * nothing calling this ever varies.
     *
     * @return `1` if this call opened the day, `0` if another caller already
     *   had — either way [com.moyi.gratitude.infra.database.BondDayStore.openOrGet]
     *   reads the row back afterwards, so the caller never has to tell the two apart.
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, entry_count, created_at, version)
            VALUES (:id, :bondId, :date, 'OPEN', :anchorTimezone, 0, :createdAt, 0)
            ON CONFLICT (bond_id, date) DO NOTHING
            """,
    )
    fun insertIfAbsent(
        id: UUID,
        bondId: UUID,
        date: LocalDate,
        anchorTimezone: String,
        createdAt: Instant,
    ): Int
}

/**
 * Entries.
 *
 * No `findById` beyond what [Repository] would already require declaring —
 * nothing in this task reads a single entry by its own id; [findAllByBondDayId]
 * is what `EntryStore.findForDay` needs, and BR-2's uniqueness is the
 * database's job (`entries_one_per_member_per_day`), not a query here.
 */
internal interface EntryRepository : Repository<EntryEntity, UUID> {
    fun save(entry: EntryEntity): EntryEntity

    fun findAllByBondDayId(bondDayId: UUID): List<EntryEntity>
}
