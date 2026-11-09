package com.moyi.export.service

import com.moyi.bond.api.ExportedBond
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.export.api.AdminAccess
import com.moyi.export.api.AdminAccessLog
import com.moyi.export.api.DataExportJobs
import com.moyi.export.infra.ExportTestApplication
import com.moyi.export.infra.FakeStorage
import com.moyi.export.infra.People
import com.moyi.gratitude.api.ExportedDay
import com.moyi.gratitude.api.ExportedEntry
import com.moyi.identity.api.ExportedAccount
import com.moyi.identity.api.ExportedConsent
import com.moyi.media.api.ExportStorage
import com.moyi.support.api.ExportedTicket
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.sql.DataSource

/**
 * What an archive holds (FR-009, FR-094, T-10, T-20, ADR-0059 decision 7): every section, in the
 * zip, with every page of every bond and each media file the JSON names, and nothing of what it
 * holds in a log. Which entries a person may have is gratitude's (`ArchiveExportsTest`); this holds
 * that the writer carries exactly what each module answered.
 */
@SpringBootTest(classes = [ExportTestApplication::class])
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class ArchiveContentsTest(
    @Autowired private val jobs: DataExportJobs,
    @Autowired private val requests: DataExportRequests,
    @Autowired private val people: People,
    @Autowired private val storage: FakeStorage,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()
    private val ada = UUID.randomUUID()
    private val bond = UUID.randomUUID()
    private val photo = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        clear()
        clock.set(ExportTestApplication.START)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE data_exports")
        people.clear()
        storage.clear()
    }

    @Test
    fun `the archive holds every section and every page, the photo it names, and none of it reaches a log`(output: CapturedOutput) {
        written()

        requests.request(ada)
        jobs.prepareDue(5) shouldBe 1

        val files = unzip(storage.archives.values.single())
        files.keys shouldBe setOf("README.txt", "moyi-export.json", "media/$photo.webp")
        String(files.getValue("media/$photo.webp")) shouldBe "a photo"
        val archive = json.readTree(files.getValue("moyi-export.json"))
        archive["format"].asString() shouldBe "moyi-export/1"
        archive["account"]["email"].asString() shouldBe "ada@example.com"
        archive["consents"].toList().map { it["document"].asString() } shouldBe listOf("TERMS", "AGE_18")
        val only = archive["bonds"].single()
        only["bond"]["name"].asString() shouldBe "Us"
        only["bond"]["me"]["nicknameForPartner"].asString() shouldBe "B"
        only["bond"]["partnerDisplayName"].asString() shouldBe "Bea"
        // Five days, three pages of the fake's two: every page was followed.
        only["days"].toList().map { it["date"].asString() } shouldBe (5 downTo 1).map { "2026-10-0$it" }
        val fifth = only["days"][0]["entries"]
        fifth.toList().map { it["author"].asString() } shouldBe listOf("ME", "PARTNER")
        fifth[0]["text"].asString() shouldBe WORDS
        fifth[0]["image"].asString() shouldBe "media/$photo.webp"
        fifth[1]["favourited"].asBoolean() shouldBe true
        fifth[1]["reaction"].asString() shouldBe "HEART"
        only["days"][1]["entries"][1]["status"].asString() shouldBe "DELETED"
        only["days"][1]["entries"][1]["text"].isNull shouldBe true
        archive["supportMessages"].single()["body"].asString() shouldBe "help me"
        archive["adminAccess"]["logKept"].asBoolean() shouldBe false
        archive["adminAccess"]["accesses"].isEmpty shouldBe true
        output.all shouldNotContain WORDS
        output.all shouldNotContain "ada@example.com"
        output.all shouldNotContain "help me"
    }

    @Test
    fun `a photo the storage no longer holds is left out of the zip, and the rest is built`() {
        written()
        storage.media.remove(photo)

        requests.request(ada)

        jobs.prepareDue(5) shouldBe 1
        unzip(storage.archives.values.single()).keys shouldBe setOf("README.txt", "moyi-export.json")
    }

    @Test
    fun `an administrator-access log, once one is kept, is carried as it is`() {
        written()
        val log =
            object : AdminAccessLog {
                override fun accessesTo(userId: UUID) =
                    listOf(AdminAccess(Instant.parse("2026-10-20T12:00:00Z"), "VIEWED_ENTRY", "A report about this entry"))
            }
        val factory = DefaultListableBeanFactory().apply { registerSingleton("adminAccessLog", log) }
        val provider = factory.getBeanProvider(AdminAccessLog::class.java)
        val withLog = ArchiveWriter(people, people, people, people, storage as ExportStorage, provider, json)
        val zip = Files.createTempFile("archive", ".zip")

        withLog.write(UUID.randomUUID(), ada, clock.instant(), zip)

        val archive = json.readTree(unzip(Files.readAllBytes(zip)).getValue("moyi-export.json"))
        Files.delete(zip)
        archive["adminAccess"]["logKept"].asBoolean() shouldBe true
        archive["adminAccess"]["accesses"].single()["action"].asString() shouldBe "VIEWED_ENTRY"
    }

    @Test
    fun `an account deletion deletes every archive of the person and every export row, and a second delivery changes nothing`() {
        written()
        requests.request(ada)
        jobs.prepareDue(5)
        val key = storage.archives.keys.single()
        val beas = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO data_exports (id, user_id, status, requested_at) VALUES (?, ?, 'PREPARING', now())",
            UUID.randomUUID(),
            beas,
        )

        repeat(2) { deleteAccount(ada) }

        storage.archives.keys.shouldBeEmpty()
        storage.deleted shouldBe listOf(key)
        jdbc.queryForObject("SELECT count(*) FROM data_exports WHERE user_id = ?", Int::class.java, ada) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM data_exports WHERE user_id = ?", Int::class.java, beas) shouldBe 1
    }

    private fun deleteAccount(userId: UUID) {
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("User", userId, "AccountDeleted", mapOf("userId" to userId), clock.instant()))
        }
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), 100).failed shouldBe 0
    }

    private fun written() {
        people.accounts[ada] =
            ExportedAccount(
                id = ada,
                email = "ada@example.com",
                emailVerifiedAt = ExportTestApplication.START,
                displayName = "Ada",
                locale = "en",
                status = "ACTIVE",
                createdAt = ExportTestApplication.START,
                updatedAt = null,
                consents =
                    listOf(
                        ExportedConsent("TERMS", "2026-09-01", ExportTestApplication.START),
                        ExportedConsent("AGE_18", "1", ExportTestApplication.START),
                    ),
            )
        people.bonds[ada] = listOf(bondOf())
        people.days[ada to bond] =
            (1..5).map { day ->
                ExportedDay(
                    LocalDate.parse("2026-10-0$day"),
                    "REVEALED",
                    listOf(
                        entry(mine = true, text = if (day == 5) WORDS else "Ada $day", image = if (day == 5) photo else null),
                        if (day == 4) {
                            entry(mine = false, text = null, status = "DELETED")
                        } else {
                            entry(mine = false, text = "Bea $day", favourited = day == 5, reaction = "HEART".takeIf { day == 5 })
                        },
                    ),
                )
            }
        people.tickets[ada] =
            listOf(ExportedTicket(UUID.randomUUID(), "A question", "help me", "OPEN", ExportTestApplication.START, null))
        storage.media[photo] = ada to "a photo".toByteArray()
    }

    @Suppress("LongParameterList")
    private fun entry(
        mine: Boolean,
        text: String?,
        status: String = "REVEALED",
        favourited: Boolean = false,
        reaction: String? = null,
        image: UUID? = null,
    ) = ExportedEntry(
        id = UUID.randomUUID(),
        mine = mine,
        status = status,
        text = text,
        createdAt = ExportTestApplication.START,
        intendedAt = ExportTestApplication.START,
        favourited = favourited,
        reaction = reaction,
        imageId = image,
        voiceId = null,
    )

    private fun bondOf() =
        ExportedBond(
            id = bond,
            name = "Us",
            type = "COUPLE",
            status = "ACTIVE",
            createdAt = ExportTestApplication.START,
            archivedAt = null,
            anchorTimezone = "Africa/Lagos",
            revealTimeLocal = null,
            strictMode = false,
            semanticSearchEnabled = false,
            role = "OWNER",
            joinedAt = ExportTestApplication.START,
            leftAt = null,
            reminderTimeLocal = LocalTime.of(20, 0),
            reminderTimezone = "Africa/Lagos",
            quietHoursStart = null,
            quietHoursEnd = null,
            nicknameForOther = "B",
            partnerDisplayName = "Bea",
            blockedAt = null,
            withdrewEntriesAt = null,
        )

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readAllBytes() }
        }
        return files
    }

    private fun JsonNode.single(): JsonNode {
        size() shouldBe 1
        return get(0)
    }

    private companion object {
        const val WORDS = "words only Ada and Bea should ever see"
    }
}
