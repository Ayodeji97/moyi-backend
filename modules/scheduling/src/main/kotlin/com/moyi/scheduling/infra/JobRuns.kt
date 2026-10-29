package com.moyi.scheduling.infra

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `job_runs` (V35): when each job last finished a run without throwing. What
 * an alarm reads to tell a job that runs from one that only starts (ADR-0050;
 * ShedLock's own row is taken before the work, so it cannot tell).
 */
@Repository
internal class JobRuns(
    private val jdbc: JdbcTemplate,
) {
    fun succeeded(
        job: String,
        at: Instant,
    ) {
        jdbc.update(
            "INSERT INTO job_runs (name, last_success_at) VALUES (?, ?) " +
                "ON CONFLICT (name) DO UPDATE SET last_success_at = EXCLUDED.last_success_at",
            job,
            Timestamp.from(at.truncatedTo(ChronoUnit.MICROS)),
        )
    }

    fun lastSuccess(job: String): Instant? =
        jdbc
            .query("SELECT last_success_at FROM job_runs WHERE name = ?", { rs, _ -> rs.getTimestamp(1).toInstant() }, job)
            .firstOrNull()
}
