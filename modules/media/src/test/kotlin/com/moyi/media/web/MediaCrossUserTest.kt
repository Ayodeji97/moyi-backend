package com.moyi.media.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

/**
 * Media is a person's, not a bond's: a second user must never read, complete or
 * delete the first user's asset, and must not learn that it exists (doc 06:183,
 * BR-1; doc 12:56 asks for cross-*user* tests because the cross-tenant suite,
 * which is about bonds, cannot see this leak).
 *
 * Every route whose path names a media id is found by reflection, so a route
 * added later with no fixture here fails the build, as the bond-scoped suites
 * do. Each answers a stranger with the same bytes as an id that names nothing
 * and a value that is not an id, and leaves the owner's asset as it was.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
internal class MediaCrossUserTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val routes: RequestMappingHandlerMapping,
    @Autowired dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()

    private val covered =
        setOf(
            "GET /api/v1/media/{mediaId}",
            "DELETE /api/v1/media/{mediaId}",
            "POST /api/v1/media/{mediaId}/complete",
        )

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_assets, idempotency_keys, outbox_deliveries, outbox_events")
    }

    @Test
    fun `every route that names a media id answers another user exactly as it answers nonsense, and changes nothing`() {
        val mediaScoped = mediaScopedRoutes()
        mediaScoped.shouldNotBeEmpty()
        val uncovered = mediaScoped - covered
        check(uncovered.isEmpty()) {
            "Routes naming a media id with no cross-user check. Add them to MediaCrossUserTest: a media route not " +
                "tested against another user is how one person's photo reaches another (doc 12:56): $uncovered"
        }

        val ada = UUID.randomUUID()
        val eve = UUID.randomUUID()
        val mediaId = intent(ada)

        mediaScoped.forEach { route ->
            val stranger = call(route, eve, mediaId)
            val randomId = call(route, ada, UUID.randomUUID().toString())
            val notAnId = call(route, ada, "not-a-media-id")

            listOf(
                "another user" to stranger,
                "a random id" to randomId,
                "a value that is not an id" to notAnId,
            ).forEach { (who, response) ->
                check(response.status == 404) { "$route with $who: expected 404, got ${response.status}: ${response.contentAsString}" }
                response.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
            }
            stranger.contentAsString.withoutInstance() shouldBe randomId.contentAsString.withoutInstance()
            stranger.contentAsString.withoutInstance() shouldBe notAnId.contentAsString.withoutInstance()
        }

        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "PENDING_UPLOAD"
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Int::class.java) shouldBe 0
    }

    private fun mediaScopedRoutes(): Set<String> =
        routes.handlerMethods.keys
            .flatMap { info ->
                val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
                val methods = info.methodsCondition.methods.ifEmpty { setOf(RequestMethod.GET) }
                patterns.filter { it.startsWith("/api/v1/media/{") }.flatMap { pattern -> methods.map { "${it.name} $pattern" } }
            }.toSet()

    private fun call(
        route: String,
        caller: UUID,
        mediaId: String,
    ): MockHttpServletResponse {
        val (method, pattern) = route.split(" ", limit = 2)
        val request =
            MockMvcRequestBuilders
                .request(HttpMethod.valueOf(method), pattern.replace("{mediaId}", mediaId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                .header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
        return mvc.perform(request).andReturn().response
    }

    private fun intent(owner: UUID): String {
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest("fake".toByteArray()))
        val response =
            mvc
                .post("/api/v1/media/upload-intent") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(owner).token}")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"kind":"IMAGE","contentType":"image/png","sizeBytes":4,"checksum":"$digest"}"""
                }.andReturn()
                .response
        response.status shouldBe 201
        return json.readTree(response.contentAsString)["mediaId"].asString()
    }

    private fun String.withoutInstance(): String = replace(Regex("\"instance\":\"[^\"]*\""), "")
}
