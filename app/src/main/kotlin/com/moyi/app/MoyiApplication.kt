package com.moyi.app

import com.moyi.common.web.idempotency.IdempotencyConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

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
 *
 * `@Import(IdempotencyConfiguration::class)` wires `Idempotency-Key` (doc 06
 * §1): `POST /bonds/{bondId}/entries` (`gratitude`, Task 7) is the first
 * `@Idempotent` endpoint, and this composition root is what makes that
 * annotation do anything at all in the running application. Fix round 1, I7
 * — `IdempotencyConfiguration`'s own KDoc has the fuller account of why this
 * is one `@Import` now rather than four hand-copied `@Bean` methods.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi"])
@ConfigurationPropertiesScan("com.moyi")
@EntityScan("com.moyi")
@EnableJpaRepositories("com.moyi")
@Import(IdempotencyConfiguration::class)
class MoyiApplication

// Spring's own idiomatic Kotlin bootstrap — the spread operator here is
// unavoidable (Array<String> into a vararg) and this is the only call site.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<MoyiApplication>(*args)
}
