package com.moyi.common.web.idempotency

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The `idempotency_keys` row (V11). See [IdempotencyKeyStore] for why this
 * is a plain class and not a JPA `@Entity`, and why the row
 * holds result *identity* ([IdempotencyRecord.resultId]), never the response
 * body: V11 has no column for the couple's words.
 */
data class IdempotencyRecord(
    val id: UUID,
    val userId: UUID,
    /** The request's HTTP method — stored, not part of the unique key (Ruling A, V11's header comment). */
    val method: String,
    /** The request's concrete path, never a route template: two bonds are two targets. */
    val path: String,
    val idempotencyKey: String,
    val requestHash: String,
    /** Null while the handler this row reserved is still running. */
    val responseStatus: Short?,
    /** What the first attempt produced, by identity; null until it completes, or when the handler named no result. */
    val resultId: UUID?,
    val resultKind: String?,
    /** Doc 06 §1's replay allowlist — see V11's comment on why only these two. */
    val responseEtag: String?,
    val responseLocation: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** What [IdempotencyKeyStore.complete] needs from the response the handler produced, bundled to keep that call to three parameters. */
data class CapturedResponse(
    val status: Int,
    val resultId: UUID?,
    val resultKind: String?,
    val etag: String?,
    val location: String?,
)

/**
 * Reads and writes [IdempotencyRecord] rows through plain [JdbcTemplate],
 * not a Spring Data JPA repository over a mapped `@Entity`.
 *
 * **This is a deliberate departure from `BondEntity`'s pattern**, and the
 * reason is `common:web` itself: it sits on every module's classpath, and
 * several modules' own test contexts build a Spring context just large
 * enough for that module — `BondTestApplication`'s `@EntityScan("com.moyi.bond")`,
 * `SecurityTestApplication`'s `scanBasePackages = ["com.moyi.common"]` with
 * **no database configured at all**. A `@Component`-scanned repository
 * needing a `JdbcTemplate`, or worse a `JpaRepository`, would fail every one
 * of those contexts at startup the moment this package is on the classpath.
 * `./gradlew build` was the check that proved it: even with these classes
 * left as plain, un-scanned beans, merely making `spring-boot-starter-jdbc`
 * an ordinary `implementation` dependency of `common:web` was enough to make
 * Spring Boot build a `DataSource` eagerly on every context that inherited
 * it, which broke `SecurityTestApplication`'s suite outright — see this
 * module's `build.gradle.kts` for the fix (the plain `spring-jdbc` artifact
 * instead of the starter, not `compileOnly`, as of review round 1's
 * optional follow-up). [IdempotencyKeyStore] and [IdempotencyInterceptor]
 * are therefore plain classes, wired by an explicit `@Bean` wherever the
 * feature is actually used (this module's own test context, and eventually
 * `app`), and `IdempotencyRecord` carries no JPA annotation for the same
 * reason: nothing here needs any module's `@EntityScan` to widen.
 *
 * **The reservation is one `INSERT`, not read-then-write.** [reserve] relies
 * on V11's `idempotency_keys_unique` constraint — `(userId, idempotencyKey)`
 * — to make a concurrent second reservation for the same key fail rather
 * than race — see [IdempotencyInterceptor]'s KDoc for why that ordering is
 * load-bearing.
 *
 * **The 24h window is enforced here, at read** (Ruling B, review round 1):
 * [find] filters `expires_at > :now`, the same shape `verification_tokens`,
 * `refresh_tokens` and `bond_invites` already use, so an expired row is
 * never handed back as something a retry can replay. No reaper exists yet —
 * that is slice C3 — so the physical row can still be sitting there under
 * the unique constraint when the *next* legitimate use of the same key
 * arrives, 24 hours or more later; [reserve] reclaims it in that one case,
 * because a client picking the same key twice, a day apart, is not a
 * conflict doc 06 §1 asks this to detect.
 */
class IdempotencyKeyStore(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Inserts [record] and returns `null` — this caller is first. On a
     * unique violation, looks up the current, non-expired row under the same
     * key: if one exists, it is returned for [IdempotencyInterceptor] to
     * decide between a replay, a 409 for a request still in flight, or a 422
     * for a reused key; if the only row under that key has expired, it is
     * reclaimed (see the class KDoc) and this reservation proceeds as if it
     * had been first, returning `null`.
     *
     * The unique violation itself is the signal this catch block acts on —
     * a fresh reservation losing the race to an existing row, not an error
     * being hidden — so there is nothing further to do with the caught
     * exception; `@Suppress` below is for that.
     */
    @Suppress("SwallowedException")
    fun reserve(record: IdempotencyRecord): IdempotencyRecord? =
        try {
            insert(record)
            null
        } catch (violation: DataIntegrityViolationException) {
            find(record.userId, record.idempotencyKey, record.createdAt) ?: reclaimExpired(record)
        }

    /**
     * Fills in the response the reserved handler produced — or, when
     * [captured]'s status is a server error, discards the reservation
     * instead (Important #1, review round 1): a transient 500 cached as "the
     * response" would be replayed to every retry for the rest of the 24h
     * window, which defeats the entire point of retrying. Also discarded
     * when [ex] is non-null: the handler threw, and whatever the advice
     * chain rendered from that is not "the response" either.
     */
    fun complete(
        id: UUID,
        captured: CapturedResponse,
        ex: Throwable?,
    ) {
        if (ex != null || captured.status >= SERVER_ERROR_THRESHOLD) {
            discard(id)
            return
        }
        jdbc.update(
            "UPDATE idempotency_keys SET response_status = ?, result_id = ?, result_kind = ?, " +
                "response_etag = ?, response_location = ? WHERE id = ?",
            captured.status,
            captured.resultId,
            captured.resultKind,
            captured.etag,
            captured.location,
            id,
        )
    }

    /** Removes a reservation outright — a discarded server-error attempt, or a wiring failure that cannot be completed. */
    fun discard(id: UUID) {
        jdbc.update("DELETE FROM idempotency_keys WHERE id = ?", id)
    }

    /**
     * A transaction-scoped, **nonblocking** advisory lock for this
     * `(user_id, key)`. `pg_try_advisory_xact_lock`, not the blocking variant:
     * a second request under a key whose first attempt is still running is
     * `409 IDEMPOTENCY_KEY_IN_FLIGHT`, a fact about timing the client can act
     * on, rather than a request thread parked for the duration of somebody
     * else's transaction.
     *
     * Two-integer form, in namespace `2`, so it cannot collide with
     * `identity`'s session lock (`pg_advisory_xact_lock(1, hashtext(user_id))`,
     * ADR-0021 §1a). Held until the *calling transaction* ends, so it is
     * meaningful only inside one; outside a transaction it is released
     * at once.
     */
    fun lockFor(
        userId: UUID,
        key: String,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(2, hashtext(?))",
            Boolean::class.java,
            "$userId:$key",
        ) == true

    /** Postgres `timestamptz` keeps microseconds; a nanosecond `Instant` would not round-trip equal. */
    private fun micros(instant: Instant): Timestamp = Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS))

    private fun insert(record: IdempotencyRecord) {
        jdbc.update(
            """
            INSERT INTO idempotency_keys
                (id, user_id, method, path, idempotency_key, request_hash, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.id,
            record.userId,
            record.method,
            record.path,
            record.idempotencyKey,
            record.requestHash,
            micros(record.createdAt),
            micros(record.expiresAt),
        )
    }

    /**
     * See the class KDoc: the row under this key has expired, and there is
     * no reaper yet to have cleared it.
     *
     * **Race-safe against a second caller reclaiming the same key**
     * (Important #N1, review round 2): the `DELETE` is scoped to
     * `expires_at <= :now` so it can only ever remove a row that is
     * genuinely expired — never a live reservation a concurrent caller just
     * inserted, which is what an unconditional `DELETE FROM ... WHERE
     * user_id = ? AND idempotency_key = ?` would otherwise have been free to
     * wipe out from under them. Two callers can still both see the row as
     * expired and both attempt to reclaim it: both `DELETE`s are then
     * idempotent (the second matches nothing), but only one `INSERT` wins —
     * the loser's re-collides with the winner's fresh row. That collision is
     * caught here and resolved by looping back through [reserve] rather than
     * surfacing a second, uncaught `DataIntegrityViolationException`: by
     * then the winner's row is live, not expired, so the retry finds it
     * through the ordinary `find` path and this caller becomes an ordinary
     * second reservation against it (409 in flight, from
     * [IdempotencyInterceptor]'s side) instead of a 500. The caught exception
     * itself is, again, the signal this branch acts on rather than an error
     * being hidden — same reasoning as [reserve]'s own `@Suppress`.
     */
    @Suppress("SwallowedException")
    private fun reclaimExpired(record: IdempotencyRecord): IdempotencyRecord? {
        jdbc.update(
            "DELETE FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ? AND expires_at <= ?",
            record.userId,
            record.idempotencyKey,
            micros(record.createdAt),
        )
        return try {
            insert(record)
            null
        } catch (violation: DataIntegrityViolationException) {
            reserve(record)
        }
    }

    private fun find(
        userId: UUID,
        idempotencyKey: String,
        now: Instant,
    ): IdempotencyRecord? =
        jdbc
            .query(
                """
                SELECT id, user_id, method, path, idempotency_key, request_hash,
                       response_status, result_id, result_kind, response_etag, response_location,
                       created_at, expires_at
                FROM idempotency_keys
                WHERE user_id = ? AND idempotency_key = ? AND expires_at > ?
                """.trimIndent(),
                ROW_MAPPER,
                userId,
                idempotencyKey,
                micros(now),
            ).firstOrNull()

    private companion object {
        const val SERVER_ERROR_THRESHOLD = 500

        val ROW_MAPPER =
            RowMapper { rs, _ ->
                IdempotencyRecord(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    method = rs.getString("method"),
                    path = rs.getString("path"),
                    idempotencyKey = rs.getString("idempotency_key"),
                    requestHash = rs.getString("request_hash"),
                    responseStatus = rs.getObject("response_status", Short::class.javaObjectType),
                    resultId = rs.getObject("result_id", UUID::class.java),
                    resultKind = rs.getString("result_kind"),
                    responseEtag = rs.getString("response_etag"),
                    responseLocation = rs.getString("response_location"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                )
            }
    }
}
