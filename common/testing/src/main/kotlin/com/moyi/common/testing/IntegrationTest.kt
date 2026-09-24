package com.moyi.common.testing

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Everything the application needs to boot: Postgres and Redis. The base
 * class for a test that runs HTTP through a full module context, since
 * slice F — any request that reaches a controller may reach the limiter,
 * and a limiter with no Redis lets the request through after a timeout
 * (fail-open, ADR-0023), which is not the behaviour those tests are about.
 *
 * [PostgresIntegrationTest] remains for a test that wants Postgres alone —
 * a persistence test, or a test of what happens when Redis is *not* there.
 * Started in the initialiser for the reason given there.
 */
@Suppress("UtilityClassWithPublicConstructor")
abstract class IntegrationTest : PostgresIntegrationTest() {
    companion object {
        @Suppress("unused")
        @ServiceConnection(name = "redis")
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(RedisIntegrationTest.REDIS_IMAGE))
                .withExposedPorts(RedisIntegrationTest.REDIS_PORT)
                .also { it.start() }
    }
}
