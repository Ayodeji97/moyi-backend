package com.moyi.media.infra

import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyConfiguration
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A Spring context just large enough for this module: `common` (security, the
 * limiter, the outbox, the Idempotency-Key) and `media`, on the shared
 * Testcontainers Postgres and Valkey and a MinIO of its own
 * ([MediaIntegrationTest]). `media` is a leaf, so nothing of any domain module
 * is here. [TokenRevocations] is identity's port and revokes nothing: a token
 * the test issuer minted is valid by construction.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common", "com.moyi.media"])
@ConfigurationPropertiesScan("com.moyi.common", "com.moyi.media")
@Import(IdempotencyConfiguration::class)
internal class MediaTestApplication {
    @Bean
    fun tokenRevocations(): TokenRevocations = TokenRevocations { TokenRevocation(invalidBefore = null) }

    @Bean
    @Primary
    fun testClock(): MutableClock = MutableClock(START)

    companion object {
        /**
         * The test clock's start: the top of the current real hour. Not a fixed
         * date: a signed GET is signed as of the clock's hour and valid 25 hours
         * (ADR-0048), and MinIO checks it against real time, so a fixed start
         * makes every test that fetches a URL fail once 25 hours have passed
         * (seen 2026-10-29 at 10:00 UTC, 25 hours after the old fixed start).
         */
        val START: Instant = Instant.now().truncatedTo(ChronoUnit.HOURS)
    }

    /** `media.process` times every photo (doc 11:49); the application gets its registry from the actuator. */
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
}
