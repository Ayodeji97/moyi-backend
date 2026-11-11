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
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The legal documents' versions and addresses come from the environment, never
 * from the shipped configuration (ADR-0056), for the reason the link bases do
 * (`LinkBaseUrlsHaveNoDefaultTest`): `LegalProperties` refuses to start without
 * them, and that guard is only worth anything while `application.yml` leaves
 * them unset. Every consent row records one of these versions; a default here
 * would be recorded as accepted by every deployment that forgot its own.
 *
 * And production names all six, so the guard does not stop a deploy.
 */
internal class LegalDocumentsHaveNoDefaultTest {
    @Test
    fun `application yml gives none of the six a value`() {
        resolved(profile = null).forEach { (name, value) ->
            withClue("$name must come from the environment, not application.yml") { value shouldBe null }
        }
    }

    @Test
    fun `the local profile points the addresses at localhost, which scripts smoke reads`() {
        resolved(profile = "local")
            .filterKeys { it.endsWith(".url") }
            .forEach { (name, value) -> withClue(name) { value.orEmpty() shouldStartWith "http://localhost:3000/" } }
    }

    @Test
    fun `the environment variables application yml names are the ones Boot binds`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                ENVIRONMENT_VARIABLES.keys.associateWith { "value-of-$it" },
            ),
        )
        val binder = Binder.get(environment)

        ENVIRONMENT_VARIABLES.forEach { (variable, property) ->
            withClue("$variable → $property") { binder.bind(property, String::class.java).orElse(null) shouldBe "value-of-$variable" }
        }
    }

    @Test
    fun `production's deploy configuration sets every one of them, each as a string`() {
        val deploy: Map<String, Any?> = Yaml().load(File("../config/deploy.yml").readText())
        val clear = (deploy["env"] as Map<*, *>)["clear"] as Map<*, *>
        ENVIRONMENT_VARIABLES.keys.forEach { variable ->
            // A string, because a bare 2026-11-08 is a YAML date, and what that becomes in the container is Kamal's guess.
            withClue(variable) { (clear[variable] as? String).orEmpty().isNotBlank() shouldBe true }
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
            listOf("terms", "privacy", "summary")
                .flatMap { document ->
                    listOf("version", "url").map { field ->
                        "MOYI_LEGAL_${document.uppercase()}_${field.uppercase()}" to "moyi.legal.$document.$field"
                    }
                }.toMap()
    }
}
