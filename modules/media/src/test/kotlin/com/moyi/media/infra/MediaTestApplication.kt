package com.moyi.media.infra

import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Instant

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
    fun testClock(): MutableClock = MutableClock(Instant.parse("2026-10-28T09:00:00Z"))
}
