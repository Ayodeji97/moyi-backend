package com.moyi.gratitude.infra.database

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
     * Holds the day's row for the rest of the transaction — fix round 1, I3:
     * `entry_count`/`status` is a read-modify-write (`BondDay.withEntry()`
     * computed in application code, then [BondDayStore.update] writes it
     * back), and two members submitting on the same day concurrently is not
     * a race, it is the ordinary case this product is for. Without this,
     * both transactions read the same `entry_count`, both compute `+1`, and
     * the second's flush trips `@Version` — `ObjectOptimisticLockingFailureException`,
     * uncaught, a `500` on the normal path. `bond.infra.database.BondRepositories.lockRow`
     * is the precedent (ADR-0028's lock rule): a row lock rather than an
     * advisory one, because the contended thing *is* a row, and `FOR UPDATE`
     * releases at commit with no key to agree on.
     */
    @Query(nativeQuery = true, value = "SELECT 1 FROM bond_days WHERE id = :id FOR UPDATE")
    fun lockRow(id: UUID): Int?

    /**
     * The lazy open (doc 04 §3): a compare-and-set against
     * `bond_days_bond_date_key` rather than a check-then-insert (ADR-0027's
     * shape, B2's own invite-creation precedent) — two callers racing to
     * open the same bond's same date both run this, and only one of them
     * writes a row.
     *
     * [status] is the one field of [com.moyi.gratitude.domain.BondDay.open]/
     * [com.moyi.gratitude.domain.BondDay.openSuspended]'s initial state that
     * varies by caller (`OPEN` for the ordinary open, `SUSPENDED` for a
     * creator writing before their partner joins, doc 04 §8.3a) — see
     * [BondDayStore.openOrGet]. `entry_count`, `revealed_at`/`closed_at` and
     * `version` do not: every day this opens starts empty, unrevealed and
     * unclosed, at `version` `0`, regardless of which status it opens under.
     *
     * `flushAutomatically = true` — the `BlockRepository.insertIfAbsent`
     * precedent (`modules/bond`): whatever this transaction already holds
     * pending must reach the database before this native statement runs.
     *
     * @return `1` if this call opened the day, `0` if another caller already
     *   had — either way [com.moyi.gratitude.infra.database.BondDayStore.openOrGet]
     *   reads the row back afterwards, so the caller never has to tell the two apart.
     *
     * `@Suppress("LongParameterList")`: eight parameters is this native
     * statement's own arity — one per column it actually varies, each a
     * distinct fact the caller already has in hand — the same justification
     * `Entry.submit` gives for its own suppression (detekt's
     * `LongParameterList` has no data-class-style exemption for a plain
     * interface function).
     */
    @Suppress("LongParameterList")
    @Modifying(flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at, version)
            VALUES (:id, :bondId, :date, :status, :anchorTimezone, :startsAt, :endsAt, 0, :createdAt, 0)
            ON CONFLICT (bond_id, date) DO NOTHING
            """,
    )
    fun insertIfAbsent(
        id: UUID,
        bondId: UUID,
        date: LocalDate,
        status: String,
        anchorTimezone: String,
        startsAt: Instant,
        endsAt: Instant,
        createdAt: Instant,
    ): Int
}

/**
 * Entries.
 *
 * [findAllByBondDayId] is what `EntryStore.findForDay` needs; [findById] is
 * the re-read an `Idempotency-Key` replay makes. BR-2's uniqueness is the
 * database's job (`entries_one_per_member_per_day`), not a query here.
 */
internal interface EntryRepository : Repository<EntryEntity, UUID> {
    /** Taken after the parent day lock; no content is returned before the caller's guard. */
    @Query(nativeQuery = true, value = "SELECT 1 FROM entries WHERE id = :id FOR UPDATE")
    fun lockRow(id: UUID): Int?

    /**
     * Writes and flushes — no plain `save()` beside it. `EntryStore.insert`
     * needs the flush, not for an `ETag` (no entry carries one) but so
     * `entries_one_per_member_per_day`'s violation is raised **here**,
     * inside this call, rather than deferred to whatever transaction the
     * caller eventually commits. A `@Transactional` method cannot catch its
     * own constraint violation and return successfully — see
     * `RegisterUser.kt` — and a caller that wraps `insert` in a `try/catch`
     * to turn BR-2 into a `409` needs the violation to surface while that
     * `catch` is still on the stack.
     */
    fun saveAndFlush(entry: EntryEntity): EntryEntity

    fun findAllByBondDayId(bondDayId: UUID): List<EntryEntity>

    /** One entry by id — the re-read behind an `Idempotency-Key` replay (`SubmitEntry`). */
    fun findById(id: UUID): EntryEntity?
}
