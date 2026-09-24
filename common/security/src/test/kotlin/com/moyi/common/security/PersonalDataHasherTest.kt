package com.moyi.common.security

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A keyed hash for the personal identifiers that have to be stored or used as
 * a key but must not be recoverable from what is stored: an address in
 * `consent_records.ip_hash`, an email in a Redis rate-limit key.
 */
internal class PersonalDataHasherTest {
    private val secret = "a".repeat(32)

    @Test
    fun `the same value hashes the same way, under the same secret`() {
        val hasher = PersonalDataHasher.from(HashingProperties(secret = secret))

        hasher.hash("203.0.113.7") shouldBe hasher.hash("203.0.113.7")
        hasher.hash("203.0.113.7") shouldNotBe hasher.hash("203.0.113.8")
    }

    @Test
    fun `a different secret gives a different hash, which is what makes an IPv4 space uncrackable from the table`() {
        // Plain SHA-256 of an address is reversible by hashing all 2^32 of them.
        // Only the secret stands between the stored column and the address.
        val one = PersonalDataHasher.from(HashingProperties(secret = secret))
        val other = PersonalDataHasher.from(HashingProperties(secret = "b".repeat(32)))

        one.hash("203.0.113.7") shouldNotBe other.hash("203.0.113.7")
    }

    @Test
    fun `the hash is base64url without padding, forty-three characters, safe in a column and a Redis key`() {
        PersonalDataHasher.from(HashingProperties(secret = secret)).hash("anything") shouldMatch Regex("[A-Za-z0-9_-]{43}")
    }

    @Test
    fun `the default source refuses to start without a secret, naming the property`() {
        ApplicationContextRunner().withUserConfiguration(HasherOnly::class.java).run { context ->
            context.startupFailure.shouldBeInstanceOf<Throwable>()
            generateSequence(context.startupFailure) { it.cause }.last().message!! shouldContain "moyi.security.hashing.secret"
        }
    }

    @Test
    fun `a short secret is refused too`() {
        ApplicationContextRunner()
            .withUserConfiguration(HasherOnly::class.java)
            .withPropertyValues("moyi.security.hashing.secret=too-short")
            .run { context ->
                context.startupFailure.shouldBeInstanceOf<Throwable>()
                generateSequence(context.startupFailure) { it.cause }.last().message!! shouldContain "32"
            }
    }

    @Test
    fun `ephemeral has to be asked for by name, and then starts`() {
        ApplicationContextRunner()
            .withUserConfiguration(HasherOnly::class.java)
            .withPropertyValues("moyi.security.hashing.secret-source=ephemeral")
            .run { context -> context.getBean(PersonalDataHasher::class.java).hash("x") shouldMatch Regex("[A-Za-z0-9_-]{43}") }
    }

    @Configuration
    @EnableConfigurationProperties(HashingProperties::class)
    class HasherOnly {
        @Bean
        fun hasher(properties: HashingProperties): PersonalDataHasher = PersonalDataHasher.from(properties)
    }
}
