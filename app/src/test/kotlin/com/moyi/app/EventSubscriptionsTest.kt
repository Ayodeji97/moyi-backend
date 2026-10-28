package com.moyi.app

import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import com.moyi.common.events.EventConsumer as Consumer

/**
 * What the running application's event consumers subscribe to, read from the
 * whole context (ADR-0034 decision 11, ADR-0046). `ArchitectureTest` warns
 * early, by reading a consumer's file for the streak events' literals; it
 * cannot see a type named through a constant written in another file. This
 * asks every consumer bean what it declares, however it came to declare it.
 *
 * Configured as `HealthCheckTest` and `JpaTransactionScopeTest` are, so it
 * shares their cached context and starts none of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventSubscriptionsTest(
    @Autowired private val context: ApplicationContext,
) : IntegrationTest() {
    @Test
    fun `no event consumer in the application subscribes to a streak event`() {
        val consumers = context.getBeansOfType(Consumer::class.java).values
        consumers.shouldNotBeEmpty()

        consumers
            .flatMap { consumer -> consumer.eventTypes.filter { it.startsWith("Streak") }.map { "${consumer.id}: $it" } }
            .shouldBeEmpty()
    }
}
