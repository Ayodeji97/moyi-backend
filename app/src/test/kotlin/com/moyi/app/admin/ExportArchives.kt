package com.moyi.app.admin

import com.moyi.export.api.DataExportJobs
import com.moyi.media.api.ExportStorage
import com.moyi.media.api.ExportedMediaFile
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.random.Random

/**
 * The data export's storage in the app's admin tests (A2 spec §9.3, §12): media's [ExportStorage]
 * replaced, `@Primary`, by one that keeps each archive in memory, so that a test can build an
 * export through the real job, `PrepareExports`, and read the archive it stored. The test
 * profile's object store is unavailable, so media's own would fail every build. It copies no
 * photo or voice note into an archive (`copyOf` is null): what these tests read is `adminAccess`.
 *
 * Production wiring is untouched: this is a second bean, preferred only in the contexts that
 * import it.
 */
@TestConfiguration
class ExportArchives {
    @Bean
    @Primary
    fun inMemoryExportStorage(): ExportStorage = Store

    /** The archives, by key. Shared by every context that imports this, as keys are random. */
    object Store : ExportStorage {
        private val archives = ConcurrentHashMap<String, ByteArray>()

        override fun copyOf(
            viewer: UUID,
            mediaId: UUID,
        ): ExportedMediaFile? = null

        override fun storeArchive(file: Path): String {
            val key = "x/${UUID.randomUUID()}.zip"
            archives[key] = Files.readAllBytes(file)
            return key
        }

        override fun archiveUrl(
            key: String,
            signedAt: Instant,
            expiresIn: Duration,
        ): URI = URI.create("https://storage.test/$key")

        override fun deleteArchive(key: String) {
            archives.remove(key)
        }

        fun archive(key: String): ByteArray = checkNotNull(archives[key]) { "no archive stored under that key" }
    }

    companion object {
        /**
         * An export of [userId], built as the scheduler builds one: a `PREPARING` row, as the
         * request route writes it, then [DataExportJobs.prepareDue] **called with no transaction
         * open** (the build holds none, and a caller's transaction would hide a `MANDATORY` read).
         * It must come out `READY`; the answer is the archive's `moyi-export.json`, as stored.
         *
         * **This export and no other.** The database is shared by every test class, and others leave
         * exports waiting; the job takes the oldest first (`ORDER BY requested_at, id`). So the row is
         * requested a second before every export already waiting (or now, if none is), and the job is
         * run for **one**: it can only be this one. No production path is needed, or changed.
         */
        fun build(
            jobs: DataExportJobs,
            jdbc: JdbcTemplate,
            clock: Clock,
            userId: UUID,
        ): ByteArray {
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO data_exports (id, user_id, status, requested_at) " +
                    "SELECT ?, ?, 'PREPARING', least(?::timestamptz, (SELECT min(requested_at) - interval '1 second' " +
                    "FROM data_exports WHERE status = 'PREPARING'))",
                id,
                userId,
                Timestamp.from(clock.instant()),
            )
            jobs.prepareDue(1) shouldBe 1
            jdbc.queryForObject("SELECT status FROM data_exports WHERE id = ?", String::class.java, id) shouldBe "READY"
            val key = jdbc.queryForObject("SELECT storage_key FROM data_exports WHERE id = ?", String::class.java, id)!!
            return document(Store.archive(key))
        }

        /**
         * The `adminAccess` member of an export's document, **as its bytes**: from its name to the end of the
         * document, of which it is the last member (`ArchiveWriter`). Compared byte for byte, two of these say
         * the two exports disclose exactly the same thing, in the same words.
         */
        fun adminAccessBytes(document: ByteArray): ByteArray {
            val text = String(document, Charsets.UTF_8)
            val at = text.indexOf("\"adminAccess\"")
            check(at > 0) { "the document has no adminAccess" }
            return text.substring(at).toByteArray(Charsets.UTF_8)
        }

        /** A member registered through the API, as a person registers, from a documentation address picked at random. */
        fun register(
            mockMvc: MockMvc,
            jdbc: JdbcTemplate,
        ): UUID {
            val email = "export-${UUID.randomUUID().toString().take(8)}@example.com"
            val response =
                mockMvc
                    .post("/api/v1/auth/register") {
                        with { it.apply { remoteAddr = "198.51.100.${Random.nextInt(1, 255)}" } }
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            """{"email":"$email","password":"eleven~amber~lanterns~at~dusk","displayName":"Ada Test","locale":"en",""" +
                            """"acceptedTermsVersion":"2026-09-01","over18":true}"""
                    }.andReturn()
                    .response
            response.status shouldBe 201
            return jdbc.queryForObject("SELECT id FROM users WHERE email = ?::citext", UUID::class.java, email)!!
        }

        private fun document(zip: ByteArray): ByteArray {
            ZipInputStream(ByteArrayInputStream(zip)).use { entries ->
                generateSequence { entries.nextEntry }.forEach { entry ->
                    if (entry.name == DOCUMENT) return entries.readAllBytes()
                }
            }
            error("the archive has no $DOCUMENT")
        }

        private const val DOCUMENT = "moyi-export.json"
    }
}
