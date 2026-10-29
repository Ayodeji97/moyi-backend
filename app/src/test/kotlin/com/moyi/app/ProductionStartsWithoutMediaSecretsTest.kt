package com.moyi.app

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Production's `config/deploy.yml` either names the media stand-in or supplies
 * every value the real storage needs (ADR-0047), names image processing off in
 * the API and native only in the media worker (ADR-0048, ADR-0052), and runs
 * the worker as the owner ruled.
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
 * Parsed as YAML. **Each role's container sees the top-level `env` with its own
 * `servers.<role>.env` merged over it** (Kamal 2.12, `Role#env`), so every rule
 * about a container's environment is checked on each role's merged
 * environment. An accessory's `env` (Postgres's, Valkey's) reaches a container
 * that is not the application, and never counts (review of #106).
 */
internal class ProductionStartsWithoutMediaSecretsTest {
    @Suppress("UNCHECKED_CAST")
    private val deploy: Map<String, Any?> = Yaml().load<Map<String, Any?>>(File("../config/deploy.yml").readText())

    @Suppress("UNCHECKED_CAST")
    private val servers: Map<String, Map<String, Any?>> =
        (deploy["servers"] as Map<String, Any?>).mapValues { (_, role) -> (role as? Map<String, Any?>).orEmpty() }

    /** One role's container environment: what Kamal hands it. */
    private class Environment(
        val clear: Map<String, Any?>,
        /** Kamal's `env.secret` is a list of `NAME` or `NAME:SOURCE`; the application sees `NAME`. */
        val secret: Set<String>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun environmentOf(section: Map<String, Any?>?): Environment {
        val env = (section?.get("env") as? Map<String, Any?>).orEmpty()
        return Environment(
            clear = (env["clear"] as? Map<String, Any?>).orEmpty(),
            secret = (env["secret"] as? List<*>).orEmpty().map { it.toString().substringBefore(':') }.toSet(),
        )
    }

    private val top = environmentOf(deploy)

    /** Each role's merged environment, by role. */
    private val roles: Map<String, Environment> =
        servers.mapValues { (_, role) ->
            val own = environmentOf(role)
            Environment(top.clear + own.clear, top.secret + own.secret)
        }

    @Test
    fun `deploy-yml runs exactly two roles, the API and the media worker`() {
        servers.keys shouldContainExactlyInAnyOrder listOf("web", "media-worker")
    }

    @Test
    fun `deploy-yml selects the unavailable stand-in, or gives R2 its endpoint, its bucket and both halves of its token, in every role`() {
        roles.forEach { (role, env) ->
            val provider =
                env.clear["MOYI_MEDIA_STORAGE_PROVIDER"]
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
            val standIn = provider == "unavailable"
            // The endpoint names the Cloudflare account, which this public repository does not
            // (ADR-0052's switch-on), so it may be a secret as well as a plain value.
            val r2Complete =
                "MOYI_MEDIA_STORAGE_R2_BUCKET" in env.clear &&
                    ("MOYI_MEDIA_STORAGE_R2_ENDPOINT" in env.clear || "MOYI_MEDIA_STORAGE_R2_ENDPOINT" in env.secret) &&
                    listOf("MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID", "MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY").all { it in env.secret }

            val found = provider ?: "<default R2>"
            withClue("config/deploy.yml would start a $role whose media storage refuses to start (provider: $found)") {
                (standIn || ((provider == null || provider == "r2") && r2Complete)) shouldBe true
            }
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
    fun `deploy-yml names photo moderation none, or gives Rekognition both halves of its key, in every role`() {
        roles.forEach { (role, env) ->
            val provider =
                env.clear["MOYI_MEDIA_MODERATION_IMAGE_PROVIDER"]
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
            val rekognitionComplete =
                listOf(
                    "MOYI_MEDIA_MODERATION_REKOGNITION_ACCESS_KEY_ID",
                    "MOYI_MEDIA_MODERATION_REKOGNITION_SECRET_ACCESS_KEY",
                ).all { it in env.secret }
            val found = provider ?: "<default Rekognition>"

            withClue("config/deploy.yml would start a $role whose photo moderation refuses to start (provider: $found)") {
                (provider == "none" || ((provider == null || provider == "rekognition") && rekognitionComplete)) shouldBe true
            }
        }
    }

    /**
     * ADR-0051 (review of #113): photos go to Rekognition in eu-central-1 and
     * nowhere else. The application refuses another region and an endpoint
     * other than a loopback test stand-in at start; the deployed file does not
     * try either, as a plain value or as a secret, in any role.
     */
    @Test
    fun `deploy-yml names no Rekognition region or endpoint, so eu-central-1 and AWS's own endpoint are what run`() {
        val overrides = listOf("MOYI_MEDIA_MODERATION_REKOGNITION_REGION", "MOYI_MEDIA_MODERATION_REKOGNITION_ENDPOINT")
        roles.forEach { (role, env) ->
            val named = overrides.filter { it in env.clear || it in env.secret }

            withClue("config/deploy.yml overrides Rekognition's region or endpoint in $role: $named") {
                named shouldBe emptyList()
            }
        }
    }

    /**
     * ADR-0051: the stand-ins decide by colour (a photo that is not red passes)
     * and pass every voice note. A partner is shown what passes, so a stand-in
     * in production would show partners unmoderated media. None of the three
     * may name it, in any role; `none`, which fails closed, is what stands in there.
     */
    @Test
    fun `deploy-yml never names a moderation stand-in`() {
        roles.forEach { (role, env) ->
            val named =
                listOf(
                    "MOYI_MEDIA_MODERATION_IMAGE_PROVIDER",
                    "MOYI_MEDIA_MODERATION_KNOWNIMAGES_PROVIDER",
                    "MOYI_MEDIA_MODERATION_VOICE_PROVIDER",
                ).associateWith {
                    env.clear[it]
                        ?.toString()
                        ?.trim()
                        ?.lowercase()
                        ?.filter(Char::isLetter)
                }

            withClue("config/deploy.yml names a moderation stand-in in $role: $named") {
                named.values.none { it == "standin" } shouldBe true
            }
        }
    }

    /**
     * ADR-0048, ADR-0049, ADR-0052: the top-level `env`, which every role
     * inherits, names processing OFF, so a role that says nothing processes
     * nothing. Since ADR-0052 the image carries libvips and ffmpeg, so NATIVE,
     * the default, would no longer refuse to start where the line is missing:
     * it would decode uploads in the API. And it says so as the **string**
     * `off`: YAML 1.1, which Kamal reads and SnakeYAML here reads too, takes a
     * bare `off` for the boolean `false`. Spring Boot happens to bind "false"
     * to `OFF` (seen too), so a bare `off` would still start; the file is held
     * to the word it means rather than to that leniency.
     */
    @Test
    fun `deploy-yml turns photo and voice processing off for every role, as the string off`() {
        val processing = top.clear["MOYI_MEDIA_PROCESSING_PROVIDER"]
        val found = "${processing?.javaClass?.simpleName}: $processing"

        withClue("config/deploy.yml must set MOYI_MEDIA_PROCESSING_PROVIDER to the string \"off\" in its top-level env (found $found)") {
            (processing is String && processing.trim().lowercase() == "off") shouldBe true
        }
    }

    /**
     * ADR-0052: the media worker alone decodes uploads. Its role, and no other,
     * overrides processing with `native`; the API's role leaves it `off`.
     */
    @Test
    fun `only the media-worker role turns processing on, and it does`() {
        val processing =
            roles.mapValues { (_, env) ->
                env.clear["MOYI_MEDIA_PROCESSING_PROVIDER"]
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
            }

        processing shouldBe mapOf("web" to "off", "media-worker" to "native")
    }

    /**
     * ADR-0052 and the owner's Q5: the worker runs its own profile (no web
     * server, its consumers alone), behind no proxy, in a container of 1 GB (Q5 said 768 MB; ADR-0052 measured it too tight)
     * and 1.5 CPUs, read-only with a tmpfs, with no capability, with a health
     * check Kamal waits on, and its JVM capped.
     */
    @Test
    fun `the media worker runs with its profile, its limits and its health check`() {
        val worker = servers.getValue("media-worker")
        val options = (worker["options"] as? Map<*, *>).orEmpty().mapKeys { it.key.toString() }
        val env = roles.getValue("media-worker").clear

        env["SPRING_PROFILES_ACTIVE"] shouldBe "media-worker"
        env["JAVA_TOOL_OPTIONS"].toString().split(' ').any { it.startsWith("-Xmx") } shouldBe true
        worker["proxy"] shouldBe null
        options["memory"] shouldBe "1g"
        env["MALLOC_ARENA_MAX"].toString() shouldBe "2"
        options["cpus"] shouldBe 1.5
        options["read-only"] shouldBe true
        options["tmpfs"].toString().startsWith("/tmp:") shouldBe true
        options["cap-drop"] shouldBe "ALL"
        options["security-opt"] shouldBe "no-new-privileges"
        options["health-cmd"] shouldBe "test -f /tmp/moyi-ready"
    }

    /**
     * Review of #116 (ADR-0052 decision 1): the API's metaspace cap was copied
     * from an older image's calculator (160 MiB) while the API was measured using
     * 155 MB of it and still loading classes. A JVM at its metaspace cap throws
     * OutOfMemoryError, and `-XX:+ExitOnOutOfMemoryError` ends it. Every role's
     * JVM names a cap, and the API's is at least 256 MiB; and the API's heap,
     * metaspace, code cache, direct memory and 250 thread stacks fit its container.
     */
    @Test
    fun `every role caps metaspace, the API at 256 MiB or more, and the API's JVM fits its container`() {
        val options =
            roles.mapValues { (_, env) ->
                env.clear["JAVA_TOOL_OPTIONS"]
                    ?.toString()
                    .orEmpty()
                    .split(Regex("\\s+"))
            }

        fun size(
            role: String,
            flag: String,
        ): Long {
            val value = options.getValue(role).firstOrNull { it.startsWith(flag) }?.removePrefix(flag)
            withClue("$role's JAVA_TOOL_OPTIONS name no $flag") { (value != null) shouldBe true }
            return bytes(value!!)
        }

        roles.keys.forEach { size(it, "-XX:MaxMetaspaceSize=") }
        val metaspace = size("web", "-XX:MaxMetaspaceSize=")
        withClue("the API's MaxMetaspaceSize is ${metaspace / MIB} MiB") { (metaspace >= 256 * MIB) shouldBe true }

        val threads = 250 * size("web", "-Xss")
        val jvm =
            size("web", "-Xmx") + metaspace + size("web", "-XX:ReservedCodeCacheSize=") + size("web", "-XX:MaxDirectMemorySize=") + threads
        val container = bytes((servers.getValue("web")["options"] as Map<*, *>)["memory"].toString())
        withClue("the API's JVM budgets ${jvm / MIB} MiB in a ${container / MIB} MiB container") { (jvm <= container) shouldBe true }
    }

    /** `512m`, `1421240K`, `2g`, or a number of bytes, as the JVM and Docker read them. */
    private fun bytes(value: String): Long {
        val number = value.dropLastWhile { it.isLetter() }.toLong()
        return when (value.last().lowercaseChar()) {
            'k' -> number * KIB
            'm' -> number * MIB
            'g' -> number * GIB
            else -> number
        }
    }

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024L * KIB
        const val GIB = 1024L * MIB
    }
}
