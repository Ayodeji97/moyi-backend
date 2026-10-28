package com.moyi.app

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Production's `config/deploy.yml` either names the media stand-in or supplies
 * every value the real storage needs (ADR-0047), and names image processing
 * off in the application's container (ADR-0048).
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
 * Parsed as YAML, and read only from the application's own `env` at the top
 * of the file: the same line under an accessory's `env` (Postgres's, Valkey's)
 * reaches a container that is not the application, and must not count
 * (review of #106).
 */
internal class ProductionStartsWithoutMediaSecretsTest {
    @Suppress("UNCHECKED_CAST")
    private val env: Map<String, Any?> =
        (Yaml().load<Map<String, Any?>>(File("../config/deploy.yml").readText())["env"] as? Map<String, Any?>).orEmpty()

    @Suppress("UNCHECKED_CAST")
    private val clear: Map<String, Any?> = (env["clear"] as? Map<String, Any?>).orEmpty()

    /** Kamal's `env.secret` is a list of `NAME` or `NAME:SOURCE`; the application sees `NAME`. */
    private val secret: Set<String> =
        (env["secret"] as? List<*>).orEmpty().map { it.toString().substringBefore(':') }.toSet()

    @Test
    fun `deploy-yml selects the unavailable stand-in, or gives R2 its endpoint, its bucket and both halves of its token`() {
        val provider = clear["MOYI_MEDIA_STORAGE_PROVIDER"]?.toString()?.trim()?.lowercase()
        val standIn = provider == "unavailable"
        val r2Complete =
            listOf("MOYI_MEDIA_STORAGE_R2_ENDPOINT", "MOYI_MEDIA_STORAGE_R2_BUCKET").all { it in clear } &&
                listOf("MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID", "MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY").all { it in secret }

        withClue("config/deploy.yml would start a release whose media storage refuses to start (provider: ${provider ?: "<default R2>"})") {
            (standIn || ((provider == null || provider == "r2") && r2Complete)) shouldBe true
        }
    }

    /**
     * ADR-0048: the application's image is the buildpack's and has no libvips,
     * so this container must name processing OFF, or VIPS, the default, refuses
     * to start (seen: the jar, with production's environment and this line left
     * out, refused to start). And it says so as the **string** `off`: YAML 1.1,
     * which Kamal reads and SnakeYAML here reads too, takes a bare `off` for the
     * boolean `false`. Spring Boot happens to bind "false" to `OFF` (seen too),
     * so a bare `off` would still start; the file is held to the word it means
     * rather than to that leniency.
     */
    @Test
    fun `deploy-yml turns image processing off in the application's container, as the string off`() {
        val processing = clear["MOYI_MEDIA_PROCESSING_PROVIDER"]
        val found = "${processing?.javaClass?.simpleName}: $processing"

        withClue("config/deploy.yml must set MOYI_MEDIA_PROCESSING_PROVIDER to the string \"off\" (found $found)") {
            (processing is String && processing.trim().lowercase() == "off") shouldBe true
        }
    }
}
