package com.moyi.common.events

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * How long a consumer's registration may wait at start-up for its lock on
 * `outbox_events` ([ConsumerRegistry]).
 *
 * **Why there is a bound at all.** The lock waits for every transaction that
 * has published and not yet committed, and while it waits every *new*
 * publisher queues behind it: Postgres grants locks in the order they were
 * asked for. One session left idle in a transaction would therefore hang the
 * instance that is starting, silently, and stop every entry submission and
 * close on the instances already running until that session ended. Bounded,
 * the start fails, says why, and the publishers move again.
 *
 * Ten seconds is far longer than any publishing transaction here should last
 * and short enough that a deploy notices. Zero is refused: to Postgres a
 * `lock_timeout` of zero means no limit, which is the fault this exists to remove.
 */
@ConfigurationProperties(prefix = "moyi.events.registration")
data class RegistrationProperties(
    val lockTimeout: Duration = Duration.ofSeconds(DEFAULT_LOCK_TIMEOUT_SECONDS),
) {
    init {
        require(lockTimeout.toMillis() >= 1) {
            "moyi.events.registration.lock-timeout must be at least one millisecond; zero would mean waiting for ever."
        }
    }

    private companion object {
        const val DEFAULT_LOCK_TIMEOUT_SECONDS = 10L
    }
}

/**
 * Declared here, and not left to the application's `@ConfigurationPropertiesScan`,
 * so that every context whose component scan reaches this package binds the
 * properties: the module test contexts scan `com.moyi.common` and have no such scan.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegistrationProperties::class)
internal class EventsConfiguration
