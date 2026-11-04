package com.moyi.common.testing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The shared container is started with the settings [PostgresIntegrationTest] gives it, read
 * back from the server itself: a `withCommand` that lost a flag would start a container without
 * complaint.
 */
internal class PostgresContainerTest {
    @Test
    fun `the shared container allows the connections every cached context holds, and keeps fsync off`() {
        show("max_connections") shouldBe "${PostgresIntegrationTest.MAX_CONNECTIONS}"
        show("fsync") shouldBe "off"
    }

    @Test
    fun `the shared container can create the vector extension`() {
        psql("CREATE EXTENSION IF NOT EXISTS vector; SELECT '[1,0]'::vector <=> '[0,1]'::vector") shouldBe "1"
    }

    private fun show(setting: String): String = psql("SHOW $setting")

    private fun psql(sql: String): String {
        val postgres = PostgresIntegrationTest.postgres
        val result = postgres.execInContainer("psql", "-U", postgres.username, "-d", postgres.databaseName, "-tAc", sql)
        return result.stdout
            .lines()
            .last { it.isNotBlank() }
            .trim()
    }
}
