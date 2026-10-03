package com.moyi.common.web.idempotency

import com.moyi.common.core.IdGenerator
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

/**
 * `@Idempotent`'s beans (doc 06 §1), wired once here — `@Import` this
 * rather than restating the `@Bean` methods below.
 *
 * **One bean it needs and does not define: a [RequestFingerprint]** (ruling
 * P8). `common:security` provides it (`HmacRequestFingerprint`, keyed by the
 * personal-data secret) — this module cannot, because it cannot depend on
 * `common:security`. A context that imports this without one fails at
 * startup naming that type, rather than quietly fingerprinting with a plain
 * hash.
 *
 * Fix round 1, I7: three contexts (`IdempotencyTestApplication`, `app.MoyiApplication`,
 * `gratitude.infra.GratitudeTestApplication`) had each hand-copied this exact
 * wiring, and nothing failed loudly when one of them drifted — [IdempotencyInterceptor.preHandle]'s
 * own `check(request is ReplayableHttpServletRequest)` catches a missing
 * *filter* loudly, but a missing *interceptor* is silent: `@Idempotent`
 * simply does nothing, which is how this was found — a replay test quietly
 * running the handler twice rather than replaying the first response. A
 * fourth context copying the same four methods by hand was only ever going
 * to repeat that discovery.
 *
 * **Correction (fix round 2, N4): forgetting `@Import(IdempotencyConfiguration::class)`
 * is still a silent no-op, not a compile-time failure.** Nothing requires
 * these beans to exist — a context that omits the import simply has no
 * `IdempotencyInterceptor` registered, and `@Idempotent` on a handler in it
 * does exactly what it did before this class existed: nothing, quietly. An
 * earlier version of this KDoc claimed otherwise; it was wrong. What this
 * class actually buys is that the wiring exists in exactly one place, so
 * three copies cannot drift from each other into three different bugs —
 * not that a missing import is caught. Catching that would need something
 * else (a test asserting the bean exists in every context that declares an
 * `@Idempotent` handler, say), which nothing here does yet.
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
 *
 * **Two costs of lite mode, both worth knowing before touching this file.**
 * First: without `@Configuration`, Spring does not CGLIB-proxy this class,
 * so a `@Bean` method calling *another `@Bean` method on this class
 * directly* would not resolve to the container's singleton — it would run
 * the plain Kotlin method body and construct a second, uncontained instance.
 * Invisible today because every method below takes its dependencies as
 * parameters rather than calling a sibling method; it stops being invisible
 * the day someone "simplifies" one to call another directly. Second: most
 * IDEs' Spring inspections (IntelliJ included) will flag a class full of
 * `@Bean` methods with no `@Configuration` and suggest adding it — doing so
 * reintroduces the exact `common:security` failure this class's own KDoc
 * describes above. Leave it bare.
 */
class IdempotencyConfiguration {
    @Bean
    fun idempotencyKeyStore(jdbc: JdbcTemplate): IdempotencyKeyStore = IdempotencyKeyStore(jdbc)

    @Bean
    fun idempotentExecution(
        store: IdempotencyKeyStore,
        clock: Clock,
        ids: IdGenerator,
    ): IdempotentExecution = IdempotentExecution(store, clock, ids)

    @Bean
    fun idempotencyInterceptor(fingerprint: RequestFingerprint): IdempotencyInterceptor = IdempotencyInterceptor(fingerprint)

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
     * and have its body read and fingerprinted for a request that was never
     * going to run. (The reservation itself is no longer at stake here: it
     * lives in the handler's transaction now — [IdempotentExecution].)
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
