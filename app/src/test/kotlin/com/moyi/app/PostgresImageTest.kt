package com.moyi.app

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * One Postgres image in all four places that start one: production's
 * accessory, local compose, the test containers and the restore drill. It is
 * the build on Debian 13 (trixie).
 *
 * Why it matters: production's current `postgres:18` image reports
 * glibc 2.41. The untagged `pgvector/pgvector:0.8.7-pg18` is Debian 12, with
 * glibc 2.36. That database opened on it warns "collation version mismatch",
 * and affected text indexes would need rebuilding (ops/postgres/README.md, seen
 * 2026-11-04). The tests had already moved to that image while production had
 * not: the four drifting apart is the failure this test exists to catch.
 */
internal class PostgresImageTest {
    private fun deployImage(): String {
        val deploy: Map<String, Any?> = Yaml().load(File("../config/deploy.yml").readText())
        val postgres = (deploy["accessories"] as Map<*, *>)["postgres"] as Map<*, *>
        return postgres["image"] as String
    }

    private fun composeImage(): String {
        val compose: Map<String, Any?> = Yaml().load(File("../docker-compose.yml").readText())
        val postgres = (compose["services"] as Map<*, *>)["postgres"] as Map<*, *>
        return postgres["image"] as String
    }

    private fun restoreDrillImages(): Set<String> =
        Regex("""pgvector/pgvector:[\w.-]+""")
            .findAll(File("../ops/backup/restore-drill.sh").readText())
            .map { it.value }
            .toSet()

    @Test
    fun `production, compose, the tests and the restore drill start the same Postgres image`() {
        val tests = PostgresIntegrationTest.IMAGE.asCanonicalNameString()
        withClue("config/deploy.yml against the test containers' image") { deployImage() shouldBe tests }
        withClue("docker-compose.yml against the test containers' image") { composeImage() shouldBe tests }
        withClue("ops/backup/restore-drill.sh against the test containers' image") { restoreDrillImages() shouldContainOnly setOf(tests) }
    }

    @Test
    fun `the image is the trixie build, the Debian release production's database was made on`() {
        withClue("config/deploy.yml's Postgres image; see ops/postgres/README.md before changing its Debian release") {
            deployImage() shouldEndWith "-trixie"
        }
    }
}
