package com.moyi.common.web.idempotency

import com.moyi.common.core.IdGenerator
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

/**
 * `@Idempotent`'s three beans (doc 06 §1), wired once here — `@Import` this
 * rather than restating the four `@Bean` methods it replaces.
 *
 * Fix round 1, I7: three contexts (`IdempotencyTestApplication`, `app.MoyiApplication`,
 * `gratitude.infra.GratitudeTestApplication`) had each hand-copied this exact
 * wiring, and nothing failed loudly when one of them drifted — [IdempotencyInterceptor.preHandle]'s
 * own `check(request is ReplayableHttpServletRequest)` catches a missing
 * *filter* loudly, but a missing *interceptor* is silent: `@Idempotent`
 * simply does nothing, which is how this was found — a replay test quietly
 * running the handler twice rather than replaying the first response. A
 * fourth context copying the same four methods by hand was only ever going
 * to repeat that discovery. `@Import(IdempotencyConfiguration::class)` is
 * the one thing every context that needs this feature has to remember now,
 * and forgetting it is a compile-time-visible absence of a bean a
 * `@Idempotent` handler needs, not a silent no-op.
 *
 * Deliberately **not** `@Component`-scanned into every module that merely
 * depends on `common:web` — see [IdempotencyKeyStore]'s own KDoc: several
 * modules' own test contexts are built deliberately too small to hold a
 * `JdbcTemplate`, and this being auto-discovered would fail every one of
 * them at startup the moment `common:web` is on the classpath. `@Import` is
 * the explicit opt-in that keeps those contexts as small as they choose to
 * be.
 *
 * **Deliberately not `@Configuration` either — found the hard way.** The
 * first version of this class carried `@Configuration`, which is itself
 * `@Component`-meta-annotated, so `common:security`'s own test contexts
 * (which scan `com.moyi.common`, same as every other module's) picked this
 * class up by classpath scanning regardless of whether they `@Import` it —
 * `common:security:test` failed outright, `NoSuchBeanDefinitionException`
 * for `JdbcTemplate`, the exact failure mode this file exists to prevent.
 * Spring still processes a plain class's `@Bean` methods in "lite" mode
 * when it is named directly in an `@Import`, with no stereotype annotation
 * required — so leaving this class bare is what keeps it invisible to a
 * scan and visible only to a deliberate `@Import`.
 */
class IdempotencyConfiguration {
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

    /**
     * Fix round 1, I4: ordered explicitly, after any earlier request-scoped
     * gate an importing application registers — `common:security`'s
     * `RateLimitInterceptor` registers at order `0` for exactly this reason
     * (see that class's own KDoc). This module cannot name that class
     * directly (`common:security` depends on `common:web`, not the other
     * way round — [IdempotencyInterceptor.callerId]'s own KDoc gives the
     * same reason), so [ORDER] is a fixed, generously late value rather than
     * an arithmetic offset from a constant only the other module owns. A
     * caller a rate limiter would refuse must never reach this interceptor
     * and reserve an `Idempotency-Key` that a `429` — neither `ex != null`
     * nor `>= 500`, the two conditions [IdempotencyKeyStore.complete]
     * discards a reservation under — could then get stored against.
     */
    @Bean
    fun idempotencyWebMvcConfigurer(interceptor: IdempotencyInterceptor): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(interceptor).order(ORDER)
            }
        }

    companion object {
        const val ORDER = 1000
    }
}
