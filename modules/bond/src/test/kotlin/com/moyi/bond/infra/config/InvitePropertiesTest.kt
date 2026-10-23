package com.moyi.bond.infra.config

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.net.URI

/**
 * The invite link's base, through Boot's binding: what a deployment that
 * forgot the variable meets. `application.yml` carries no value either
 * (ADR-0018, amended 2026-10-22; `LinkBaseUrlsHaveNoDefaultTest` in `app`).
 */
internal class InvitePropertiesTest {
    @Test
    fun `a context without the invite link base fails to start, naming the property`() {
        runner.run { context -> failureOf(context.startupFailure) shouldContain "moyi.bond.invite.link-base-url" }
    }

    @Test
    fun `a relative path fails to start, naming the property`() {
        runner
            .withPropertyValues("moyi.bond.invite.link-base-url=i")
            .run { context -> failureOf(context.startupFailure) shouldContain "moyi.bond.invite.link-base-url" }
    }

    @Test
    fun `given, it starts and binds it as given`() {
        runner
            .withPropertyValues("moyi.bond.invite.link-base-url=https://moyi.example/i")
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(InviteProperties::class.java).linkBaseUrl shouldBe URI("https://moyi.example/i")
            }
    }

    @Configuration
    @EnableConfigurationProperties(InviteProperties::class)
    internal class PropertiesOnly

    private companion object {
        val runner: ApplicationContextRunner = ApplicationContextRunner().withUserConfiguration(PropertiesOnly::class.java)

        /** Every message in the cause chain, so the assertion does not depend on which layer names the property. */
        fun failureOf(startupFailure: Throwable?): String =
            generateSequence(startupFailure.shouldNotBeNull()) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }
}
