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
    fun `R2 named with only some of its values refuses to start, naming exactly the ones missing`() {
        runner
            .withPropertyValues(
                "moyi.media.storage.provider=r2",
                "moyi.media.storage.r2.endpoint=https://example.eu.r2.cloudflarestorage.com",
                "moyi.media.storage.r2.bucket=moyi-media",
                "moyi.media.storage.r2.access-key-id=fake-access-key-id",
            ).run { context ->
                context.startupFailure.shouldBeInstanceOf<Throwable>()
                val messages = generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }
                // The property, and the environment variable production sets it by (config/deploy.yml).
                messages shouldContain "missing: moyi.media.storage.r2.secret-access-key (MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY)."
                messages shouldNotContain "fake-access-key-id"
            }
    }

    /**
     * Production's switch-on (ADR-0052): the endpoint arrives as a secret, and a
     * deploy that lost it must say so by name in its first lines, not as a list
     * of everything R2 could need. A value that is only blank counts as missing.
     */
    @Test
    fun `a blank endpoint is named as the one value missing, and no value that is set is`() {
        runner
            .withPropertyValues(
                "moyi.media.storage.r2.endpoint= ",
                "moyi.media.storage.r2.bucket=moyi-media",
                "moyi.media.storage.r2.access-key-id=fake-access-key-id",
                "moyi.media.storage.r2.secret-access-key=fake~secret~access~key",
            ).run { context ->
                context.startupFailure.shouldBeInstanceOf<Throwable>()
                val messages = generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }
                messages shouldContain "missing: moyi.media.storage.r2.endpoint (MOYI_MEDIA_STORAGE_R2_ENDPOINT)."
                messages shouldNotContain "r2.bucket ("
                messages shouldNotContain "fake~secret~access~key"
            }
    }

    /**
     * Review of #117: the endpoint names the Cloudflare account, so a value
     * that is not a URL is refused by the property's name alone; `URI.create`
     * would have put the value, account id and all, in the exception's message.
     */
    @Test
    fun `an endpoint that is not an http(s) URL is refused by name, and its value is never printed`() {
        listOf(
            "https://fake-account-id-0123.eu.r2.cloudflarestorage.com/a path with spaces",
            "fake-account-id-0123.eu.r2.cloudflarestorage.com",
            "ftp://fake-account-id-0123.eu.r2.cloudflarestorage.com",
        ).forEach { endpoint ->
            runner
                .withPropertyValues(
                    "moyi.media.storage.r2.endpoint=$endpoint",
                    "moyi.media.storage.r2.bucket=moyi-media",
                    "moyi.media.storage.r2.access-key-id=fake-access-key-id",
                    "moyi.media.storage.r2.secret-access-key=fake~secret~access~key",
                ).run { context ->
                    context.startupFailure.shouldBeInstanceOf<Throwable>()
                    val messages = generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }
                    messages shouldContain "moyi.media.storage.r2.endpoint (MOYI_MEDIA_STORAGE_R2_ENDPOINT) is not an http(s) URL"
                    messages shouldNotContain "fake-account-id-0123"
                }
        }
    }

    @Test
    fun `an endpoint with spaces around it is trimmed, and starts`() {
        runner
            .withPropertyValues(
                "moyi.media.storage.r2.endpoint=  https://example.eu.r2.cloudflarestorage.com  ",
                "moyi.media.storage.r2.bucket=moyi-media",
                "moyi.media.storage.r2.access-key-id=fake-access-key-id",
                "moyi.media.storage.r2.secret-access-key=fake~secret~access~key",
            ).run { context -> context.getBean(ObjectStore::class.java).shouldBeInstanceOf<R2ObjectStore>() }
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
