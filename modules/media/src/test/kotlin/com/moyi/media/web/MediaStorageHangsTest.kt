package com.moyi.media.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * A storage that takes the connection and never answers (review of #106).
 * With the SDK's defaults a completion's HEAD waited about 20 s, holding a
 * pooled connection, the key's lock and the asset's row lock while it did.
 * Now every storage call is bounded (3 s, two attempts of 1.5 s) and runs
 * before the transaction, so the answer is `503` within about four seconds
 * and nothing is left behind: no row changed, no Idempotency-Key recorded.
 *
 * The "storage" is a listening socket nobody accepts from: the kernel
 * completes the TCP handshake into its backlog, and no byte ever comes back.
 * Signing an upload URL needs no network, so the intent still succeeds.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
internal class MediaStorageHangsTest(
    @Autowired mvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val client = MediaClient(mvc, tokens)

    @Test
    fun `a completion against a storage that never answers is 503 within four seconds and records nothing`() {
        val ada = UUID.randomUUID()
        val mediaId = client.intent(ada, "fake photo bytes".toByteArray())["mediaId"].asString()

        val started = System.nanoTime()
        val call = Executors.newSingleThreadExecutor().submit(Callable { client.complete(ada, mediaId) })
        // While the storage keeps it waiting, the request holds nothing in the
        // database: no open transaction of its own, so no key lock and no row lock.
        var heldMeanwhile = 0
        while (!call.isDone) {
            heldMeanwhile = maxOf(heldMeanwhile, locksHeldByOthers())
            Thread.onSpinWait()
        }
        val response = call.get()
        val took = Duration.ofNanos(System.nanoTime() - started)
        heldMeanwhile shouldBe 0

        response.status shouldBe 503
        response.contentAsString shouldContain "\"code\":\"MEDIA_STORAGE_UNAVAILABLE\""
        took.toMillis() shouldBeLessThan BOUND.toMillis()
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "PENDING_UPLOAD"
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE user_id = ?", Int::class.java, ada) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?::uuid", Int::class.java, mediaId) shouldBe 0
    }

    /**
     * Advisory and row locks any other client session on this database holds
     * right now: the request's key lock, if it had a transaction open. Client
     * sessions only, on this database only: an autovacuum ANALYZE takes a
     * transaction id too, and once made this test fail on CI (review of #110).
     */
    private fun locksHeldByOthers(): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid " +
                "WHERE l.pid <> pg_backend_pid() AND l.locktype IN ('advisory', 'transactionid') " +
                "AND a.backend_type = 'client backend' AND a.datname = current_database()",
            Int::class.java,
        )!!

    companion object {
        /** The client's 3 s per call, and room for the request around it. */
        private val BOUND: Duration = Duration.ofMillis(4500)

        private val silent = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())

        @JvmStatic
        @DynamicPropertySource
        fun storage(registry: DynamicPropertyRegistry) {
            registry.add("moyi.media.storage.provider") { "R2" }
            registry.add("moyi.media.storage.r2.endpoint") { "http://127.0.0.1:${silent.localPort}" }
            registry.add("moyi.media.storage.r2.bucket") { "never-answers" }
            registry.add("moyi.media.storage.r2.access-key-id") { "fake-access-key-id" }
            registry.add("moyi.media.storage.r2.secret-access-key") { "fake-secret-access-key" }
        }

        @JvmStatic
        @AfterAll
        fun closeSocket() {
            silent.close()
        }

        private const val BACKLOG = 50
    }
}
