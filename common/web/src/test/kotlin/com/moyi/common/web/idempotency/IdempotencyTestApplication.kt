package com.moyi.common.web.idempotency

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * A Spring context just large enough to put a real `POST` through the real
 * filter chain for this feature: `common:core`'s injected `Clock` and
 * `IdGenerator`, `common:web`'s error handler, and — via
 * `@Import(IdempotencyConfiguration::class)`, not `@Component`-scanned —
 * [IdempotencyRequestCachingFilter] and [IdempotencyInterceptor] against a
 * real Postgres.
 *
 * This file's own four `@Bean` methods were the original of what became
 * [IdempotencyConfiguration] (fix round 1, I7): this class's own KDoc used
 * to say "this is exactly the wiring the real application will need to
 * repeat once `POST /entries` (task 7) becomes the first live user" — which
 * is precisely what happened, twice (`app.MoyiApplication` and
 * `gratitude.infra.GratitudeTestApplication` each hand-copied it), with
 * nothing to catch a fourth copy drifting from the other three. `@Import`
 * is what closes that: this context now depends on the same bean
 * definitions the real application does, rather than a copy of them that
 * happens to agree today.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common"])
@ConfigurationPropertiesScan("com.moyi.common")
@Import(IdempotencyConfiguration::class)
class IdempotencyTestApplication {
    @Bean
    fun idempotencyProbeController(): IdempotencyProbeController = IdempotencyProbeController()
}
