package com.moyi.export.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.export.api.DataExportJobs
import com.moyi.export.infra.ExportTestApplication
import com.moyi.export.infra.FakeStorage
import com.moyi.export.infra.People
import com.moyi.export.infra.RecordingMail
import com.moyi.identity.api.ExportedAccount
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The two events that make an archive one nobody may keep (ADR-0059 decisions 9 and 12): a
 * withdrawal in the bond (the words are gone for both, so no archive holding them is honoured),
 * and an account deletion racing a build that is making it READY.
 */
@SpringBootTest(classes = [ExportTestApplication::class])
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class ExportErasuresTest(
    @Autowired private val jobs: DataExportJobs,
    @Autowired private val requests: DataExportRequests,
    @Autowired private val people: People,
    @Autowired private val storage: FakeStorage,
    @Autowired private val mail: RecordingMail,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val dataSource: DataSource,
    @Autowired private val erasure: EraseDeletedAccountExports,
    @Autowired private val withdrawal: ExpireWithdrawnExports,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val cleo = UUID.randomUUID()
    private val bond = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        clear()
        clock.set(ExportTestApplication.START)
        listOf(ada, bea, cleo).forEach { people.accounts[it] = account(it) }
        people.members[bond] = setOf(ada, bea)
        // What earlier tests published is delivered first, so each test's dispatch is its own event's.
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), 100)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE data_exports, export_archive_deletions")
        people.clear()
        storage.clear()
        mail.sent.clear()
    }

    @Test
    fun `a withdrawal expires both members' READY exports and deletes their archives, and nobody else's`() {
        val adas = readyExportOf(ada)
        val beas = readyExportOf(bea)
        val cleos = readyExportOf(cleo)
        val cleosKey = keyOf(cleos)
        val keys = listOf(keyOf(adas), keyOf(beas))

        withdraw()

        statusOf(adas) shouldBe "EXPIRED"
        statusOf(beas) shouldBe "EXPIRED"
        storage.deleted.toSet() shouldBe keys.toSet()
        statusOf(cleos) shouldBe "READY"
        storage.archives.keys shouldBe setOf(cleosKey)
        requests.find(ada, adas).downloadUrl shouldBe null
    }

    @Test
    fun `a build that read the words before the withdrawal cannot make them READY, and the export is built again`() {
        val id = requests.request(ada).export.id
        var once = true
        people.duringBuild = {
            if (once) {
                once = false
                withdraw()
            }
        }

        jobs.prepareDue(5) shouldBe 0

        statusOf(id) shouldBe "PREPARING"
        storage.archives.keys.shouldBeEmpty()
        storage.deleted.size shouldBe 1
        mail.sent.shouldBeEmpty()
        jdbc.queryForObject("SELECT attempts FROM data_exports WHERE id = ?", Int::class.java, id) shouldBe 0

        jobs.prepareDue(5) shouldBe 1

        statusOf(id) shouldBe "READY"
    }

    @Test
    fun `an account deletion waiting on a build's READY deletes the archive that build stored`() {
        val id = requests.request(ada).export.id
        val key = "x/${UUID.randomUUID()}.zip"
        storage.archives[key] = "an archive".toByteArray()
        val builder = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val readied = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        // The build's READY, held uncommitted on the row.
        val build =
            CompletableFuture.runAsync {
                builder.executeWithoutResult {
                    JdbcTemplate(dataSource).update(
                        "UPDATE data_exports SET status = 'READY', storage_key = ?, size_bytes = 10, ready_at = ?, expires_at = ? " +
                            "WHERE id = ?",
                        key,
                        Timestamp.from(clock.instant()),
                        Timestamp.from(clock.instant().plus(Duration.ofHours(24))),
                        id,
                    )
                    readied.complete(Unit)
                    release.get(60, TimeUnit.SECONDS)
                }
            }
        readied.get(30, TimeUnit.SECONDS)
        val deletion = CompletableFuture.runAsync { deleteAccount(ada) }
        // The deletion's statement is blocked on the build's row lock: then let the build commit.
        awaitBlockedOn("DELETE FROM data_exports")
        release.complete(Unit)
        build.get(30, TimeUnit.SECONDS)
        deletion.get(30, TimeUnit.SECONDS)

        storage.archives.keys.shouldBeEmpty()
        storage.deleted shouldBe listOf(key)
        jdbc.queryForObject("SELECT count(*) FROM data_exports WHERE user_id = ?", Int::class.java, ada) shouldBe 0
    }

    @Test
    fun `a delivery whose transaction rolls back deletes no archive, so a READY export never points at nothing`() {
        val adas = readyExportOf(ada)
        val beas = readyExportOf(bea)
        val keys = setOf(keyOf(adas), keyOf(beas))

        transactions.executeWithoutResult { status ->
            erasure.handle(received("AccountDeleted", mapOf("userId" to ada)))
            withdrawal.handle(received("EntriesWithdrawn", mapOf("bondId" to bond)))
            status.setRollbackOnly()
        }

        statusOf(adas) shouldBe "READY"
        statusOf(beas) shouldBe "READY"
        storage.deleted.shouldBeEmpty()
        storage.archives.keys shouldBe keys
        // Nor is anything left owed: the next run's sweep must not delete what the READY rows still name.
        jobs.prepareDue(5) shouldBe 0
        storage.archives.keys shouldBe keys
    }

    @Test
    fun `an archive the storage could not delete when its delivery committed is deleted by the next run`() {
        val adas = readyExportOf(ada)
        val key = keyOf(adas)
        storage.failingDeletes = true

        withdraw()

        statusOf(adas) shouldBe "EXPIRED"
        storage.archives.keys shouldBe setOf(key)
        storage.failingDeletes = false

        jobs.prepareDue(5) shouldBe 0

        storage.archives.keys.shouldBeEmpty()
        storage.deleted shouldBe listOf(key)
    }

    private fun received(
        type: String,
        references: Map<String, UUID>,
    ) = ReceivedEvent(UUID.randomUUID(), "Test", UUID.randomUUID(), type, references, clock.instant())

    private fun awaitBlockedOn(statement: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE ?",
                    Int::class.java,
                    "$statement%",
                )
            if (waiting!! > 0) return
            Thread.onSpinWait()
        }
        error("nothing was blocked on '$statement'")
    }

    private fun readyExportOf(user: UUID): UUID {
        val id = requests.request(user).export.id
        jobs.prepareDue(5) shouldBe 1
        return id
    }

    private fun withdraw() {
        transactions.executeWithoutResult {
            publisher.publish(
                OutboxEvent("Bond", bond, "EntriesWithdrawn", mapOf("bondId" to bond, "memberId" to UUID.randomUUID()), clock.instant()),
            )
        }
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), 100).failed shouldBe 0
    }

    private fun deleteAccount(userId: UUID) {
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("User", userId, "AccountDeleted", mapOf("userId" to userId), clock.instant()))
        }
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), 100).failed shouldBe 0
    }

    private fun statusOf(id: UUID): String = jdbc.queryForObject("SELECT status FROM data_exports WHERE id = ?", String::class.java, id)!!

    private fun keyOf(id: UUID): String = jdbc.queryForObject("SELECT storage_key FROM data_exports WHERE id = ?", String::class.java, id)!!

    private fun account(id: UUID) =
        ExportedAccount(id, "$id@example.com", clock.instant(), "Someone", "en", "ACTIVE", clock.instant(), null, emptyList())
}
