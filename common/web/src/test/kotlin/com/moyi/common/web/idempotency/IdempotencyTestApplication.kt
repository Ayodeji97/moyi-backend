package com.moyi.common.web.idempotency

import com.moyi.common.core.IdGenerator
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

/**
 * A Spring context just large enough to put a real `POST` through the real
 * filter chain for this feature: `common:core`'s injected `Clock` and
 * `IdGenerator`, `common:web`'s error handler, and — wired explicitly right
 * here, not `@Component`-scanned — [IdempotencyRequestCachingFilter] and
 * [IdempotencyInterceptor] against a real Postgres.
 *
 * The explicit `@Bean`s are the point, not an omission: see
 * [IdempotencyKeyStore]'s KDoc for why this feature must never be
 * `@Component`-scanned into every module that merely depends on
 * `common:web`. This is exactly the wiring the real application will need
 * to repeat once `POST /entries` (task 7) becomes the first live user.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common"])
@ConfigurationPropertiesScan("com.moyi.common")
class IdempotencyTestApplication {
    @Bean
    fun idempotencyKeyStore(jdbc: JdbcTemplate): IdempotencyKeyStore = IdempotencyKeyStore(jdbc)

    @Bean
    fun idempotencyInterceptor(
        store: IdempotencyKeyStore,
        clock: Clock,
        ids: IdGenerator,
    ): IdempotencyInterceptor = IdempotencyInterceptor(store, clock, ids)

    @Bean
    fun idempotencyRequestCachingFilter(): IdempotencyRequestCachingFilter = IdempotencyRequestCachingFilter()

    @Bean
    fun idempotencyWebMvcConfigurer(interceptor: IdempotencyInterceptor): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(interceptor)
            }
        }

    @Bean
    fun idempotencyProbeController(): IdempotencyProbeController = IdempotencyProbeController()
}
