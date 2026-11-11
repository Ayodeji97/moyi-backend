package com.moyi.admin.web

import com.moyi.admin.infra.AdminTestApplication
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * Switched on, the console refuses to start without its configuration (spec §4): a console
 * that admits nobody, or cannot read its secrets, is a configuration error, and a refusal to
 * boot says so before a support ticket would. The message names the property, never a value.
 */
internal class AdminConsoleStartupTest : IntegrationTest() {
    @Test
    fun `switched on with no allow-list, the application does not start`() {
        failure("moyi.admin.totp-key=$KEY") shouldContain "moyi.admin.allowed-networks"
    }

    @Test
    fun `switched on with a key that is not 32 bytes, the application does not start, and does not print it`() {
        val failure = failure("moyi.admin.allowed-networks=10.9.0.0/16", "moyi.admin.totp-key=$SHORT_KEY")
        failure shouldContain "moyi.admin.totp-key"
        failure shouldNotContain SHORT_KEY
    }

    private fun failure(vararg properties: String): String {
        val thrown =
            shouldThrow<Exception> {
                SpringApplicationBuilder(AdminTestApplication::class.java)
                    .properties(
                        "server.port=0",
                        "moyi.admin.enabled=true",
                        "spring.datasource.url=${postgres.jdbcUrl}",
                        "spring.datasource.username=${postgres.username}",
                        "spring.datasource.password=${postgres.password}",
                        "spring.data.redis.host=${redis.host}",
                        "spring.data.redis.port=${redis.firstMappedPort}",
                        *properties,
                    ).run()
                    .close()
            }
        return generateSequence<Throwable>(thrown) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    private companion object {
        const val KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        const val SHORT_KEY = "CQkJCQkJCQkJCQkJCQkJCQ=="
    }
}
