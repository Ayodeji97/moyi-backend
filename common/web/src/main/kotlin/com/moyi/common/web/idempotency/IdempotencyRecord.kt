package com.moyi.common.web.idempotency

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
    /** Null only between the reservation and its completion, inside the one transaction that wrote both. */
    val responseStatus: Short?,
    /** What the first attempt produced, by identity; null only while it is still being reserved (V11 pairs it with [resultKind]). */
    val resultId: UUID?,
    val resultKind: String?,
    /** Doc 06 §1's replay allowlist — see V11's comment on why only these two. */
    val responseEtag: String?,
    val responseLocation: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
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
 * See this module's `build.gradle.kts` for the dependency half of the same
 * reasoning (the plain `spring-jdbc` artifact, not the starter).
 * [IdempotencyKeyStore], [IdempotentExecution] and [IdempotencyInterceptor]
 * are therefore plain classes, wired by [IdempotencyConfiguration] wherever
 * the feature is actually used, and `IdempotencyRecord` carries no JPA
 * annotation for the same reason.
 *
 * **Not safe on its own: every method but [lockFor] assumes the caller holds
 * [lockFor]'s lock, in the transaction it is running in.** [IdempotentExecution]
 * is the one caller and does exactly that (spec §5.4: lock, then look up,
 * then reserve). Under that lock, [find]-then-[insert] cannot race a second
 * writer of the same key, which is what lets this be a plain lookup and
 * insert — the old insert-and-catch-the-unique-violation reservation cannot
 * run inside a transaction at all, because a failed `INSERT` aborts it.
 * `idempotency_keys_unique` stays as the backstop the lock makes unreachable.
 *
 * **The 24h window is enforced here, at read** (Ruling B, review round 1):
 * [find] filters `expires_at > :now`, the same shape `verification_tokens`,
 * `refresh_tokens` and `bond_invites` already use, so an expired row is
 * never handed back as something a retry can replay. No reaper exists yet —
 * that is slice C3 — so the physical row can still be sitting there under
 * the unique constraint when the next use of the same key arrives;
 * [deleteExpired] clears it, under the same lock (spec §5.4: "expired-key
 * replacement uses the same lock").
 */
class IdempotencyKeyStore(
    private val jdbc: JdbcTemplate,
) {
    /** The live (unexpired) row under this key, if there is one. */
    fun find(
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

    /** The reservation: a row with no status, visible to nobody until the transaction that wrote it commits. */
    fun insert(record: IdempotencyRecord) {
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
     * Clears an expired row under this key, so the [insert] that follows does
     * not meet it at the unique constraint. Scoped to `expires_at <= :now`,
     * so it can never remove a live row.
     */
    fun deleteExpired(
        userId: UUID,
        idempotencyKey: String,
        now: Instant,
    ) {
        jdbc.update(
            "DELETE FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ? AND expires_at <= ?",
            userId,
            idempotencyKey,
            micros(now),
        )
    }

    /**
     * Fills in what the reserved request produced — in the same transaction
     * as the reservation and the write, so a row is either absent or
     * complete to every other transaction. Only a success reaches here
     * ([IdempotentResult]'s own `init`); a refusal or a crash rolled the
     * reservation back instead of being stored.
     */
    fun complete(
        id: UUID,
        result: IdempotentResult<*>,
    ) {
        val updated =
            jdbc.update(
                "UPDATE idempotency_keys SET response_status = ?, result_id = ?, result_kind = ?, " +
                    "response_etag = ?, response_location = ? WHERE id = ?",
                result.status,
                result.resultId,
                result.kind.name,
                result.etag,
                result.location,
                id,
            )
        check(updated == 1) { "the reservation this transaction inserted is gone before it completed" }
    }

    /**
     * A transaction-scoped, **nonblocking** advisory lock for this
     * `(user_id, key)`. `pg_try_advisory_xact_lock`, not the blocking variant:
     * a second request under a key whose first attempt is still running is
     * `409 IDEMPOTENCY_KEY_IN_FLIGHT`, a fact about timing the client can act
     * on, rather than a request thread parked for the duration of somebody
     * else's transaction.
     *
     * Two-integer form, in namespace `3`. The namespaces taken so far, so
     * the next lock picks a free one: `1` is `identity`'s per-user session
     * lock (`IdentityRepositories`, ADR-0021 §1a), `2` is `bond`'s per-user
     * lock (`BondRepositories`), `3` is this. Sharing one would only ever
     * cost a spurious wait or `409`, never correctness, but there is no
     * reason to pay it. Held until the *calling transaction* ends, so it is
     * meaningful only inside one; outside a transaction it is released at
     * once.
     */
    fun lockFor(
        userId: UUID,
        key: String,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(3, hashtext(?))",
            Boolean::class.java,
            "$userId:$key",
        ) == true

    /** Postgres `timestamptz` keeps microseconds; a nanosecond `Instant` would not round-trip equal. */
    private fun micros(instant: Instant): Timestamp = Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS))

    private companion object {
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
