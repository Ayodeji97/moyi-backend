package com.moyi.app

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The three link bases come from the environment, never from the shipped
 * configuration (ADR-0018, amended 2026-10-22).
 *
 * A verification or reset token travels in its link as `?token=`, and an
 * invite code as the last path segment. `application.yml` once gave all three
 * `https://moyi.com/…`, a domain the project does not own; every profile
 * inherited it, so the required-property guard in `VerificationProperties`
 * and `InviteProperties` never fired, and a deploy that forgot a variable
 * would have booted and emailed live tokens to a stranger's server. Those
 * classes' own tests prove a context without the value fails to start; this
 * one proves the shipped files leave it without one, which is the half the
 * defect was in.
 *
 * Reads the real files through Boot's config-data loading, with no database
 * and no beans: only what a profile resolves to.
 */
internal class LinkBaseUrlsHaveNoDefaultTest {
    @Test
    fun `application yml gives none of the three a value, so the default profile must take them from the environment`() {
        resolved(profile = null).forEach { (name, value) ->
            withClue("$name must come from the environment, not application.yml") { value shouldBe null }
        }
    }

    @Test
    fun `the local profile points them at localhost, which scripts smoke reads`() {
        resolved(profile = "local").forEach { (name, value) ->
            withClue(name) { value.orEmpty() shouldStartWith "http://localhost:3000/" }
        }
    }

    @Test
    fun `the test profile points them at the reserved test domain`() {
        resolved(profile = "test").forEach { (name, value) ->
            withClue(name) { value.orEmpty() shouldStartWith "https://moyi.test/" }
        }
    }

    @Test
    fun `the environment variables application yml names are the ones Boot binds`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                ENVIRONMENT_VARIABLES.keys.associateWith { "https://links.example/$it" },
            ),
        )
        val binder = Binder.get(environment)

        ENVIRONMENT_VARIABLES.forEach { (variable, property) ->
            withClue("$variable → $property") {
                binder.bind(property, String::class.java).orElse(null) shouldBe "https://links.example/$variable"
            }
        }
    }

    private fun resolved(profile: String?): Map<String, String?> {
        var values: Map<String, String?> = emptyMap()
        val runner =
            ApplicationContextRunner()
                .withInitializer(ConfigDataApplicationContextInitializer())
                .let { if (profile == null) it else it.withPropertyValues("spring.profiles.active=$profile") }
        runner.run { context ->
            values = ENVIRONMENT_VARIABLES.values.associateWith { context.environment.getProperty(it) }
        }
        values.keys shouldBe ENVIRONMENT_VARIABLES.values.toSet()
        return values
    }

    private companion object {
        /** As application.yml lists them: variable to property. */
        val ENVIRONMENT_VARIABLES =
            mapOf(
                "MOYI_IDENTITY_VERIFICATION_LINK_BASE_URL" to "moyi.identity.verification.link-base-url",
                "MOYI_IDENTITY_VERIFICATION_RESET_LINK_BASE_URL" to "moyi.identity.verification.reset-link-base-url",
                "MOYI_BOND_INVITE_LINK_BASE_URL" to "moyi.bond.invite.link-base-url",
            )
    }
}
