package com.moyi.app

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.SecurityConfiguration
import com.moyi.common.testing.ArchivePageBudget
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
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
@Suppress("LargeClass") // One document, and everything held about it where it can be read together.
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
        // four (slice C5b), search's three (slice C6b) and no others. A resolver
        // type documented as one would generate a client that sends it, so the
        // list is exhaustive: a new query parameter has to be added here by
        // somebody who meant it.
        operations()
            .flatMap { (route, op) ->
                op.parameters
                    .orEmpty()
                    .filter { it.`in` != "path" && it.`in` != "header" }
                    .map { "$route ${it.`in`} ${it.name}" }
            }.toSet() shouldBe
            listOf("limit", "cursor", "until", "favourites").map { "GET /api/v1/bonds/{bondId}/days query $it" }.toSet() +
            listOf("q", "limit", "cursor").map { "GET /api/v1/bonds/{bondId}/search query $it" }
        // Headers are not all accidental — `If-Match` is required by doc 06 §1
        // and has to appear, or a generated client cannot send it. The list is
        // exhaustive on purpose: a *new* header parameter should have to be
        // justified here, which is what this assertion makes someone do.
        // `Idempotency-Key` joined it in C1 (`submitEntry`), and `patchEntry` carries it since C2.
        // `If-None-Match` in C5b: the archive's two reads are conditional, and a client has to be able to send its tag.
        operations()
            .flatMap { (_, op) ->
                op.parameters
                    .orEmpty()
                    .filter { it.`in` == "header" }
                    .map { it.name }
            }.toSet() shouldBe setOf(HttpHeaders.IF_MATCH, HttpHeaders.IF_NONE_MATCH, IdempotencyInterceptor.HEADER)
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
    fun `one ending requires its body and its flag, never null, and the other takes both as optional`() {
        // ADR-0035 questions 1 and 11, the owner's ruling of 2026-10-21. The
        // handler takes the shared optional type, so that the guard runs
        // before the flag is asked for; springdoc can see only that, and
        // without the customiser the document would say the body may be left
        // out of a route that refuses it.
        val leave = api.paths["/api/v1/bonds/{bondId}/leave"]!!.post.requestBody
        val other = api.paths["/api/v1/bonds/{bondId}/block"]!!.post.requestBody

        leave.required shouldNotBe true
        leave.content["application/json"]!!.schema.`$ref` shouldBe "#/components/schemas/EndBondRequest"

        other.required shouldBe true
        other.content.keys shouldBe setOf("application/json")
        other.content["application/json"]!!.schema.`$ref` shouldBe "#/components/schemas/RequiredEndBondRequest"
        val required = api.components.schemas["RequiredEndBondRequest"]!!
        required.required shouldBe listOf("withdrawEntries")
        required.properties.keys shouldBe setOf("withdrawEntries")
        val flag = required.properties["withdrawEntries"]!!
        // A boolean and nothing else: `null` is not one of its types.
        (flag.types ?: setOf(flag.type)) shouldBe setOf("boolean")
        flag.description.shouldNotBeNull() shouldNotContainIgnoringCase "block"
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
        // Two things the schema cannot say and a client gets wrong by guessing (the review of slice C5b): a short page
        // is not the end, and a day whose status is EMPTY can hold the caller's own tombstone. It no longer says that a
        // page can be empty with a cursor as a matter of course: only at the bound on one request's reading.
        val told = days.description.shouldNotBeNull()
        told shouldContain "Only a null `nextCursor` means the end"
        told shouldContain "`items` can be shorter than `limit`"
        told shouldContain "A page with a `nextCursor` holds at least one day, with one exception"
        told shouldNotContain "`items` can be empty"
        told shouldContain "`status: EMPTY`"
        told shouldContain "tombstone"
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
        // And bounded as the route bounds it: a longer one is refused unread (`DayCursor.MAX_LENGTH`, internal to gratitude).
        query.getValue("cursor").schema.maxLength shouldBe 32
    }

    @Test
    fun `both archive reads document the 406 that comes before the membership check, and no other operation documents one`() {
        val reads = listOf("/api/v1/bonds/{bondId}/days", "/api/v1/bonds/{bondId}/days/{date}").map { api.paths[it]!!.get }

        reads.forEach { read ->
            withClue(read.operationId) {
                val refused = read.responses["406"].shouldNotBeNull()
                refused.content.keys shouldBe setOf("application/problem+json")
                refused.headers.orEmpty().keys shouldNotContain "ETag"
                refused.description shouldContain "before the caller's membership is looked at"
                refused.description shouldContain "the same response for a member"
            }
        }
        // Everywhere else a 406 comes after the handler, as it always has, and is documented nowhere (as 405 is not).
        operations().filter { (_, op) -> op.responses.containsKey("406") }.map { it.second.operationId } shouldContainExactlyInAnyOrder
            listOf("days", "day")
    }

    @Test
    fun `the day and the two favourite operations say what the schema cannot`() {
        // One day: today is not there until the caller has written, and where to read it instead.
        val day =
            api.paths["/api/v1/bonds/{bondId}/days/{date}"]!!
                .get.description
                .shouldNotBeNull()
        day shouldContain "`404 DAY_NOT_FOUND` for every date that is not in the caller's archive"
        day shouldContain "read today from `GET /bonds/{bondId}/today`"
        day shouldContain "does not say whether there is anything to draw"

        // A mark: whose it is, what can be marked, and which of the two 409s means what.
        val favourite = api.paths["/api/v1/entries/{entryId}/favourite"]!!
        val mark = favourite.put.description.shouldNotBeNull()
        mark shouldContain "The other member is never shown it"
        mark shouldContain "`status: REVEALED`"
        mark shouldContain "`409 ENTRY_NOT_REVEALED`"
        mark shouldContain "`409 ENTRY_IMMUTABLE`"
        mark shouldContain "never shown"
        mark shouldContain "Allowed on a bond that has ended"

        val unmark = favourite.delete.description.shouldNotBeNull()
        unmark shouldContain "no bookmark is success"
        unmark shouldContain "never shown"
        unmark shouldContain "Allowed on a bond that has ended"
    }

    @Test
    fun `a day of the archive carries the two entry fields exactly as today does, and a page is days and a cursor`() {
        // One reader for an entry, wherever it is met: the same branches, the
        // same discriminator, the same null. And no field about the other
        // person but one: a date, the day's status, the two entries, and the
        // name the partner goes by, which their bond already tells the caller
        // (ADR-0036 question 5), nullable for a bond nobody has joined yet.
        val today = api.components.schemas["TodayResponse"]!!.properties
        val day = api.components.schemas["DayResponse"]!!
        day.properties.keys shouldBe setOf("date", "status", "myEntry", "partnerEntry", "partnerDisplayName")
        day.properties["partnerDisplayName"]!!.types shouldBe setOf("string", "null")
        today.keys shouldNotContain "partnerDisplayName"
        day.properties["partnerEntry"] shouldBe today["partnerEntry"]
        day.properties["myEntry"] shouldBe today["myEntry"]
        day.properties["partnerEntry"]!!.discriminator.shouldNotBeNull()
        day.properties["partnerEntry"]!!.oneOf.size shouldBe 3
        day.properties["date"]!!.format shouldBe "date"

        val page = api.components.schemas["DaysResponse"]!!
        page.properties.keys shouldBe setOf("items", "nextCursor")
        page.properties["items"]!!.items.`$ref` shouldBe "#/components/schemas/DayResponse"
    }

    @Test
    fun `the longest display name identity takes is no longer than the archive's largest page was measured with`() {
        // Every day of the archive carries the partner's name (ADR-0036 question 5), and
        // DaysFeedTest measures the largest page with a name of this length. Identity's limit
        // is internal to it; the contract states it, and a longer one is a page nobody measured.
        val displayName = api.components.schemas["RegisterRequest"]!!.properties["displayName"]!!
        displayName.maxLength.shouldNotBeNull() shouldBeLessThanOrEqual ArchivePageBudget.MEASURED_DISPLAY_NAME_LENGTH
    }

    @Test
    fun `on-this-day takes nothing but its path and returns days and no cursor`() {
        val onThisDay = api.paths["/api/v1/bonds/{bondId}/on-this-day"]!!.get
        onThisDay.operationId shouldBe "onThisDay"
        onThisDay.parameters.filter { it.`in` == "query" } shouldBe emptyList()
        onThisDay.responses.keys shouldContainAll listOf("200", "401", "404", "429")
        onThisDay.responses.keys shouldNotContain "422"
        onThisDay.responses["200"]!!
            .content["application/json"]!!
            .schema.`$ref` shouldBe "#/components/schemas/OnThisDayResponse"
        val page = api.components.schemas["OnThisDayResponse"]!!
        page.properties.keys shouldBe setOf("items")
        page.properties["items"]!!.items.`$ref` shouldBe "#/components/schemas/DayResponse"
        val told = onThisDay.description.shouldNotBeNull()
        told shouldContain "earlier year"
        told shouldContain "29 February"
        told shouldContain "takes no writes"
    }

    @Test
    fun `milestones are what was reached and the next threshold, and no count`() {
        val milestones = api.paths["/api/v1/bonds/{bondId}/milestones"]!!.get
        milestones.operationId shouldBe "milestones"
        milestones.parameters.filter { it.`in` == "query" } shouldBe emptyList()
        milestones.responses.keys shouldContainAll listOf("200", "401", "404", "429")
        milestones.responses.keys shouldNotContain "422"
        milestones.responses["200"]!!
            .content["application/json"]!!
            .schema.`$ref` shouldBe "#/components/schemas/MilestonesResponse"
        // FR-076, H-M2, H-M3, Q-M3: thresholds and dates, nothing about a member, and no count.
        api.components.schemas["MilestonesResponse"]!!
            .properties.keys shouldBe setOf("achieved", "next")
        api.components.schemas["AchievedMilestoneResponse"]!!
            .properties.keys shouldBe setOf("threshold", "achievedOn")
        api.components.schemas["NextMilestoneResponse"]!!
            .properties.keys shouldBe setOf("threshold")
        val told = milestones.description.shouldNotBeNull()
        // ADR-0041 decision 14: the thresholds in words, a mirror of Milestones.THRESHOLDS.
        told shouldContain "7, 30, 100 and 365"
        told shouldContain "takes no writes"
    }

    @Test
    fun `search documents q as required and bounded, the feed's limit and cursor, its 400, 422 and 404, and the feed's page`() {
        val search = api.paths["/api/v1/bonds/{bondId}/search"]!!.get
        search.operationId shouldBe "search"
        search.responses.keys shouldContainAll listOf("200", "400", "401", "404", "422", "429")
        // An undecodable q is UndecodableParameterFilter's 400 MALFORMED_REQUEST, a problem like every refusal.
        search.responses["400"]!!
            .content[MediaType.APPLICATION_PROBLEM_JSON_VALUE]!!
            .schema.`$ref` shouldBe "#/components/schemas/ProblemDetail"
        search.parameters.single { it.name == "q" }.description shouldContain "400 MALFORMED_REQUEST"
        search.responses.keys shouldNotContain "406"
        search.responses.keys shouldNotContain "409"
        search.requestBody.shouldBeNull()

        val query = search.parameters.filter { it.`in` == "query" }.associateBy { it.name }
        query.keys shouldBe setOf("q", "limit", "cursor")
        query.getValue("q").required shouldBe true
        query.getValue("q").schema.minLength shouldBe 1
        query.getValue("q").schema.maxLength shouldBe 1024
        query
            .getValue("limit")
            .schema.maximum
            .toInt() shouldBe 50
        query
            .getValue("limit")
            .schema.default
            .toString() shouldBe "20"
        query.getValue("cursor").schema.maxLength shouldBe 32
        search.responses["200"]!!
            .content["application/json"]!!
            .schema.`$ref` shouldBe "#/components/schemas/DaysResponse"

        val told = search.description.shouldNotBeNull()
        told shouldContain "Only a null `nextCursor` means the end"
        told shouldContain "Whole words"
        told shouldContain "never echoed"
        told shouldContain "no count"
    }

    @Test
    fun `one day of the archive is the feed's day, at a date, with the one 404 and no parameter but its path`() {
        val day = api.paths["/api/v1/bonds/{bondId}/days/{date}"]!!.get
        day.operationId shouldBe "day"
        day.requestBody.shouldBeNull()
        // The body is a `Representation<DayResponse>` in the handler: the document must say DayResponse, not the holder.
        day.responses["200"]!!
            .content[MediaType.APPLICATION_JSON_VALUE]!!
            .schema.`$ref` shouldBe "#/components/schemas/DayResponse"
        api.paths["/api/v1/bonds/{bondId}/days"]!!
            .get.responses["200"]!!
            .content[MediaType.APPLICATION_JSON_VALUE]!!
            .schema.`$ref` shouldBe "#/components/schemas/DaysResponse"
        api.components.schemas.keys
            .filter { it.contains("Representation") }
            .shouldBeEmpty()
        day.responses.keys shouldContainAll listOf("200", "304", "401", "404", "429")
        day.responses.keys shouldNotContain "422"
        day.responses.keys shouldNotContain "409"
        day.parameters.filter { it.`in` == "query" }.shouldBeEmpty()
        val date = day.parameters.single { it.`in` == "path" && it.name == "date" }
        date.required shouldBe true
        date.schema.format shouldBe "date"
        (
            api.components.schemas["ProblemDetail"]!!
                .properties["code"]!!
                .enum
                .map { it.toString() }
        ) shouldContain "DAY_NOT_FOUND"
    }

    @Test
    fun `both archive reads document the ETag they send, the If-None-Match they take and the 304 they may answer`() {
        // The header is set on the ResponseEntity and the 304 is the framework's, so springdoc sees neither.
        val reads = listOf("/api/v1/bonds/{bondId}/days", "/api/v1/bonds/{bondId}/days/{date}").map { api.paths[it]!!.get }

        reads.forEach { read ->
            withClue(read.operationId) {
                read.responses["200"]!!.headers.orEmpty() shouldContainKey "ETag"
                // One media type comes back, whatever the mapping accepts: `produces` also names `application/*+json`, so that
                // an `Accept` of a `+json` type is answered and not refused, and what answers it is still `application/json`.
                read.responses["200"]!!.content.keys shouldBe setOf("application/json")
                val condition = read.parameters.single { it.name == HttpHeaders.IF_NONE_MATCH }
                condition.`in` shouldBe "header"
                condition.required shouldBe false
                val notModified = read.responses["304"].shouldNotBeNull()
                notModified.content.shouldBeNull()
                notModified.headers.orEmpty() shouldContainKey "ETag"
                // Nothing here is a condition on a write.
                read.parameters.map { it.name } shouldNotContain HttpHeaders.IF_MATCH
                read.responses.keys shouldNotContain "412"
                read.responses.keys shouldNotContain "428"
            }
        }
        // And only they: a 304 documented on a route that cannot give one is a branch a client is generated to handle for nothing.
        operations().filter { (_, op) -> op.responses.containsKey("304") }.map { it.second.operationId } shouldContainExactlyInAnyOrder
            listOf("days", "day")
    }

    @Test
    fun `a favourite is put and deleted with no body, and removing a locked mark can conflict`() {
        val favourite = api.paths["/api/v1/entries/{entryId}/favourite"]!!
        favourite.readOperationsMap().keys.map { it.name } shouldContainExactlyInAnyOrder listOf("PUT", "DELETE")

        // 409 is ENTRY_NOT_REVEALED and ENTRY_IMMUTABLE. No body goes in, so no 400 or 422, and none comes out.
        favourite.put.operationId shouldBe "favouriteEntry"
        favourite.put.responses.keys shouldContainExactlyInAnyOrder listOf("204", "401", "403", "404", "409", "429", "500")
        favourite.put.requestBody shouldBe null
        favourite.put.responses["204"]!!.content shouldBe null
        favourite.put.parameters.map { it.name } shouldBe listOf("entryId")

        // Absent is success; a row held by another transaction is FAVOURITE_BUSY.
        favourite.delete.operationId shouldBe "unfavouriteEntry"
        favourite.delete.responses.keys shouldContainExactlyInAnyOrder listOf("204", "401", "403", "404", "409", "429", "500")
        favourite.delete.description shouldContain "409 FAVOURITE_BUSY"
        // …and never on a tombstone, where it would tell a withdrawal in flight from a deletion by hand (ADR-0037 decision 6).
        favourite.delete.description shouldContain "never given for an entry the caller is shown as erased"
        favourite.delete.responses["204"]!!.content shouldBe null
        favourite.delete.parameters.map { it.name } shouldBe listOf("entryId")
    }

    @Test
    fun `an entry says whether its reader kept it, and the shape for an entry never shown has no such field`() {
        val full =
            api.components.schemas["EntryResponse"]!!
                .allOf
                .last()
        full.properties["favourited"]!!.types shouldBe setOf("boolean")
        full.required shouldContain "favourited"
        api.components.schemas["LockedEntryResponse"]!!
            .allOf
            .last()
            .properties.keys shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
    }

    @Test
    fun `a reaction is put as one required type of the five and deleted with no body, and removing a locked one can conflict`() {
        val reaction = api.paths["/api/v1/entries/{entryId}/reaction"]!!
        reaction.readOperationsMap().keys.map { it.name } shouldContainExactlyInAnyOrder listOf("PUT", "DELETE")

        // 409 is REACTION_ON_OWN_ENTRY, ENTRY_IMMUTABLE and BOND_ARCHIVED. A body goes in, so 400 and 422; none comes out.
        reaction.put.operationId shouldBe "reactToEntry"
        reaction.put.responses.keys shouldContainExactlyInAnyOrder listOf("204", "400", "401", "403", "404", "409", "422", "429", "500")
        reaction.put.responses["204"]!!.content shouldBe null
        reaction.put.parameters.map { it.name } shouldBe listOf("entryId")
        reaction.put.requestBody.required shouldBe true
        reaction.put.requestBody.content.keys shouldBe setOf(MediaType.APPLICATION_JSON_VALUE)
        // 415 is not in the status list; the description says it, with the body's other rules, as the ending routes' does.
        reaction.put.description shouldContain "415"

        // The body is the one key, never null, and its values are the set itself: no second list to drift.
        val body = api.components.schemas["ReactionRequest"]!!
        body.properties.keys shouldBe setOf("type")
        body.required shouldBe listOf("type")
        val type = body.properties["type"]!!
        (type.types ?: setOf(type.type)) shouldBe setOf("string")
        type.enum.map { it.toString() } shouldBe REACTION_TYPES

        // Absent is success, on a tombstone too; a row held by another transaction is REACTION_BUSY; an ended bond is BOND_ARCHIVED.
        reaction.delete.operationId shouldBe "unreactToEntry"
        reaction.delete.responses.keys shouldContainExactlyInAnyOrder listOf("204", "401", "403", "404", "409", "429", "500")
        reaction.delete.description shouldContain "409 REACTION_BUSY"
        reaction.delete.description shouldContain "Neither is given for an entry the caller is shown as erased"
        reaction.delete.responses["204"]!!.content shouldBe null
        reaction.delete.requestBody shouldBe null
        reaction.delete.parameters.map { it.name } shouldBe listOf("entryId")
    }

    @Test
    fun `an entry carries the reaction on it, one of the five or null and always sent, and nothing says when it was left`() {
        val full =
            api.components.schemas["EntryResponse"]!!
                .allOf
                .last()
        val reaction = full.properties["reaction"]!!
        reaction.types shouldBe setOf("string", "null")
        // `null` is listed: by JSON Schema an enum that leaves it out forbids it, whatever `type` says.
        reaction.enum.map { it?.toString() } shouldBe REACTION_TYPES + null
        // Required though nullable, as `text` is: "no reaction" is `null`, not a field the server left out.
        full.required shouldContain "reaction"
        // The whole of what an entry says: a field added for a reaction's time, or its maker, fails here.
        full.properties.keys shouldBe
            setOf(
                "id",
                "bondId",
                "date",
                "authorMemberId",
                "text",
                "status",
                "createdAt",
                "intendedAt",
                "favourited",
                "reaction",
                "image",
                "voice",
            )
        // No schema of a reaction beyond its type exists to carry more.
        api.components.schemas.keys
            .filter { "eaction" in it } shouldBe listOf("ReactionRequest")
    }

    /** Phase 4 D2, ADR-0048: `{id, status, url?, width?, height?}`, and nowhere on a locked entry (BR-8). */
    @Test
    fun `an entry's photo is an id, a status and, once shown, a URL and a size, every field always sent`() {
        val image = api.components.schemas["EntryImageResponse"]!!
        image.properties.keys shouldBe setOf("id", "status", "url", "width", "height")
        image.required.toSet() shouldBe image.properties.keys
        image.properties["status"]!!.enum.map { it.toString() } shouldBe listOf("READY", "PROCESSING")
        listOf("url", "width", "height").forEach { image.properties[it]!!.types shouldContain "null" }

        val full =
            api.components.schemas["EntryResponse"]!!
                .allOf
                .last()
        full.required shouldContain "image"
        full.properties["image"]!!.oneOf.map { it.`$ref` ?: it.types.single() } shouldBe
            listOf("#/components/schemas/EntryImageResponse", "null")
        // BR-8: the locked shape has no field a photo could hide in.
        api.components.schemas["LockedEntryResponse"]!!
            .allOf
            .last()
            .properties.keys shouldBe setOf("authorMemberId", "status")
    }

    /** Phase 4 D3, ADR-0049: `{id, status, url?, durationMs?}`, and nowhere on a locked entry (BR-8). */
    @Test
    fun `an entry's voice note is an id, a status and, once playable, a URL and a length, every field always sent`() {
        val voice = api.components.schemas["EntryVoiceResponse"]!!
        voice.properties.keys shouldBe setOf("id", "status", "url", "durationMs")
        voice.required.toSet() shouldBe voice.properties.keys
        voice.properties["status"]!!.enum.map { it.toString() } shouldBe listOf("READY", "PROCESSING")
        listOf("url", "durationMs").forEach { voice.properties[it]!!.types shouldContain "null" }
        voice.description.shouldNotBeNull() shouldContain "no moderation"

        val full =
            api.components.schemas["EntryResponse"]!!
                .allOf
                .last()
        full.required shouldContain "voice"
        full.properties["voice"]!!.oneOf.map { it.`$ref` ?: it.types.single() } shouldBe
            listOf("#/components/schemas/EntryVoiceResponse", "null")
    }

    @Test
    fun `the two reaction operations say what the schema cannot`() {
        val reaction = api.paths["/api/v1/entries/{entryId}/reaction"]!!
        val set = reaction.put.description.shouldNotBeNull()
        set shouldContain "The other member is shown it"
        set shouldContain "replaces"
        set shouldContain "`409 REACTION_ON_OWN_ENTRY`"
        set shouldContain "`409 ENTRY_IMMUTABLE`"
        set shouldContain "`409 BOND_ARCHIVED`"
        set shouldContain "never shown"
        set shouldContain "`422`"
        set shouldContain "`400`"

        val remove = reaction.delete.description.shouldNotBeNull()
        remove shouldContain "no reaction is success"
        remove shouldContain "never shown"
        remove shouldContain "`409 BOND_ARCHIVED`"
        remove shouldNotContain "Allowed on a bond that has ended"

        api.components.schemas["EntryResponse"]!!
            .allOf
            .last()
            .properties["reaction"]!!
            .description
            .shouldNotBeNull() shouldContain "left by the member who did not write"
    }

    @Test
    fun `partnerEntry is one of two branches, discriminated on status with no value naming two`() {
        // springdoc resolves the sealed interface to a `oneOf` of its
        // branches; OpenApiConfiguration adds the `discriminator`, which is
        // what a generated client builds its sealed types from (doc 06 §2).
        // A discriminator maps a value to exactly one schema, so the
        // branches' `status` enums must be disjoint. There were three until
        // the owner's ruling of 2026-10-21 (ADR-0032 question 2): a partner's
        // entry erased before it was revealed is now `null`, and its
        // `ErasedEntryResponse` and `REMOVED` are gone from the document.
        val today = api.components.schemas["TodayResponse"]!!
        val partnerEntry = today.properties["partnerEntry"]!!
        val branches = listOf("EntryResponse", "LockedEntryResponse")
        api.components.schemas.keys shouldNotContain "ErasedEntryResponse"

        partnerEntry.oneOf.mapNotNull { it.`$ref` } shouldContainExactlyInAnyOrder branches.map { "#/components/schemas/$it" }
        partnerEntry.discriminator.shouldNotBeNull()
        partnerEntry.discriminator.propertyName shouldBe "status"
        partnerEntry.discriminator.mapping shouldBe
            mapOf(
                "SUBMITTED" to "#/components/schemas/EntryResponse",
                "REVEALED" to "#/components/schemas/EntryResponse",
                "DELETED" to "#/components/schemas/EntryResponse",
                "LOCKED" to "#/components/schemas/LockedEntryResponse",
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
        // of the objects, and a strict client rejects the ordinary first
        // response of every day.
        val today = api.components.schemas["TodayResponse"]!!

        listOf("myEntry", "partnerEntry").forEach { name ->
            withClue(name) {
                val branches = today.properties[name]!!.oneOf.shouldNotBeNull()
                branches.filter { it.`$ref` == null }.map { it.types } shouldBe listOf(setOf("null"))
            }
        }
        // Exactly the two objects and the null: nothing else was admitted.
        today.properties["partnerEntry"]!!.oneOf.size shouldBe 3
    }

    @Test
    fun `the narrow partnerEntry branch carries an author and a status, both required, and nothing else`() {
        // BR-8: the type has nowhere to put anything else.
        val fields =
            api.components.schemas["LockedEntryResponse"]!!
                .allOf
                .last()
        fields.properties.keys shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        fields.required shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        fields.properties["status"]!!.enum shouldBe listOf("LOCKED")
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

    @Test
    fun `today carries the day's prompt, an id and a text or null, and nothing about who answered it`() {
        api.components.schemas["TodayResponse"]!!
            .properties.keys shouldContain "prompt"
        api.components.schemas["TodayPromptResponse"]!!
            .properties.keys shouldBe setOf("id", "text")
        api.components.schemas["TodayPromptResponse"]!!
            .properties["id"]!!
            .format shouldBe "uuid"
        val prompt = api.components.schemas["TodayResponse"]!!.properties["prompt"]!!
        // Nullable, as myEntry is: springdoc gives a nullable $ref a null branch.
        prompt.oneOf.orEmpty().any { it.types?.contains("null") == true } shouldBe true
        prompt.description shouldContain "takes no writes"
    }

    @Test
    fun `an entry is written with an optional prompt id that no shape of an entry ever carries back`() {
        val sent = api.components.schemas["SubmitEntryRequest"]!!
        sent.properties["promptId"]!!.format shouldBe "uuid"
        sent.required.orEmpty() shouldNotContain "promptId"
        val patched = api.components.schemas["PatchEntryRequest"]!!
        patched.properties.keys shouldNotContain "promptId"
        for (shape in listOf("EntryResponse", "LockedEntryResponse")) {
            api.components.schemas[shape]!!.toString() shouldNotContain "promptId"
        }
    }

    private companion object {
        /** FR-048's set, written out: the contract's enum, so a change here is a breaking change to a generated client. */
        val REACTION_TYPES = listOf("HEART", "THANK_YOU", "SMILE", "MOVED", "ME_TOO")
        val GENERATED = File("build/openapi/openapi.json")
        val COMMITTED = File("../contracts/openapi.json")
    }
}
