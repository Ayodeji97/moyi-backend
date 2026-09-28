package com.moyi.app

import com.moyi.common.core.IdGenerator
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.common.web.idempotency.IdempotencyKeyStore
import com.moyi.common.web.idempotency.IdempotencyRequestCachingFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

/**
 * The composition root. `app` is the only module that boots, and the only one
 * that knows the others exist (doc 25 §6).
 *
 * The three package declarations are all `com.moyi` rather than the default,
 * which is the package of *this* class. Spring Boot's auto-configuration
 * scans downwards from wherever `@SpringBootApplication` sits, so with the
 * default every `@Service`, `@Entity` and repository under `com.moyi.identity`
 * — that is, all of them — would be invisible. A modular monolith needs this
 * said once, explicitly, here.
 *
 * `@ConfigurationPropertiesScan` is the same story for `@ConfigurationProperties`
 * classes, which are found by scanning rather than by being `@Component`s —
 * and whose absence shows up as a missing-bean failure at startup, which is
 * at least loud.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi"])
@ConfigurationPropertiesScan("com.moyi")
@EntityScan("com.moyi")
@EnableJpaRepositories("com.moyi")
class MoyiApplication {
    /**
     * `Idempotency-Key` (doc 06 §1), wired by hand rather than picked up by
     * `@SpringBootApplication`'s own component scan — see
     * `IdempotencyKeyStore`'s own KDoc for why `IdempotencyKeyStore`,
     * [IdempotencyInterceptor] and [IdempotencyRequestCachingFilter] are
     * plain classes rather than `@Component`s: several modules' own test
     * contexts are deliberately too small to hold a `JdbcTemplate`, and a
     * scanned repository needing one would fail every one of them at
     * startup the moment `common:web` is on the classpath. `POST
     * /bonds/{bondId}/entries` (`gratitude`, Task 7) is the first endpoint
     * `@Idempotent` — this composition root is what makes that annotation
     * do anything at all in the running application, the same three beans
     * `IdempotencyTestApplication` wires for that feature's own test
     * context.
     */
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
}

// Spring's own idiomatic Kotlin bootstrap — the spread operator here is
// unavoidable (Array<String> into a vararg) and this is the only call site.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<MoyiApplication>(*args)
}
