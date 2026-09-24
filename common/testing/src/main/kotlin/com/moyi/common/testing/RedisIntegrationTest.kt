package com.moyi.common.testing

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * A real Redis for a test that needs one and no database — the rate limiter's
 * own tests. Same arrangement as [PostgresIntegrationTest], including the
 * reason it is started in the initialiser rather than by `@Container`: one
 * container per JVM, never restarted under a cached context, reaped by Ryuk.
 *
 * Valkey, because that is what `docker-compose.yml` runs and doc 05 accepts;
 * `@ServiceConnection(name = "redis")` because Boot matches the connection
 * details factory on that name and would not recognise the image on its own.
 */
@Suppress("UtilityClassWithPublicConstructor")
abstract class RedisIntegrationTest {
    companion object {
        const val REDIS_IMAGE = "valkey/valkey:8"
        const val REDIS_PORT = 6379

        @Suppress("unused")
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(
                DockerImageName.parse(REDIS_IMAGE),
            ).withExposedPorts(REDIS_PORT).also { it.start() }
    }
}
