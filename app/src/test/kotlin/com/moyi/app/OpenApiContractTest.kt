package com.moyi.app

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.SecurityConfiguration
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.ErrorCode
import com.moyi.contracts.OpenApiConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.util.UUID
import javax.sql.DataSource

/**
 * Doc 12 §3.4: the OpenAPI document is generated from the running
 * application and validated there. What is asserted is the contract's
 * *shape* — the part a generated client is built from — not its prose.
 *
 * The document is read behind the bearer like everything else (doc 06 §1)
 * and written to `build/openapi/openapi.json`; the last test compares it
 * with the committed `contracts/openapi.json`, which is what the client repo
 * and the breaking-change diff read (ADR-0024).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiContractTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val issuer: AccessTokenIssuer,
    @Autowired private val json: JsonMapper,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private lateinit var document: String
    private lateinit var api: OpenAPI

    @BeforeAll
    fun fetch() {
        val response = mockMvc.get("/v3/api-docs") { header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}") }.andReturn().response
        response.status shouldBe 200
        response.contentType!! shouldStartWith MediaType.APPLICATION_JSON_VALUE
        document = response.contentAsString
        GENERATED.parentFile.mkdirs()
        GENERATED.writeText(document)

        val parsed = OpenAPIV3Parser().readContents(document, null, ParseOptions().apply { isResolve = true })
        parsed.messages.orEmpty().shouldBeEmpty()
        api = parsed.openAPI
    }

    @Test
    fun `it is OpenAPI 3 and knows every route that exists`() {
        api.openapi shouldStartWith "3."
        api.paths.keys shouldContainAll
            listOf(
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/me",
                "/api/v1/bonds",
                "/api/v1/bonds/{bondId}",
            )
    }

    @Test
    fun `the argument resolvers' types are not parameters of any operation`() {
        // CurrentUser and ClientContext are resolved from the token and the
        // socket; documented as query parameters they would generate a client
        // that sends them. Path parameters (`/sessions/{id}`) are real.
        operations()
            .flatMap { (_, op) ->
                op.parameters
                    .orEmpty()
                    .filter { it.`in` != "path" }
                    .map { it.name }
            }.shouldBeEmpty()
        api.components.schemas.keys
            .filter { it in setOf("CurrentUser", "ClientContext") }
            .shouldBeEmpty()
    }

    @Test
    fun `every operation documents the problem responses it can return`() {
        // Doc 06 §2: one error shape. 429 and 500 can happen to anything;
        // 400 and 422 to anything with a body; 401 to anything behind the
        // bearer. Each points at the one ProblemDetail schema.
        operations().forEach { (name, op) ->
            val responses = op.responses.keys
            responses shouldContainAll listOf("429", "500")
            if (op.requestBody != null) responses shouldContainAll listOf("400", "422")
            if (!isPublic(name)) op.responses shouldContainKey "401"
            // An operation that names a resource by id can answer "not found
            // or not permitted to know it exists" (doc 06 §2), and a
            // generated client has to be able to model that (Codex on #35).
            if (op.parameters.orEmpty().any { it.`in` == "path" }) op.responses shouldContainKey "404"
            responses.filter { it.startsWith("4") || it.startsWith("5") }.forEach { status ->
                op.responses[status]!!
                    .content[MediaType.APPLICATION_PROBLEM_JSON_VALUE]!!
                    .schema.`$ref` shouldBe "#/components/schemas/ProblemDetail"
            }
        }
    }

    @Test
    fun `the problem schema enumerates every error code, so the client's sealed class is exhaustive`() {
        val problem = api.components.schemas["ProblemDetail"]!!
        val code = problem.properties["code"]!!
        code.enum.map { it.toString() } shouldContainExactly ErrorCode.entries.map { it.name }
        problem.properties.keys shouldContainAll listOf("type", "title", "status", "detail", "instance", "code", "errors")
    }

    @Test
    fun `the public auth endpoints carry no bearer requirement, and everything else does`() {
        operations().forEach { (name, op) ->
            val required = op.security?.let { it.isNotEmpty() } ?: (api.security?.isNotEmpty() == true)
            required shouldBe !isPublic(name)
        }
    }

    @Test
    fun `ending a session documents its 404`() {
        api.paths["/api/v1/auth/sessions/{id}"]!!.delete.responses shouldContainKey "404"
    }

    @Test
    fun `reading a bond documents its 404, and creating one its 201`() {
        // Doc 06 §2 and ADR-0024's amendment: an operation that names a
        // resource by a path parameter can answer "not found or not permitted
        // to know it exists", and a generated client has to be able to model
        // that. For bonds it is the *usual* answer to a stranger (T-02), not
        // an edge case.
        val bond = api.paths["/api/v1/bonds/{bondId}"]!!.get
        bond.responses shouldContainKey "404"
        bond.responses["200"]!!.content.keys shouldContainExactly listOf(MediaType.APPLICATION_JSON_VALUE)
        api.paths["/api/v1/bonds"]!!.post.responses shouldContainKey "201"
    }

    @Test
    fun `a response carrying a versioned resource declares its ETag`() {
        // The header is set on the ResponseEntity, so springdoc cannot see it
        // and an OpenApiCustomizer adds it by rule. Without it a generated
        // client has no typed way to keep the value that `If-Match` must send
        // back, which is the whole reason these endpoints return one (doc 06
        // §1). Raised by the review of PR #37.
        val versioned =
            operations().filter { (_, op) ->
                op.responses.any { (status, response) ->
                    status.startsWith("2") &&
                        response.content?.values?.any {
                            it.schema
                                ?.`$ref`
                                ?.substringAfterLast('/') in OpenApiConfiguration.VERSIONED_RESOURCE_SCHEMAS
                        } == true
                }
            }

        versioned.shouldNotBeEmpty()
        versioned.forEach { (name, op) ->
            op.responses
                .filterKeys { it.startsWith("2") }
                .forEach { (status, response) ->
                    withClue("$name -> $status") { response.headers.orEmpty() shouldContainKey "ETag" }
                }
        }
    }

    @Test
    fun `logout is 204, and success bodies are JSON, not star-slash-star`() {
        api.paths["/api/v1/auth/logout"]!!.post.responses shouldContainKey "204"
        api.paths["/api/v1/auth/logout-all"]!!.post.responses shouldContainKey "204"
        api.paths["/api/v1/auth/login"]!!
            .post.responses["200"]!!
            .content.keys shouldContainExactly listOf(MediaType.APPLICATION_JSON_VALUE)
    }

    @Test
    fun `every operation has a distinct id, and none was renamed by a collision`() {
        // springdoc derives `operationId` from the *method name alone* — the
        // controller class is not part of it — and silently appends `_1` when
        // two collide, picking the loser by scan order. A generated client
        // names its methods after these, so a collision renames a method for an
        // endpoint that did not change, and `oasdiff` does not notice because
        // no path or schema moved. Slice B1 renamed the sessions list that way
        // and it took a reviewer to see it.
        val ids = operations().map { (route, op) -> route to op.operationId }

        ids.forEach { (route, id) ->
            withClue(route) {
                id.shouldNotBeNull()
                // The suffix springdoc adds on a collision. Its presence means
                // two controller methods share a name: rename one after what
                // the *API* calls it, not after what reads well in Kotlin.
                id.endsWith("_1") shouldBe false
            }
        }
        ids.map { it.second }.toSet().size shouldBe ids.size
    }

    @Test
    fun `the committed document is the generated one`() {
        // The committed file is what the client repo and the breaking-change
        // diff read. If this fails, the API changed: copy the generated file
        // over the committed one and review the diff in the PR.
        val generated = json.readTree(document)
        val committed = COMMITTED.takeIf { it.exists() }?.let(json::readTree)
        if (committed != generated) {
            error(
                "contracts/openapi.json is stale. Regenerate with: " +
                    "cp app/build/openapi/openapi.json contracts/openapi.json — then read the diff, it is the API contract changing.",
            )
        }
    }

    private fun operations(): List<Pair<String, Operation>> =
        api.paths.flatMap { (path, item) -> item.readOperationsMap().map { (method, op) -> "$method $path" to op } }

    private fun isPublic(operation: String): Boolean = SecurityConfiguration.PUBLIC_AUTH_ENDPOINTS.any { operation == "POST $it" }

    private fun token(): String {
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"spec-reader@example.com","password":"correct horse battery","displayName":"Spec",""" +
                    """"locale":"en","acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        val userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'spec-reader@example.com'", UUID::class.java)!!
        return issuer.issue(userId).token
    }

    private companion object {
        val GENERATED = File("build/openapi/openapi.json")
        val COMMITTED = File("../contracts/openapi.json")
    }
}
