package com.moyi.identity.infra.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
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

    private companion object {
        val RESET = URI("https://moyi.example/reset-password")
    }
}
