package com.moyi.ai.infra

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration

/**
 * A JDK HttpServer answering one path with scripted statuses and bodies, recording each request's
 * body and `Authorization` header. A real socket, so the client's own timeouts and
 * serialisation are what is tested.
 */
internal class StubHttpServer(
    path: String,
) : AutoCloseable {
    val requests = mutableListOf<String>()
    val authorizations = mutableListOf<String?>()
    val responses: ArrayDeque<Pair<Int, String>> = ArrayDeque()

    /** How long the server waits before answering: the slow provider. */
    var delay: Duration = Duration.ZERO

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(path) { exchange ->
                requests += exchange.requestBody.readAllBytes().decodeToString()
                authorizations += exchange.requestHeaders.getFirst("Authorization")
                Thread.sleep(delay.toMillis())
                val (status, body) = responses.removeFirst()
                val bytes = body.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
