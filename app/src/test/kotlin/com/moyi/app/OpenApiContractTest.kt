package com.moyi.app

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.SecurityConfiguration
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.contracts.OpenApiConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
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
                "/api/v1/bonds/{bondId}/leave",
                "/api/v1/bonds/{bondId}/members/me/settings",
                "/api/v1/bonds/{bondId}/timezone",
                "/api/v1/bonds/{bondId}/timezone/confirm",
                "/api/v1/bonds/{bondId}/deletion-request",
                "/api/v1/bonds/{bondId}/block",
                "/api/v1/bonds/{bondId}/invites",
                "/api/v1/bonds/{bondId}/invites/{inviteId}",
                "/api/v1/invites/{code}",
                "/api/v1/invites/{code}/accept",
            )
    }

    @Test
    fun `the argument resolvers' types are not parameters of any operation`() {
        // CurrentUser and ClientContext are resolved from the token and the
        // socket; documented as query parameters they would generate a client
        // that sends them. Path parameters (`/sessions/{id}`) are real.
        // Query and cookie parameters: none, ever. A resolver type documented as
        // one would generate a client that sends it.
        operations()
            .flatMap { (_, op) ->
                op.parameters
                    .orEmpty()
                    .filter { it.`in` != "path" && it.`in` != "header" }
                    .map { it.name }
            }.shouldBeEmpty()
        // Headers are not all accidental — `If-Match` is required by doc 06 §1
        // and has to appear, or a generated client cannot send it. The list is
        // exhaustive on purpose: a *new* header parameter should have to be
        // justified here, which is what this assertion makes someone do.
        // `Idempotency-Key` joined it in fix round 1, C2 (`submitEntry`).
        operations()
            .flatMap { (_, op) ->
                op.parameters
                    .orEmpty()
                    .filter { it.`in` == "header" }
                    .map { it.name }
            }.toSet() shouldBe setOf(HttpHeaders.IF_MATCH, IdempotencyInterceptor.HEADER)
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
        api.paths["/api/v1/bonds"]!!.post.responses shouldContainKey "409"
    }

    @Test
    fun `ending a bond is documented as 204, and only leaving can conflict`() {
        // Spec §5.2 rows #8-#9. Leave answers 409 BOND_ARCHIVED; block answers
        // no conflict at all, because it is accepted on a bond that has already
        // ended. A generated client modelling a 409 on block would be modelling
        // a state this API never returns (ADR-0028).
        val leave = api.paths["/api/v1/bonds/{bondId}/leave"]!!.post
        val block = api.paths["/api/v1/bonds/{bondId}/block"]!!.post

        leave.responses.keys shouldContainAll listOf("204", "404", "409")
        block.responses.keys shouldContainAll listOf("204", "404")
        block.responses.keys shouldNotContain "409"
    }

    @Test
    fun `the conditional update documents its If-Match, its 428 and its 412`() {
        // Doc 06 §1. A generated client has to be able to *send* the condition
        // and to model both ways it can fail, or the concurrency control is
        // something a client author has to discover by reading prose.
        val patch = api.paths["/api/v1/bonds/{bondId}"]!!.patch

        patch.responses.keys shouldContainAll listOf("200", "404", "409", "412", "422", "428")
        patch.parameters.map { it.name } shouldContain HttpHeaders.IF_MATCH
        patch.parameters.first { it.name == HttpHeaders.IF_MATCH }.`in` shouldBe "header"
        // Required in the document even though the handler takes it as optional:
        // the optionality exists so an absent condition is our 428 rather than
        // Spring's 400, and a client generated from `required: false` would
        // offer a call that cannot succeed (review of #40).
        patch.parameters.first { it.name == HttpHeaders.IF_MATCH }.required shouldBe true
        patch.responses["200"]!!.headers.orEmpty() shouldContainKey "ETag"

        // The member-settings endpoints take no condition, and the document says
        // so by not offering one: nobody else can write that row (ADR-0029).
        val settings = api.paths["/api/v1/bonds/{bondId}/members/me/settings"]!!
        settings.put.parameters
            .orEmpty()
            .map { it.name } shouldNotContain HttpHeaders.IF_MATCH
        settings.put.responses.keys shouldContainAll listOf("200", "404", "409", "422")
        settings.put.responses.keys shouldNotContain "412"
        settings.get.responses shouldContainKey "404"
    }

    @Test
    fun `the consent endpoints document their conflicts, and the deletion request its 202`() {
        // FR-027, FR-028. A generated client has to be able to model the three
        // ways consent is refused, and to know that asking for a deletion is
        // accepted rather than done (`202`).
        val timezone = api.paths["/api/v1/bonds/{bondId}/timezone"]!!
        timezone.patch.responses.keys shouldContainAll listOf("200", "404", "409", "422")
        timezone.patch.responses["200"]!!
            .headers
            .orEmpty() shouldContainKey "ETag"
        timezone.delete.responses shouldContainKey "204"
        // Cancelling is never a conflict: either it was there or it was not.
        timezone.delete.responses.keys shouldNotContain "409"
        api.paths["/api/v1/bonds/{bondId}/timezone/confirm"]!!
            .post.responses.keys shouldContainAll listOf("200", "404", "409")

        val deletion = api.paths["/api/v1/bonds/{bondId}/deletion-request"]!!
        deletion.post.responses.keys shouldContainAll listOf("202", "404", "409")
        deletion.post.responses.keys shouldNotContain "200"
        deletion.delete.responses shouldContainKey "204"

        // The three new codes are in the one enum a client switches on, which is
        // what makes them a breaking change and worth the label (ADR-0024).
        api.components.schemas["ProblemDetail"]!!
            .properties["code"]!!
            .enum
            .map { it.toString() } shouldContainAll
            listOf("PROPOSAL_PENDING", "PROPOSAL_NEEDS_OTHER_MEMBER", "TIMEZONE_CHANGE_TOO_SOON")
    }

    @Test
    fun `submitting an entry documents its Idempotency-Key, and every 409 it can give`() {
        // Fix round 1, C2: two things a generated client had no way to model.
        // (a) The header doc 06 §1 requires — `@Idempotent` is invisible to
        // springdoc, so without OpenApiConfiguration adding it explicitly a
        // generated client would call this endpoint with no way to send it
        // at all, and every call would be refused as 422. (b) The 409 this
        // operation's own headline behaviour (BR-2, ENTRY_ALREADY_EXISTS)
        // needs, alongside BOND_ARCHIVED, DAY_CLOSED and
        // IDEMPOTENCY_KEY_IN_FLIGHT — all four are 409s the same ProblemDetail
        // schema carries, so this asserts the status is offered at all, not
        // one code at a time.
        val submit = api.paths["/api/v1/bonds/{bondId}/entries"]!!.post

        submit.responses.keys shouldContainAll listOf("201", "404", "409", "422")
        submit.parameters.map { it.name } shouldContain IdempotencyInterceptor.HEADER
        submit.parameters.first { it.name == IdempotencyInterceptor.HEADER }.`in` shouldBe "header"
        submit.parameters.first { it.name == IdempotencyInterceptor.HEADER }.required shouldBe true
    }

    @Test
    fun `the entry endpoints document their conflicts, and today its 404`() {
        val entries = api.paths["/api/v1/bonds/{bondId}/entries"]!!.post
        entries.responses.keys shouldContainAll listOf("201", "404", "409", "422")
        entries.parameters.map { it.name } shouldContain "Idempotency-Key"
        entries.parameters.first { it.name == "Idempotency-Key" }.required shouldBe true

        api.paths["/api/v1/bonds/{bondId}/today"]!!.get.responses shouldContainKey "404"
    }

    @Test
    fun `partnerEntry is one of three branches a generated client can tell apart by shape`() {
        // springdoc resolves the sealed interface to a `oneOf` of its
        // branches. There is deliberately NO discriminator: `"DELETED"` is
        // both EntryResponse's tombstone and ErasedEntryResponse's only
        // value (a partner's entry erased before it was ever revealed, which
        // BR-8 keeps to author-and-status), and a discriminator maps a value
        // to exactly one schema. So the shapes themselves must be decidable
        // from the document: only EntryResponse has (and requires) an `id`,
        // and the two narrow branches' `status` enums are disjoint.
        val today = api.components.schemas["TodayResponse"]!!
        val partnerEntry = today.properties["partnerEntry"]!!

        partnerEntry.oneOf.map { it.`$ref` } shouldContainExactlyInAnyOrder
            listOf(
                "#/components/schemas/EntryResponse",
                "#/components/schemas/LockedEntryResponse",
                "#/components/schemas/ErasedEntryResponse",
            )
        partnerEntry.discriminator.shouldBeNull()

        val narrow =
            listOf("LockedEntryResponse" to "LOCKED", "ErasedEntryResponse" to "DELETED").map { (name, status) ->
                val fields =
                    api.components.schemas[name]!!
                        .allOf
                        .last()
                // BR-8: the type has nowhere to put anything else.
                fields.properties.keys shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
                fields.required shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
                fields.properties["status"]!!.enum shouldBe listOf(status)
                fields
            }
        narrow.forEach { it.properties.keys shouldNotContain "id" }
    }

    @Test
    fun `an entry's text is required though nullable, so a tombstone is not an absent field`() {
        // `"text": null` is how an erased entry is rendered (an Idempotency-Key
        // replay re-reads it). Optional as well as nullable, a generated
        // client could not tell that from a field the server never sent.
        val fields =
            api.components.schemas["EntryResponse"]!!
                .allOf
                .last()

        fields.required shouldContainAll
            listOf("id", "bondId", "date", "authorMemberId", "text", "status", "createdAt", "intendedAt")
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
    fun `joining by a code returns a bond, and every code operation documents its 404`() {
        // FR-024's one answer has to be modellable by a generated client: the
        // four ways a code fails are one status and one code, and the client
        // switches on the code.
        val accept = api.paths["/api/v1/invites/{code}/accept"]!!.post
        accept.responses.keys shouldContainAll listOf("404", "409", "422")
        accept.responses["200"]!!
            .content[MediaType.APPLICATION_JSON_VALUE]!!
            .schema.`$ref` shouldBe "#/components/schemas/BondResponse"
        api.paths["/api/v1/invites/{code}"]!!
            .get.responses.keys shouldContainAll listOf("404", "422")
        api.paths["/api/v1/bonds/{bondId}/invites"]!!
            .post.responses.keys shouldContainAll listOf("201", "409")
        api.paths["/api/v1/bonds/{bondId}/invites/{inviteId}"]!!.delete.responses shouldContainKey "204"
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
