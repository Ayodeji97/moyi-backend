package com.moyi.scheduling.infra

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * The lock that keeps two instances from running one job at once (doc 05
 * §5.2) — during a rolling deploy there are two for a minute or so.
 *
 * `usingDbTime`: the lock's instants are the database's `now()`, not this
 * JVM's clock, so two instances whose clocks disagree still agree about who
 * holds it.
 *
 * `defaultLockAtMostFor` is the backstop for a job that names none: an
 * instance that dies holding a lock must not hold it for ever.
 *
 * **The lock is a courtesy, not the guarantee.** If it is lost — a pause
 * longer than `lockAtMostFor` — two runs overlap, and what keeps a day from
 * being closed twice is `CloseDay`'s row lock and `closedAt`, which
 * `gratitude`'s own tests hold.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
internal class SchedulingConfiguration {
    @Bean
    fun lockProvider(jdbc: JdbcTemplate): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration
                .builder()
                .withJdbcTemplate(jdbc)
                .usingDbTime()
                .build(),
        )
}

/**
 * The timer itself, switched off where nothing should fire on its own: a
 * test context that includes this module would otherwise close days on the
 * quarter-hour, against whatever the test had just built. Off, the jobs are
 * still beans and can be called.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["moyi.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
internal class SchedulingTimerConfiguration
