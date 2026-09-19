package com.moyi.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
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
 */
@SpringBootApplication(scanBasePackages = ["com.moyi"])
@ConfigurationPropertiesScan("com.moyi")
@EntityScan("com.moyi")
@EnableJpaRepositories("com.moyi")
class MoyiApplication

// Spring's own idiomatic Kotlin bootstrap — the spread operator here is
// unavoidable (Array<String> into a vararg) and this is the only call site.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<MoyiApplication>(*args)
}
