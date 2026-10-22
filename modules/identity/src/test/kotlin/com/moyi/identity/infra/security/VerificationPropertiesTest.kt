package com.moyi.identity.infra.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.net.URI

internal class VerificationPropertiesTest {
    @Test
    fun `an absolute http or https link base is accepted`() {
        VerificationProperties(URI("https://moyi.example/verify"), RESET)
        VerificationProperties(URI("http://localhost:3000/verify"), RESET)
    }

    @Test
    fun `a relative path is refused at binding, and the message names the property`() {
        // A deployment whose environment says `verify` instead of a URL would
        // otherwise start cleanly and email links nobody can open.
        val failure = shouldThrow<IllegalArgumentException> { VerificationProperties(URI("verify"), RESET) }

        failure.message shouldContain "moyi.identity.verification.link-base-url"
    }

    @Test
    fun `the reset link is held to the same rule, and the message names its property`() {
        // The reset flow's first version pointed reset links at the verify page;
        // this class is where a wrong reset URL is now caught, at startup.
        val failure =
            shouldThrow<IllegalArgumentException> { VerificationProperties(URI("https://moyi.example/verify"), URI("reset")) }

        failure.message shouldContain "moyi.identity.verification.reset-link-base-url"
    }

    @Test
    fun `a non-web scheme is refused`() {
        shouldThrow<IllegalArgumentException> { VerificationProperties(URI("mailto:hello@moyi.example"), RESET) }
        shouldThrow<IllegalArgumentException> { VerificationProperties(URI("moyi://verify"), RESET) }
    }

    // Through Boot's binding rather than the constructor: what a deployment
    // that forgot the variable meets. application.yml carries no value either
    // (ADR-0018, amended 2026-10-22; LinkBaseUrlsHaveNoDefaultTest in `app`).

    @Test
    fun `a context without the verification link base fails to start, naming the property`() {
        runner
            .withPropertyValues("moyi.identity.verification.reset-link-base-url=https://moyi.example/reset-password")
            .run { context -> failureOf(context.startupFailure) shouldContain "moyi.identity.verification.link-base-url" }
    }

    @Test
    fun `a context without the reset link base fails to start, naming the property`() {
        runner
            .withPropertyValues("moyi.identity.verification.link-base-url=https://moyi.example/verify")
            .run { context -> failureOf(context.startupFailure) shouldContain "moyi.identity.verification.reset-link-base-url" }
    }

    @Test
    fun `with both given, it starts and binds them as given`() {
        runner
            .withPropertyValues(
                "moyi.identity.verification.link-base-url=https://moyi.example/verify",
                "moyi.identity.verification.reset-link-base-url=https://moyi.example/reset-password",
            ).run { context ->
                context.startupFailure shouldBe null
                context.getBean(VerificationProperties::class.java).linkBaseUrl shouldBe URI("https://moyi.example/verify")
            }
    }

    @Configuration
    @EnableConfigurationProperties(VerificationProperties::class)
    internal class PropertiesOnly

    private companion object {
        val RESET = URI("https://moyi.example/reset-password")

        val runner: ApplicationContextRunner = ApplicationContextRunner().withUserConfiguration(PropertiesOnly::class.java)

        /** Every message in the cause chain, so the assertion does not depend on which layer names the property. */
        fun failureOf(startupFailure: Throwable?): String =
            generateSequence(startupFailure.shouldNotBeNull()) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }
}
