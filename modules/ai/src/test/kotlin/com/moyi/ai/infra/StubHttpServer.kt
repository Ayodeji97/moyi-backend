package com.moyi.ai.infra

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * A JDK HttpServer answering one path with scripted statuses and bodies, recording each request's
 * body and `Authorization` header. A real socket, so the client's own timeouts and
 * serialisation are what is tested.
 *
 * Requests are handled on a pool, so a stalled answer does not hide the request after it (a
 * retry): the server's default single dispatcher thread would queue it unseen.
 */
internal class StubHttpServer(
    path: String,
) : AutoCloseable {
    val requests: MutableList<String> = CopyOnWriteArrayList()
    val authorizations: MutableList<String?> = CopyOnWriteArrayList()
    val responses: ArrayDeque<Pair<Int, String>> = ArrayDeque()

    /** How long the server waits before answering: the slow provider. */
    var delay: Duration = Duration.ZERO

    /** When set, no request is ever answered until [close]: the provider that hangs. */
    @Volatile
    var stall: Boolean = false

    private val released = CountDownLatch(1)
    private val arrived = Semaphore(0)
    private val pool =
        Executors.newCachedThreadPool { task -> Thread(task, "stub-http").apply { isDaemon = true } }

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext(path) { exchange ->
                requests += exchange.requestBody.readAllBytes().decodeToString()
                authorizations += exchange.requestHeaders.getFirst("Authorization")
                arrived.release()
                if (stall) {
                    released.await()
                    exchange.close()
                    return@createContext
                }
                Thread.sleep(delay.toMillis())
                val (status, body) = synchronized(responses) { responses.removeFirst() }
                val bytes = body.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    /** Waits until a request has arrived: whether one did within [timeout]. */
    fun awaitRequest(timeout: Duration): Boolean = arrived.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)

    override fun close() {
        released.countDown()
        server.stop(0)
        pool.shutdownNow()
    }
}
