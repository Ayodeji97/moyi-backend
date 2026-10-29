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
     * ADR-0051: photo moderation defaults to Rekognition, which refuses to
     * start without its key pair, so the file names `none` (which moderates
     * nothing and shows no partner any photo) or gives Rekognition both halves
     * of its key, as secrets. The jar was started with this environment and no
     * AWS credentials when this landed (the pull request records the run).
     */
    @Test
    fun `deploy-yml names photo moderation none, or gives Rekognition both halves of its key`() {
        val provider = clear["MOYI_MEDIA_MODERATION_IMAGE_PROVIDER"]?.toString()?.trim()?.lowercase()
        val rekognitionComplete =
            listOf(
                "MOYI_MEDIA_MODERATION_REKOGNITION_ACCESS_KEY_ID",
                "MOYI_MEDIA_MODERATION_REKOGNITION_SECRET_ACCESS_KEY",
            ).all { it in secret }
        val found = provider ?: "<default Rekognition>"

        withClue("config/deploy.yml would start a release whose photo moderation refuses to start (provider: $found)") {
            (provider == "none" || ((provider == null || provider == "rekognition") && rekognitionComplete)) shouldBe true
        }
    }

    /**
     * ADR-0051 (review of #113): photos go to Rekognition in eu-central-1 and
     * nowhere else. The application refuses another region and an endpoint
     * other than a loopback test stand-in at start; the deployed file does not
     * try either, as a plain value or as a secret.
     */
    @Test
    fun `deploy-yml names no Rekognition region or endpoint, so eu-central-1 and AWS's own endpoint are what run`() {
        val overrides = listOf("MOYI_MEDIA_MODERATION_REKOGNITION_REGION", "MOYI_MEDIA_MODERATION_REKOGNITION_ENDPOINT")
        val named = overrides.filter { it in clear || it in secret }

        withClue("config/deploy.yml overrides Rekognition's region or endpoint: $named") {
            named shouldBe emptyList()
        }
    }

    /**
     * ADR-0051: the stand-ins decide by colour (a photo that is not red passes)
     * and pass every voice note. A partner is shown what passes, so a stand-in
     * in production would show partners unmoderated media. None of the three
     * may name it; `none`, which fails closed, is what stands in there.
     */
    @Test
    fun `deploy-yml never names a moderation stand-in`() {
        val named =
            listOf(
                "MOYI_MEDIA_MODERATION_IMAGE_PROVIDER",
                "MOYI_MEDIA_MODERATION_KNOWNIMAGES_PROVIDER",
                "MOYI_MEDIA_MODERATION_VOICE_PROVIDER",
            ).associateWith {
                clear[it]
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?.filter(Char::isLetter)
            }

        withClue("config/deploy.yml names a moderation stand-in: $named") {
            named.values.none { it == "standin" } shouldBe true
        }
    }

    /**
     * ADR-0048, ADR-0049: the application's image is the buildpack's and has
     * neither libvips nor ffmpeg, so this container must name processing OFF,
     * which turns off photos and voice notes alike, or NATIVE, the default,
     * refuses to start (seen: the jar, with production's environment and this
     * line left out, refused to start). And it says so as the **string** `off`: YAML 1.1,
     * which Kamal reads and SnakeYAML here reads too, takes a bare `off` for the
     * boolean `false`. Spring Boot happens to bind "false" to `OFF` (seen too),
     * so a bare `off` would still start; the file is held to the word it means
     * rather than to that leniency.
     */
    @Test
    fun `deploy-yml turns photo and voice processing off in the application's container, as the string off`() {
        val processing = clear["MOYI_MEDIA_PROCESSING_PROVIDER"]
        val found = "${processing?.javaClass?.simpleName}: $processing"

        withClue("config/deploy.yml must set MOYI_MEDIA_PROCESSING_PROVIDER to the string \"off\" (found $found)") {
            (processing is String && processing.trim().lowercase() == "off") shouldBe true
        }
    }
}
