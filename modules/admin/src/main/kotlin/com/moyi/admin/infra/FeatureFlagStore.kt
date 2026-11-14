package com.moyi.admin.infra

import com.moyi.common.core.FeatureFlag
import com.moyi.common.core.FeatureFlags
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * `feature_flags` (V67): the one place a flag is stored, and the application's only [FeatureFlags]
 * (ADR-0067 decision 11; `FeatureFlagsWiringTest` holds that). A persistence bean, present with the
 * console off (ADR-0055 ruling D), so that the jobs, the media worker and a console-off production
 * all read the same table.
 *
 * [isOn] reads the row every time: no cache, so a switch is seen by the next call on every instance
 * (decision 8). A flag with no row is its [FeatureFlag.defaultOn].
 *
 * `@Component`, not `@Repository`: exception translation would wrap this class's own exceptions
 * (ADR-0063 decision 3).
 */
@Component
internal class FeatureFlagStore(
    jdbcTemplate: JdbcTemplate,
) : FeatureFlags {
    private val jdbc = JdbcClient.create(jdbcTemplate)

    override fun isOn(flag: FeatureFlag): Boolean =
        jdbc
            .sql("SELECT enabled FROM feature_flags WHERE key = :key")
            .param("key", flag.name)
            .query(Boolean::class.javaObjectType)
            .optional()
            .orElse(flag.defaultOn)

    /** Every flag, in [FeatureFlag]'s order, with its row's facts when it has one. */
    fun states(): List<FlagState> {
        val rows =
            jdbc
                .sql("SELECT key, enabled, updated_at, updated_by FROM feature_flags")
                .query { rs, _ ->
                    rs.getString("key") to
                        Row(
                            rs.getBoolean("enabled"),
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getObject("updated_by", UUID::class.java),
                        )
                }.list()
                .toMap()
        return FeatureFlag.entries.map { flag ->
            val row = rows[flag.name]
            FlagState(flag, row?.enabled ?: flag.defaultOn, row?.at, row?.by)
        }
    }

    /**
     * The flag's value now, after taking the transaction-scoped advisory lock that serialises every
     * change of this flag (ADR-0067 decision 10): a second change waits here until the first commits,
     * and then reads what the first wrote, so a change's `from` is always the value it replaced.
     * Joins the caller's transaction or refuses to run, since a lock outside one would be released
     * at once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockAndRead(flag: FeatureFlag): Boolean {
        jdbc
            .sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:space, hashtext(CAST(:key AS text)))) AS held")
            .param("space", LOCK_SPACE)
            .param("key", flag.name)
            .query(Int::class.javaObjectType)
            .single()
        return isOn(flag)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun write(
        flag: FeatureFlag,
        enabled: Boolean,
        at: Instant,
        adminId: UUID,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO feature_flags (key, enabled, updated_at, updated_by) VALUES (:key, :enabled, :at, :by)
                ON CONFLICT (key) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at,
                    updated_by = EXCLUDED.updated_by
                """.trimIndent(),
            ).param("key", flag.name)
            .param("enabled", enabled)
            .param("at", Timestamp.from(at.truncatedTo(ChronoUnit.MICROS)))
            .param("by", adminId)
            .update()
    }

    private class Row(
        val enabled: Boolean,
        val at: Instant,
        val by: UUID,
    )

    internal companion object {
        /**
         * The advisory lock's first key, so that it cannot meet another lock of the application's
         * (identity's sessions lock is space 1). The migration's number, for no reason but to be findable.
         */
        const val LOCK_SPACE = 67
    }
}

/** One flag as the console shows it: on or off now, and when and by whom it was last switched (null: never). */
internal class FlagState(
    val flag: FeatureFlag,
    val on: Boolean,
    val changedAt: Instant?,
    val changedBy: UUID?,
) {
    val key: String get() = flag.name
    val pauses: String get() = flag.pauses
}
