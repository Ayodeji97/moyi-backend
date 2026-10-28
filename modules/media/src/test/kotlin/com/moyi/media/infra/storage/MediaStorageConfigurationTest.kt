package com.moyi.media.infra.storage

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * The wiring, as email's and push's are held (ADR-0017 decision 3, ADR-0044
 * decision 10): R2 is the default, and an environment without its credentials
 * does not start; the stand-in that lets production start without media has to
 * be asked for by name (ADR-0047).
 */
internal class MediaStorageConfigurationTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(MediaStorageConfiguration::class.java)

    @Test
    fun `with no configuration at all, the application refuses to start and names the missing properties`() {
        runner.run { context ->
            context.startupFailure.shouldBeInstanceOf<Throwable>()
            val messages = generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }
            messages shouldContain "moyi.media.storage.r2.secret-access-key"
            messages shouldContain "UNAVAILABLE"
        }
    }

    @Test
    fun `R2 named with only some of its values refuses to start too`() {
        runner
            .withPropertyValues(
                "moyi.media.storage.provider=r2",
                "moyi.media.storage.r2.endpoint=https://example.eu.r2.cloudflarestorage.com",
                "moyi.media.storage.r2.bucket=moyi-media",
            ).run { context -> context.startupFailure.shouldBeInstanceOf<Throwable>() }
    }

    @Test
    fun `with a bucket and its token, R2 is wired, and the secret is never printed`() {
        runner
            .withPropertyValues(
                "moyi.media.storage.r2.endpoint=https://example.eu.r2.cloudflarestorage.com",
                "moyi.media.storage.r2.bucket=moyi-media",
                "moyi.media.storage.r2.access-key-id=fake-access-key-id",
                "moyi.media.storage.r2.secret-access-key=fake~secret~access~key",
            ).run { context ->
                context.getBean(ObjectStore::class.java).shouldBeInstanceOf<R2ObjectStore>()
                context.getBean(MediaStorageProperties::class.java).toString() shouldNotContain "fake~secret~access~key"
            }
    }

    @Test
    fun `UNAVAILABLE, asked for by name in lower case as deploy-yml writes it, starts with no credentials and stores nothing`() {
        runner.withPropertyValues("moyi.media.storage.provider=unavailable").run { context ->
            context.getBean(ObjectStore::class.java).shouldBeInstanceOf<UnavailableObjectStore>()
        }
    }
}
