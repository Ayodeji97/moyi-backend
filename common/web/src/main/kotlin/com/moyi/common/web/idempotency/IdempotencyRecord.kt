package com.moyi.common.web.idempotency

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** The `idempotency_keys` row (V11). See [IdempotencyKeyStore] for why this is a plain class and not a JPA `@Entity`. */
data class IdempotencyRecord(
    val id: UUID,
    val userId: UUID,
    val endpoint: String,
    val idempotencyKey: String,
    val requestHash: String,
    /** Null while the handler this row reserved is still running. */
    val responseStatus: Short?,
    val responseBody: String?,
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
 * `./gradlew build` was the check that proved it: even with these classes
 * left as plain, un-scanned beans, merely making `spring-boot-starter-jdbc`
 * an ordinary `implementation` dependency of `common:web` was enough to make
 * Spring Boot build a `DataSource` eagerly on every context that inherited
 * it, which broke `SecurityTestApplication`'s suite outright — see this
 * module's `build.gradle.kts` for why that dependency is `compileOnly`
 * instead. [IdempotencyKeyStore] and [IdempotencyInterceptor] are therefore
 * plain classes, wired by an explicit `@Bean` wherever the feature is
 * actually used (this module's own test context, and eventually `app`), and
 * `IdempotencyRecord` carries no JPA annotation for the same reason: nothing
 * here needs any module's `@EntityScan` to widen.
 *
 * **The reservation is one `INSERT`, not read-then-write.** [reserve] relies
 * on V11's `idempotency_keys_unique` constraint to make a concurrent second
 * reservation for the same `(userId, endpoint, idempotencyKey)` fail rather
 * than race — see [IdempotencyInterceptor]'s KDoc for why that ordering is
 * load-bearing.
 */
class IdempotencyKeyStore(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Inserts [record] and returns `null` — this caller is first. On a
     * unique violation, returns the row already there instead, for
     * [IdempotencyInterceptor] to decide between a replay, a 409 for a
     * request still in flight, or a 422 for a reused key.
     */
    fun reserve(record: IdempotencyRecord): IdempotencyRecord? =
        try {
            jdbc.update(
                """
                INSERT INTO idempotency_keys
                    (id, user_id, endpoint, idempotency_key, request_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                record.id,
                record.userId,
                record.endpoint,
                record.idempotencyKey,
                record.requestHash,
                Timestamp.from(record.createdAt),
                Timestamp.from(record.expiresAt),
            )
            null
        } catch (violation: DataIntegrityViolationException) {
            findByKey(record.userId, record.endpoint, record.idempotencyKey) ?: throw violation
        }

    /** Fills in the response the reserved handler produced, once it has run. */
    fun complete(
        id: UUID,
        responseStatus: Int,
        responseBody: String,
    ) {
        jdbc.update(
            "UPDATE idempotency_keys SET response_status = ?, response_body = ? WHERE id = ?",
            responseStatus,
            responseBody,
            id,
        )
    }

    private fun findByKey(
        userId: UUID,
        endpoint: String,
        idempotencyKey: String,
    ): IdempotencyRecord? =
        jdbc
            .query(
                """
                SELECT id, user_id, endpoint, idempotency_key, request_hash, response_status, response_body, created_at, expires_at
                FROM idempotency_keys
                WHERE user_id = ? AND endpoint = ? AND idempotency_key = ?
                """.trimIndent(),
                ROW_MAPPER,
                userId,
                endpoint,
                idempotencyKey,
            ).firstOrNull()

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs, _ ->
                IdempotencyRecord(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    endpoint = rs.getString("endpoint"),
                    idempotencyKey = rs.getString("idempotency_key"),
                    requestHash = rs.getString("request_hash"),
                    responseStatus = rs.getObject("response_status", Short::class.javaObjectType),
                    responseBody = rs.getString("response_body"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                )
            }
    }
}
