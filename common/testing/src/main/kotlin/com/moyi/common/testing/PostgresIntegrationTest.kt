package com.moyi.common.testing

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Extend this for any test that needs a real Postgres rather than a mock —
 * doc 12: "prefer real-Postgres integration tests over mocks." One
 * container is started per JVM and shared across every subclass in the
 * same test run; Testcontainers' Ryuk reaper tears it down afterwards, so
 * there's no manual `stop()` to forget.
 *
 * `UtilityClassWithPublicConstructor` is suppressed below: detekt's rule
 * doesn't distinguish "utility class" from "abstract base meant only for
 * subclassing" (the standard Testcontainers+Spring pattern) — an abstract
 * class can't be instantiated directly regardless.
 */
@Suppress("UtilityClassWithPublicConstructor")
@Testcontainers
abstract class PostgresIntegrationTest {
    companion object {
        @Suppress("unused")
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:18")
    }
}
