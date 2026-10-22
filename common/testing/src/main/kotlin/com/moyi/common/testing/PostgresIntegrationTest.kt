package com.moyi.common.testing

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Extend this for any test that needs a real Postgres rather than a mock —
 * doc 12: "prefer real-Postgres integration tests over mocks." One
 * container is started per JVM and shared across every subclass in the
 * same test run; Testcontainers' Ryuk reaper tears it down afterwards, so
 * there's no manual `stop()` to forget.
 *
 * **Started once, in the initialiser, and never stopped — deliberately not
 * `@Testcontainers` / `@Container`.** The JUnit extension stops a static
 * container after each test *class* and starts a new one (new port) for the
 * next, while Spring caches the application context across classes with
 * the first container's port inside it. Two classes sharing one context
 * therefore worked only as long as no two classes shared one; the day
 * `AddressNeverLoggedTest` joined `HealthCheckTest`'s context, the health
 * check waited thirty seconds on a container that no longer existed. The
 * singleton pattern is Testcontainers' own answer to this.
 *
 * **`max_connections=300`, not Postgres's 100.** Spring caches up to 32
 * distinct test contexts for the run, and evicts (and closes) the least
 * recently used one only past that; each cached
 * context's Hikari pool keeps its connections open (`minimumIdle` defaults to
 * the pool size, 4 in the modules' test `application.yml`). The gratitude
 * suite reached about 25 contexts, 100 connections, in slice C6c: one more
 * context made an unrelated class fail to load with "sorry, too many clients
 * already", in whatever class happened to start next. 300 is the headroom
 * for 32 contexts at 4 and the tests' own `DataSource` connections.
 * `fsync=off` is the flag `PostgreSQLContainer` starts with by default, kept:
 * `withCommand` replaces the whole command. `PostgresContainerTest` holds both.
 *
 * `UtilityClassWithPublicConstructor` is suppressed below: detekt's rule
 * doesn't distinguish "utility class" from "abstract base meant only for
 * subclassing" (the standard Testcontainers+Spring pattern) — an abstract
 * class can't be instantiated directly regardless.
 */
@Suppress("UtilityClassWithPublicConstructor")
abstract class PostgresIntegrationTest {
    companion object {
        @Suppress("unused")
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:18")
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=$MAX_CONNECTIONS")
                .also { it.start() }

        /** See the class KDoc: room for every context the run caches. */
        const val MAX_CONNECTIONS = 300
    }
}
