package com.moyi.identity.infra.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI

internal class VerificationPropertiesTest {
    @Test
    fun `an absolute http or https link base is accepted`() {
        VerificationProperties(URI("https://moyi.example/verify"))
        VerificationProperties(URI("http://localhost:3000/verify"))
    }

    @Test
    fun `a relative path is refused at binding, and the message names the property`() {
        // A deployment whose environment says `verify` instead of a URL would
        // otherwise start cleanly and email links nobody can open.
        val failure = shouldThrow<IllegalArgumentException> { VerificationProperties(URI("verify")) }

        failure.message shouldContain "moyi.identity.verification.link-base-url"
    }

    @Test
    fun `a non-web scheme is refused`() {
        shouldThrow<IllegalArgumentException> { VerificationProperties(URI("mailto:hello@moyi.example")) }
        shouldThrow<IllegalArgumentException> { VerificationProperties(URI("moyi://verify")) }
    }
}
