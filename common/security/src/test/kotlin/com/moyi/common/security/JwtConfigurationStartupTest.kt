package com.moyi.common.security

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * The startup posture, through Boot's binding rather than by constructing
 * the properties by hand: an environment that forgot the key must not
 * boot, and the message must say which property.
 */
internal class JwtConfigurationStartupTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(KeysOnly::class.java)

    @Test
    fun `the default key source refuses to start with no PEMs, naming the property`() {
        runner.run { context ->
            context.startupFailure.shouldBeInstanceOf<Throwable>()
            generateSequence(context.startupFailure) { it.cause }.last().message!! shouldContain "moyi.security.jwt.private-key-pem"
        }
    }

    @Test
    fun `ephemeral has to be asked for by name, and then starts`() {
        runner
            .withPropertyValues("moyi.security.jwt.key-source=ephemeral")
            .run { context -> context.getBean(SigningKeys::class.java).kid.shouldBeInstanceOf<String>() }
    }

    /** Only the beans under test; the filter chain needs a servlet context this runner does not have. */
    @Configuration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(JwtProperties::class)
    class KeysOnly {
        @Bean
        fun signingKeys(properties: JwtProperties): SigningKeys = SigningKeys.from(properties)

        @Bean
        fun tokenRevocations(): TokenRevocations = TokenRevocations { _: UUID -> TokenRevocation(null) }
    }
}
