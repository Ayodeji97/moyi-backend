package com.moyi.app

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Production's `config/deploy.yml` either names the media stand-in or supplies
 * every value the real storage needs (ADR-0047).
 *
 * The lesson of #96's first deploy: a provider that defaults to the real
 * service and refuses to start without its secrets took production down when
 * a release added it and the deploy file did not follow. R2 is the default
 * here too, and fails closed, so the deploy file must say one or the other.
 * `MediaStorageConfigurationTest` holds the two halves of the wiring; this
 * holds the file that is actually deployed. The jar itself was started with
 * that environment and no media secrets when this landed (the pull request
 * that added it records the run).
 *
 * Read as text, not as YAML: the file is Kamal's, and the two lines that
 * matter are plain `NAME: value` under `env.clear` and `- NAME` under
 * `env.secret`.
 */
internal class ProductionStartsWithoutMediaSecretsTest {
    private val deploy = File("../config/deploy.yml").readLines().map { it.trim() }.filterNot { it.startsWith("#") }

    @Test
    fun `deploy-yml selects the unavailable stand-in, or gives R2 its endpoint, its bucket and both halves of its token`() {
        val provider =
            deploy
                .firstOrNull { it.startsWith("MOYI_MEDIA_STORAGE_PROVIDER:") }
                ?.substringAfter(':')
                ?.trim()
                ?.lowercase()
        val standIn = provider == "unavailable"
        val r2Complete =
            listOf("MOYI_MEDIA_STORAGE_R2_ENDPOINT", "MOYI_MEDIA_STORAGE_R2_BUCKET").all { name ->
                deploy.any { it.startsWith("$name:") }
            } &&
                listOf("MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID", "MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY").all { name ->
                    deploy.any { it == "- $name" || it.startsWith("- $name:") }
                }

        withClue("config/deploy.yml would start a release whose media storage refuses to start (provider: ${provider ?: "<default R2>"})") {
            (standIn || ((provider == null || provider == "r2") && r2Complete)) shouldBe true
        }
    }
}
