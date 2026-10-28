package com.moyi.media.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/** The calls the race and timeout tests make, as a phone makes them. */
internal class MediaClient(
    private val mvc: MockMvc,
    private val tokens: AccessTokenIssuer,
) {
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    fun intent(
        owner: UUID,
        bytes: ByteArray,
    ): JsonNode {
        val response =
            mvc
                .post("/api/v1/media/upload-intent") {
                    header(HttpHeaders.AUTHORIZATION, bearer(owner))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"kind":"IMAGE","contentType":"image/jpeg","sizeBytes":${bytes.size},"checksum":"${sha256(bytes)}"}"""
                }.andReturn()
                .response
        response.status shouldBe 201
        return json.readTree(response.contentAsString)
    }

    /** PUTs [bytes] to the intent's URL with its headers but the length, which the JDK sets. */
    fun put(
        intent: JsonNode,
        bytes: ByteArray,
    ): Int {
        val request = HttpRequest.newBuilder(URI.create(intent["uploadUrl"].asString())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        intent["requiredHeaders"].properties().forEach { (name, value) ->
            if (!name.equals("content-length", ignoreCase = true)) request.header(name, value.asString())
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    fun complete(
        caller: UUID,
        mediaId: String,
        key: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mvc
            .post("/api/v1/media/$mediaId/complete") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, key)
            }.andReturn()
            .response

    fun delete(
        caller: UUID,
        mediaId: String,
    ): MockHttpServletResponse =
        mvc.delete("/api/v1/media/$mediaId") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }.andReturn().response

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    companion object {
        fun sha256(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

/**
 * The asset's row, locked `FOR UPDATE` by a transaction of the test's own and
 * held until [release]: what a slow change to the same asset looks like to a
 * request that needs the row.
 */
internal class HeldRow(
    dataSource: DataSource,
    mediaId: String,
) : AutoCloseable {
    private val connection: Connection = dataSource.connection.apply { autoCommit = false }

    init {
        connection.prepareStatement("SELECT 1 FROM media_assets WHERE id = ?::uuid FOR UPDATE").use {
            it.setString(1, mediaId)
            it.executeQuery().use { rows -> check(rows.next()) { "no row to hold" } }
        }
    }

    fun release() {
        connection.commit()
    }

    override fun close() {
        if (!connection.isClosed) {
            connection.rollback()
            connection.close()
        }
    }
}
