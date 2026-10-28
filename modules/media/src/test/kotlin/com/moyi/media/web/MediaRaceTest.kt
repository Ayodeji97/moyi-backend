package com.moyi.media.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Two changes to one asset at once (review of #106). The storage is asked
 * before the transaction, so what keeps them apart is the row lock taken
 * inside it and the status read again under that lock (ADR-0047 decision 2).
 *
 * Each test holds the asset's row from a transaction of its own, sends the two
 * requests, and **waits until Postgres shows both waiting on that row**
 * (`pg_locks`, two locks not granted), never for a time. Then it lets go, and
 * reads what the two answered.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
internal class MediaRaceTest(
    @Autowired mvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired private val dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val client = MediaClient(mvc, tokens)
    private val pool = Executors.newFixedThreadPool(2)
    private val ada = UUID.randomUUID()
    private val photo = "fake photo bytes for a race".toByteArray()

    @AfterEach
    fun clear() {
        pool.shutdownNow()
        jdbc.execute("TRUNCATE TABLE media_assets, idempotency_keys, outbox_deliveries, outbox_events")
    }

    @Test
    fun `two completions of one upload under different keys both answer 200, and one MediaUploaded is recorded`() {
        val mediaId = uploaded()
        val answers =
            HeldRow(dataSource, mediaId).use { held ->
                val first = pool.submit(Callable { client.complete(ada, mediaId) })
                val second = pool.submit(Callable { client.complete(ada, mediaId) })
                awaitWaiters(2)
                held.release()
                listOf(first, second).map { it.settled() }
            }

        answers.map { it.status } shouldContainExactlyInAnyOrder listOf(200, 200)
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MediaUploaded'", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "UPLOADED"
    }

    @Test
    fun `a deletion racing a completion is 204, and the completion is either done before it or a 404 after it`() {
        val mediaId = uploaded()
        val (completion, deletion) =
            HeldRow(dataSource, mediaId).use { held ->
                val complete = pool.submit(Callable { client.complete(ada, mediaId) })
                val delete = pool.submit(Callable { client.delete(ada, mediaId) })
                awaitWaiters(2)
                held.release()
                complete.settled() to delete.settled()
            }

        deletion.status shouldBe 204
        listOf(200, 404) shouldContain completion.status
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "DELETED"
        val events = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MediaUploaded'", Int::class.java)
        events shouldBe if (completion.status == 200) 1 else 0
    }

    private fun uploaded(): String {
        val intent = client.intent(ada, photo)
        client.put(intent, photo) shouldBe 200
        return intent["mediaId"].asString()
    }

    /** Until [count] locks are waiting to be granted: both requests are parked on the held row. */
    private fun awaitWaiters(count: Int) {
        val deadline = Instant.now().plus(WAIT_FOR_WAITERS)
        while (waiting() < count) {
            check(Instant.now().isBefore(deadline)) { "expected $count lock waiters on the held row, saw ${waiting()}" }
            Thread.onSpinWait()
        }
    }

    private fun waiting(): Int = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Int::class.java)!!

    private fun Future<MockHttpServletResponse>.settled(): MockHttpServletResponse = get(SETTLE_SECONDS, TimeUnit.SECONDS)

    private companion object {
        val WAIT_FOR_WAITERS: Duration = Duration.ofSeconds(15)
        const val SETTLE_SECONDS = 20L
    }
}
