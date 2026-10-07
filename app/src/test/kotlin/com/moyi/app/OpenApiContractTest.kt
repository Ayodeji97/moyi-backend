package com.moyi.app

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.SecurityConfiguration
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.common.web.idempotency.Idempotent
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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import io.kotest.matchers.string.shouldStartWith
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
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
    @Autowired @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
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
        // Cookie parameters: none, ever. Query parameters: the archive feed's
        // four and no others (slice C5b). A resolver type documented as one
        // would generate a client that sends it, so the list is exhaustive: a
        // new query parameter has to be added here by somebody who meant it.
        operations()
            .flatMap { (route, op) ->
                op.parameters
                    .orEmpty()
                    .filter { it.`in` != "path" && it.`in` != "header" }
                    .map { "$route ${it.`in`} ${it.name}" }
            }.toSet() shouldBe
            listOf("limit", "cursor", "until", "favourites").map { "GET /api/v1/bonds/{bondId}/days query $it" }.toSet()
        // Headers are not all accidental — `If-Match` is required by doc 06 §1
        // and has to appear, or a generated client cannot send it. The list is
        // exhaustive on purpose: a *new* header parameter should have to be
        // justified here, which is what this assertion makes someone do.
        // `Idempotency-Key` joined it in C1 (`submitEntry`), and `patchEntry` carries it since C2.
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
    fun `each ending says what an absent flag does to the caller's entries, and neither says which ending it is`() {
        // One request type serves both routes and its flag has opposite
        // defaults: absent, it erases on one and keeps on the other, and an
        // erasure cannot be undone. The generated type is an optional nullable
        // boolean, so the document is the only place a person writing a
        // client can learn that leaving it out destroys something. Found by
        // the privacy and contract review of the C5a branch.
        val leave =
            api.paths["/api/v1/bonds/{bondId}/leave"]!!
                .post.description
                .shouldNotBeNull()
        val other =
            api.paths["/api/v1/bonds/{bondId}/block"]!!
                .post.description
                .shouldNotBeNull()
        val flag =
            api.components.schemas["EndBondRequest"]!!
                .properties["withdrawEntries"]!!
                .description
                .shouldNotBeNull()

        leave shouldContain "absent"
        leave shouldContain "are kept"
        leave shouldContain "cannot be undone"
        other shouldContain "absent"
        other shouldContain "are ERASED"
        other shouldContain "cannot be undone"
        other shouldContain """{"withdrawEntries": false}"""
        // The shared type cannot state a default that is right for both.
        flag shouldContain "depends on the route"
        // ADR-0028 decision 8: nothing says which ending is which. The path
        // is the route's own name; its prose, and the other route's, do not
        // repeat it.
        listOf(leave, other, flag).forEach { it shouldNotContainIgnoringCase "block" }
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
    fun `changing an entry documents an optional key, a text that is never null, and its refusals`() {
        val patch = api.paths["/api/v1/entries/{entryId}"]!!.patch
        val delete = api.paths["/api/v1/entries/{entryId}"]!!.delete

        // The key is honoured on PATCH and may be left out; the controller's
        // own annotation is the authority, and the document must agree with it.
        val key = patch.parameters.first { it.name == IdempotencyInterceptor.HEADER }
        key.required shouldBe false
        handlerOf("patchEntry").getMethodAnnotation(Idempotent::class.java)!!.required shouldBe false
        handlerOf("submitEntry").getMethodAnnotation(Idempotent::class.java)!!.required shouldBe true
        // DELETE is idempotent by nature and takes no key at all.
        delete.parameters.orEmpty().map { it.name } shouldNotContain IdempotencyInterceptor.HEADER

        // `{"text": null}` is a 422: the document must not say it is accepted.
        val text = api.components.schemas["PatchEntryRequest"]!!.properties["text"]!!
        text.types shouldBe setOf("string")
        api.components.schemas["PatchEntryRequest"]!!.required shouldContain "text"
        text.maxLength shouldBe
            api.components.schemas["SubmitEntryRequest"]!!
                .properties["text"]!!
                .maxLength

        // 409 is ENTRY_IMMUTABLE and BOND_ARCHIVED; 413 is the body bound, key or no key.
        patch.responses.keys shouldContainAll listOf("200", "404", "409", "413", "415", "422")
        // A delete cannot conflict. Its author may take an entry back on any bond, ended or not
        // (the ruling on ADR-0032 question 1; it was BOND_ARCHIVED before), it takes no key, and
        // a repeat is a 204. A client modelling a 409 here would model an answer never given.
        delete.responses.keys shouldContainAll listOf("204", "404")
        delete.responses.keys shouldNotContain "409"
    }

    @Test
    fun `the streak is documented on its own route and on today, with no field about either member`() {
        val streak = api.paths["/api/v1/bonds/{bondId}/streak"]!!.get

        streak.operationId shouldBe "streak"
        streak.responses.keys shouldContainAll listOf("200", "404")
        // FR-076: counts about the bond and a status per day. A field about a
        // member would tell one of them something about the other.
        api.components.schemas["StreakResponse"]!!
            .properties.keys shouldBe
            setOf("current", "longest", "freezesAvailable", "freezeProgress", "strictMode", "totalCompleteDays", "lastCompleteDate", "days")
        api.components.schemas["StreakDayResponse"]!!
            .properties.keys shouldBe setOf("date", "status")
        api.components.schemas["TodayStreakResponse"]!!
            .properties.keys shouldBe
            setOf("current", "longest", "freezesAvailable", "strictMode")
        api.components.schemas["TodayResponse"]!!
            .properties.keys shouldContain "streak"
    }

    @Test
    fun `an idempotent operation documents its key's bound, and the 413 and 415 its body can earn`() {
        // Final whole-branch review, A5 and A6. The key is 1 to 255 visible
        // ASCII characters (V11's CHECK and the interceptor say the same), a
        // body past the interceptor's bound is 413, and a multipart one is
        // Spring's own 415 — each of which used to be an undocumented 500.
        val submit = api.paths["/api/v1/bonds/{bondId}/entries"]!!.post

        val key = submit.parameters.first { it.name == IdempotencyInterceptor.HEADER }.schema
        key.minLength shouldBe 1
        key.maxLength shouldBe IdempotencyInterceptor.MAX_KEY_LENGTH
        Regex(key.pattern).matches("0f8fad5b-d9cb-469f-a165-70867728950e") shouldBe true
        Regex(key.pattern).matches("two words") shouldBe false
        submit.responses.keys shouldContainAll listOf("413", "415")
        // Not on a route that buffers nothing: `today` takes no body at all.
        val today = api.paths["/api/v1/bonds/{bondId}/today"]!!.get
        today.responses.keys shouldNotContain "413"
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
    fun `the archive feed documents its four parameters with their bounds, its 422 and its 404`() {
        // The handler takes the four as optional text and reads them after the
        // membership guard, so springdoc alone would document four strings.
        // OpenApiConfiguration states what the route enforces.
        val days = api.paths["/api/v1/bonds/{bondId}/days"]!!.get
        days.operationId shouldBe "days"
        days.responses.keys shouldContainAll listOf("200", "401", "404", "422", "429")
        days.responses.keys shouldNotContain "409"
        days.requestBody.shouldBeNull()

        val query = days.parameters.filter { it.`in` == "query" }.associateBy { it.name }
        query.keys shouldBe setOf("limit", "cursor", "until", "favourites")
        query.values.forEach { it.required shouldBe false }

        fun typesOf(name: String) = query.getValue(name).schema.let { it.types ?: setOf(it.type) }

        val limit = query.getValue("limit").schema
        typesOf("limit") shouldBe setOf("integer")
        limit.minimum.toInt() shouldBe 1
        limit.maximum.toInt() shouldBe 50
        limit.default.toString() shouldBe "20"
        typesOf("until") shouldBe setOf("string")
        query.getValue("until").schema.format shouldBe "date"
        typesOf("favourites") shouldBe setOf("boolean")
        query.getValue("favourites").schema.default shouldBe false
        // Opaque: a string with no format, so no client is generated to take it apart.
        typesOf("cursor") shouldBe setOf("string")
        query
            .getValue("cursor")
            .schema.format
            .shouldBeNull()
    }

    @Test
    fun `a day of the archive carries the two entry fields exactly as today does, and a page is days and a cursor`() {
        // One reader for an entry, wherever it is met: the same branches, the
        // same discriminator, the same null. And no field about the other
        // person: nothing but a date, the day's status and the two entries.
        val today = api.components.schemas["TodayResponse"]!!.properties
        val day = api.components.schemas["DayResponse"]!!
        day.properties.keys shouldBe setOf("date", "status", "myEntry", "partnerEntry")
        day.properties["partnerEntry"] shouldBe today["partnerEntry"]
        day.properties["myEntry"] shouldBe today["myEntry"]
        day.properties["partnerEntry"]!!.discriminator.shouldNotBeNull()
        day.properties["partnerEntry"]!!.oneOf.size shouldBe 4
        day.properties["date"]!!.format shouldBe "date"

        val page = api.components.schemas["DaysResponse"]!!
        page.properties.keys shouldBe setOf("items", "nextCursor")
        page.properties["items"]!!.items.`$ref` shouldBe "#/components/schemas/DayResponse"
    }

    @Test
    fun `a favourite is put and deleted with no body, and only putting one can conflict`() {
        val favourite = api.paths["/api/v1/entries/{entryId}/favourite"]!!
        favourite.readOperationsMap().keys.map { it.name } shouldContainExactlyInAnyOrder listOf("PUT", "DELETE")

        // 409 is ENTRY_NOT_REVEALED and ENTRY_IMMUTABLE. No body goes in, so no 400 or 422, and none comes out.
        favourite.put.operationId shouldBe "favouriteEntry"
        favourite.put.responses.keys shouldContainExactlyInAnyOrder listOf("204", "401", "403", "404", "409", "429", "500")
        favourite.put.requestBody shouldBe null
        favourite.put.responses["204"]!!.content shouldBe null
        favourite.put.parameters.map { it.name } shouldBe listOf("entryId")

        // Taking a mark off is never a conflict: absent is success, on a tombstone too.
        favourite.delete.operationId shouldBe "unfavouriteEntry"
        favourite.delete.responses.keys shouldContainExactlyInAnyOrder listOf("204", "401", "403", "404", "429", "500")
        favourite.delete.responses["204"]!!.content shouldBe null
        favourite.delete.parameters.map { it.name } shouldBe listOf("entryId")
    }

    @Test
    fun `an entry says whether its reader kept it, and the two shapes for an entry never shown have no such field`() {
        val full =
            api.components.schemas["EntryResponse"]!!
                .allOf
                .last()
        full.properties["favourited"]!!.types shouldBe setOf("boolean")
        full.required shouldContain "favourited"
        listOf("LockedEntryResponse", "ErasedEntryResponse").forEach { name ->
            api.components.schemas[name]!!
                .allOf
                .last()
                .properties.keys shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        }
    }

    @Test
    fun `partnerEntry is one of three branches, discriminated on status with no value naming two`() {
        // springdoc resolves the sealed interface to a `oneOf` of its
        // branches; OpenApiConfiguration adds the `discriminator`, which is
        // what a generated client builds its sealed types from (doc 06 §2).
        // A discriminator maps a value to exactly one schema, so the three
        // branches' `status` enums must be disjoint — which is why a
        // partner's never-revealed erased entry says `REMOVED`, not the wide
        // tombstone's `DELETED`.
        val today = api.components.schemas["TodayResponse"]!!
        val partnerEntry = today.properties["partnerEntry"]!!
        val branches = listOf("EntryResponse", "LockedEntryResponse", "ErasedEntryResponse")

        partnerEntry.oneOf.mapNotNull { it.`$ref` } shouldContainExactlyInAnyOrder branches.map { "#/components/schemas/$it" }
        partnerEntry.discriminator.shouldNotBeNull()
        partnerEntry.discriminator.propertyName shouldBe "status"
        partnerEntry.discriminator.mapping shouldBe
            mapOf(
                "SUBMITTED" to "#/components/schemas/EntryResponse",
                "REVEALED" to "#/components/schemas/EntryResponse",
                "DELETED" to "#/components/schemas/EntryResponse",
                "LOCKED" to "#/components/schemas/LockedEntryResponse",
                "REMOVED" to "#/components/schemas/ErasedEntryResponse",
            )

        // The mapping is the document's claim; each branch's own `status`
        // enum is what the server can actually send. Every value a branch
        // can send maps to that branch, so no value names two schemas and
        // none is left to the OpenAPI default.
        val statusesByBranch =
            branches.associateWith { name ->
                val fields =
                    api.components.schemas[name]!!
                        .allOf
                        .last()
                fields.required shouldContain "status"
                fields.properties["status"]!!.enum.map { it.toString() }
            }
        statusesByBranch.values.flatten().let { all -> all.toSet().size shouldBe all.size }
        statusesByBranch.forEach { (branch, statuses) ->
            statuses.forEach { partnerEntry.discriminator.mapping[it] shouldBe "#/components/schemas/$branch" }
        }
        statusesByBranch.values.flatten().toSet() shouldBe partnerEntry.discriminator.mapping.keys
    }

    @Test
    fun `partnerEntry may be null, as myEntry may - the day before the partner has written`() {
        // `GET /today` answers `"partnerEntry": null` until the partner
        // writes. springdoc says so for a nullable `$ref` (myEntry) and not
        // for a nullable sealed interface's `oneOf`; OpenApiConfiguration adds
        // the branch. Without it the contract claims the field is always one
        // of the three objects, and a strict client rejects the ordinary
        // first response of every day.
        val today = api.components.schemas["TodayResponse"]!!

        listOf("myEntry", "partnerEntry").forEach { name ->
            withClue(name) {
                val branches = today.properties[name]!!.oneOf.shouldNotBeNull()
                branches.filter { it.`$ref` == null }.map { it.types } shouldBe listOf(setOf("null"))
            }
        }
        // Exactly the three objects and the null: nothing else was admitted.
        today.properties["partnerEntry"]!!.oneOf.size shouldBe 4
    }

    @Test
    fun `the two narrow partnerEntry branches carry an author and a status, both required, and nothing else`() {
        // BR-8: the type has nowhere to put anything else.
        listOf("LockedEntryResponse" to "LOCKED", "ErasedEntryResponse" to "REMOVED").forEach { (name, status) ->
            val fields =
                api.components.schemas[name]!!
                    .allOf
                    .last()
            fields.properties.keys shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
            fields.required shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
            fields.properties["status"]!!.enum shouldBe listOf(status)
        }
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

    private fun handlerOf(operationId: String) = handlerMapping.handlerMethods.values.single { it.method.name == operationId }

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
