package com.moyi.contracts

import com.moyi.common.security.ClientContext
import com.moyi.common.security.CurrentUser
import com.moyi.common.security.SecurityConfiguration
import com.moyi.common.web.ErrorCode
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus

/**
 * What the generated document says beyond what springdoc can read off the
 * controllers (ADR-0024).
 *
 * Three things are added by rule rather than by annotation, because each is
 * a property of the whole API and an annotation per endpoint would be one
 * more place for it to drift:
 *
 * - **The bearer.** Everything is called with a token unless it is one of the
 *   public auth endpoints in `SecurityConfiguration` (doc 06 §1); those get an
 *   empty security requirement, which is OpenAPI's way of saying "none".
 * - **The error responses.** Doc 06 §2: one shape, `ProblemDetail`, whose
 *   `code` is `ErrorCode` enumerated — the client generates a sealed class
 *   from it, and an unhandled code is a compile error there. 429 and 500 can
 *   happen to anything; 400 and 422 to anything with a body; 401 and 403 to
 *   anything behind the bearer; 404 to anything that names a resource by a
 *   path parameter — doc 06 §2's "not found or not permitted to know it
 *   exists", which the first document omitted for `DELETE /sessions/{id}`
 *   until the review of #35 noticed a generated client could not model it.
 * - **The two argument-resolver types.** `CurrentUser` comes from the token
 *   and `ClientContext` from the socket; documented as query parameters they
 *   would generate a client that sends them.
 * - **The `ETag` on a versioned resource.** A success response whose body is
 *   one of [VERSIONED_RESOURCE_SCHEMAS] carries an opaque representation `ETag`,
 *   which is what `If-Match` compares against (doc 06 §1). The header is set
 *   on the `ResponseEntity`, so springdoc cannot see it, and a client
 *   generated without it has no typed way to keep the value it must send
 *   back — raised by the review of PR #37. Keyed on the response schema
 *   rather than a list of paths, so the `PATCH` that arrives with slice B4
 *   is documented by having a body, not by somebody remembering.
 * - **`If-Match`, required.** The handler takes the header as optional so that
 *   an absent condition is our `428` rather than Spring's `400`, and springdoc
 *   copies that optionality into the document — where it means something else
 *   entirely: that a client may leave it out. [CONDITIONAL_OPERATIONS] is the
 *   one list of operations that demand a condition, and it now says so in both
 *   places. Raised by the review of PR #40.
 * - **`Idempotency-Key`, required.** `@Idempotent` (`common:web`) is a marker
 *   annotation on the handler method, not a Spring-visible parameter, so
 *   springdoc has no way to know the header exists at all — unlike `If-Match`,
 *   there is nothing here to correct the optionality of, only a parameter to
 *   add outright. [IDEMPOTENT_OPERATIONS] names the operations that carry
 *   `@Idempotent`, mirroring [CONDITIONAL_OPERATIONS]'s own shape. Fix round
 *   1, C2: found alongside the missing `409` below — a generated client had
 *   no header to send at all, so every call to `POST /bonds/{bondId}/entries`
 *   it made would have been refused as `422 VALIDATION_FAILED`.
 * - **The `partnerEntry` discriminator.** `gratitude.web.TodayResponse.partnerEntry`
 *   is a Kotlin sealed interface's `oneOf` — springdoc already splits it into
 *   its three branches,
 *   [EntryResponse][com.moyi.gratitude.web.EntryResponse],
 *   [LockedEntryResponse][com.moyi.gratitude.web.LockedEntryResponse] and
 *   [ErasedEntryResponse][com.moyi.gratitude.web.ErasedEntryResponse], on its
 *   own, but adds no `discriminator`, so a generated client has to try each
 *   shape structurally to learn which one it received rather than read one
 *   field. [discriminatePartnerEntry] adds one, keyed on `status`, which
 *   every branch already carries. Deferred by Task 8 (`PartnerEntryResponse`'s
 *   own KDoc) to whichever task next regenerated the document.
 * - **`partnerEntry` is nullable, and says so.** The field is `null` until
 *   the partner has written. springdoc gives a nullable `$ref` its
 *   `{"type": "null"}` branch (it does for `myEntry`) but not a nullable
 *   sealed interface's `oneOf`, which left the document claiming the field is
 *   always one of the three objects. [admitAbsentPartnerEntry] adds the
 *   missing branch.
 *
 * **F9 (whole-branch review) — three things a generated client could not
 * previously do at all:**
 * - **`text`/`name`'s documented `pattern` is now ECMA-262, not Java.**
 *   [NOT_ONLY_SPACE_DOCUMENTED_PATTERN] replaces the literal `(?sU).*\S.*`
 *   this document used to publish for both `SubmitEntryRequest.text` and
 *   `CreateBondRequest.name` — valid to `java.util.regex.Pattern`, because
 *   `bond.web.NOT_ONLY_SPACE` is a Java inline-flag pattern the *server*
 *   enforces, but `new RegExp("(?sU).*\\S.*")` is a JS `SyntaxError: Invalid
 *   group`, and Python's `re`/.NET's `Regex` reject the `U` flag the same
 *   way. Only the *documented* pattern changes here; the server's own
 *   enforcement (`bond.web.NOT_ONLY_SPACE`, `gratitude.web.ValidEntryText`)
 *   is untouched. See [NOT_ONLY_SPACE_DOCUMENTED_PATTERN]'s own KDoc for the
 *   equivalence.
 * - **The `partnerEntry` discriminator property is now `required`.**
 *   [discriminatePartnerEntry] used to add a `discriminator` keyed on
 *   `status` without ever marking `status` required on any branch's own
 *   schema — legal JSON Schema, but OpenAPI requires a discriminator's own
 *   property to be `required`, and several generators (and every strict
 *   validator) drop a discriminator that is not. [requireDiscriminatorProperty]
 *   closes that on every branch:
 *   [EntryResponse][com.moyi.gratitude.web.EntryResponse],
 *   [LockedEntryResponse][com.moyi.gratitude.web.LockedEntryResponse] and
 *   [ErasedEntryResponse][com.moyi.gratitude.web.ErasedEntryResponse].
 * - **`Idempotency-Replayed` is now a declared response header.**
 *   The handler sets it on every replay (from
 *   [IdempotentOutcome][com.moyi.common.web.idempotency.IdempotentOutcome]'s
 *   `wasReplayed`), but nothing in this document said so, so a generated
 *   client had no typed way to tell a replay from a fresh success. A replay
 *   is **not** a stored response: the resource is re-read and rendered from
 *   its current state (spec §5.4), which the header descriptions below say.
 *   [requireIdempotencyKey] adds it to every success response of
 *   [IDEMPOTENT_OPERATIONS], mirroring how [documentETags] adds `ETag`.
 *
 * Which codes a *particular* operation returns is doc 06 §3's table, not this
 * document: the contract a generated client is built from is the shape.
 */
@Configuration
class OpenApiConfiguration {
    init {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser::class.java, ClientContext::class.java)
    }

    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("Moyi API").version("v1").description("The contract the Moyi client is generated from (doc 06)."))
            .components(
                Components()
                    .addSecuritySchemes(BEARER, SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")),
            ).addSecurityItem(SecurityRequirement().addList(BEARER))

    /**
     * A customizer rather than part of the [openApi] bean: springdoc rebuilds
     * `components.schemas` from what it finds on the controllers and drops
     * anything that was there before, so the two schemas have to be added
     * after that pass.
     */
    @Bean
    fun problemResponsesAndPublicEndpoints(): OpenApiCustomizer =
        OpenApiCustomizer { api ->
            api.components.addSchemas(PROBLEM_DETAIL, problemDetail()).addSchemas(FIELD_VIOLATION, fieldViolation())
            api.paths.forEach { (path, item) ->
                item.readOperationsMap().forEach { (method, operation) ->
                    val public = method == PathItem.HttpMethod.POST && path in SecurityConfiguration.PUBLIC_AUTH_ENDPOINTS
                    if (public) operation.security = emptyList()
                    statusesFor(operation, public).forEach { status ->
                        operation.responses.addApiResponse(status.value().toString(), problemResponse(status))
                    }
                    documentETags(operation)
                    requireIfMatch(operation)
                    requireIdempotencyKey(operation)
                }
            }
            documentEntryTextLimits(api)
            documentBondNamePattern(api)
            discriminatePartnerEntry(api)
            admitAbsentPartnerEntry(api)
            requireDiscriminatorProperty(api)
        }

    /**
     * States FR-041's octet cap on the wire schema, declaratively — fix
     * round 2, N1. `gratitude.web.ValidEntryText` asks the domain
     * (`EntryText.of`) instead of restating the limit as a Bean Validation
     * annotation, which is the right call for *enforcement* (fix round 1,
     * C1 — a restated `@Size` measured the wrong unit and let an over-length
     * body reach the domain as an uncaught `500`) but left springdoc with
     * nothing on the field to read at all: `SubmitEntryRequest.text`
     * generated as a bare `{"type":"string"}`, so a generated client had no
     * client-side hint and every over-length body became a round trip the
     * server was always going to refuse anyway.
     *
     * `gratitude.domain.EntryText`'s two constants are `internal` to that
     * module, which `contracts` does not depend on (the class KDoc's own
     * "nothing here names a controller" — the same reason [CONFLICTING_OPERATIONS]
     * and [CONDITIONAL_OPERATIONS] are literal operation ids rather than
     * typed references), so [ENTRY_TEXT_MAX_OCTETS] and [ENTRY_TEXT_MAX_GRAPHEMES]
     * below are literals that mirror those constants rather than references
     * to them.
     *
     * **`maxLength` states the octet cap, not the grapheme cap, and the two
     * are not the same number.** OpenAPI's `maxLength` counts UTF-16
     * characters (the same unit `@Size` used, and the same unit mismatch
     * fix round 1, C1 found) — a body of 501 plain ASCII graphemes is over
     * FR-041's real, 500-grapheme limit while comfortably under a
     * `maxLength: 8192` hint, and a body of a few thousand 4-byte-UTF-8
     * emoji can be under `maxLength: 8192` while over the octet cap. The
     * hint is therefore a client-side backstop against a clearly oversized
     * body, not a promise that everything under it will be accepted — the
     * description says so, and [EntryText.of] (`gratitude`, not this
     * module) remains the one place both limits are actually enforced.
     */
    private fun documentEntryTextLimits(api: OpenAPI) {
        val text =
            api.components.schemas[SUBMIT_ENTRY_REQUEST]
                ?.properties
                ?.get(ENTRY_TEXT_PROPERTY) ?: return
        text.minLength = 1
        text.maxLength = ENTRY_TEXT_MAX_OCTETS
        text.pattern = NOT_ONLY_SPACE_DOCUMENTED_PATTERN
        text.description =
            "FR-041: at least one non-whitespace character (Unicode-aware, so a lone non-breaking space does " +
            "not count either), at most $ENTRY_TEXT_MAX_OCTETS UTF-8 octets and at most " +
            "$ENTRY_TEXT_MAX_GRAPHEMES user-perceived characters (graphemes, not UTF-16 code units — an " +
            "emoji sequence can be one grapheme and several of those). `maxLength` here states the octet " +
            "cap in UTF-16 characters, which is not the same unit as either real limit and both are wider " +
            "than this hint suggests for multi-byte text: treat it as a coarse client-side backstop, not a " +
            "guarantee. The server enforces both limits exactly and is the only authority on whether a " +
            "given body is accepted."
    }

    private fun statusesFor(
        operation: Operation,
        public: Boolean,
    ): List<HttpStatus> =
        buildList {
            if (operation.requestBody != null) addAll(listOf(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY))
            if (!public) addAll(listOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN))
            if (operation.parameters.orEmpty().any { it.`in` == PATH_PARAMETER }) add(HttpStatus.NOT_FOUND)
            if (operation.operationId in CONFLICTING_OPERATIONS) add(HttpStatus.CONFLICT)
            if (operation.operationId in CONDITIONAL_OPERATIONS) {
                addAll(listOf(HttpStatus.PRECONDITION_FAILED, HttpStatus.PRECONDITION_REQUIRED))
            }
            if (operation.parameters.orEmpty().any { it.`in` == PATH_PARAMETER && it.name == "code" }) {
                add(HttpStatus.UNPROCESSABLE_ENTITY)
            }
            add(HttpStatus.TOO_MANY_REQUESTS)
            add(HttpStatus.INTERNAL_SERVER_ERROR)
        }

    /**
     * Declares the `ETag` on every success response that returns a versioned
     * resource. Idempotent, and silent when there is none to declare.
     */
    private fun documentETags(operation: Operation) {
        operation.responses
            .filterKeys { it.startsWith("2") }
            .values
            .filter { response ->
                response.content
                    ?.values
                    ?.any { it.schema?.`$ref` in VERSIONED_RESOURCE_REFS } == true
            }.forEach { response ->
                response.addHeaderObject(
                    ETAG,
                    Header()
                        .description(
                            "Opaque representation validator (RFC 9110 §8.8.3). " +
                                "Echo the entire value as `If-Match` when updating (doc 06 §1).",
                        ).schema(StringSchema()),
                )
            }
    }

    /**
     * Marks `If-Match` required on the operations that demand one.
     *
     * The handler declares the header `required = false` on purpose, so that an
     * absent condition is *our* `428` with an `ErrorCode` rather than Spring's
     * bare `400` — but springdoc reads that declaration literally and a client
     * generated from it offers the call without the header, which cannot
     * succeed. The document states the rule the API actually enforces. Raised
     * by the review of PR #40.
     */
    private fun requireIfMatch(operation: Operation) {
        if (operation.operationId !in CONDITIONAL_OPERATIONS) return
        operation.parameters.orEmpty().filter { it.name == IF_MATCH && it.`in` == HEADER_PARAMETER }.forEach {
            it.required = true
        }
    }

    /**
     * Adds `Idempotency-Key` outright to every `@Idempotent` operation —
     * see the class KDoc's own bullet for why this is an addition rather
     * than, as [requireIfMatch] does for `If-Match`, a correction of what
     * springdoc already found.
     *
     * **Also adds the `Idempotency-Replayed` response header** to every
     * success response of the same operations (F9, whole-branch review —
     * folded into this function rather than kept a separate one, to stay
     * under detekt's `TooManyFunctions` threshold the way
     * [discriminatePartnerEntry]'s own KDoc explains for its sibling
     * functions).
     * The handler sets that header on every replay and never on a fresh response, but
     * nothing before this declared it anywhere in the document, so a
     * generated client had no typed field to read it from — the same gap
     * [documentETags] closes for `ETag`, and added the same way: the header
     * is set on the response object directly, not through anything springdoc
     * can introspect from the handler's own return type.
     */
    private fun requireIdempotencyKey(operation: Operation) {
        if (operation.operationId !in IDEMPOTENT_OPERATIONS) return
        operation.addParametersItem(
            Parameter()
                .`in`(HEADER_PARAMETER)
                .name(IDEMPOTENCY_KEY)
                .required(true)
                .description(
                    "A client-chosen key, unique per retried request (doc 06 §1). A replay of the same key with the " +
                        "same request returns the first attempt's status and the same resource, re-read and rendered " +
                        "from its current state — never a stored copy. A replay is authorised as a read of the " +
                        "result: a caller who may still read it gets it back even if a new write would now be " +
                        "refused, a caller who may no longer read it gets the read's refusal, and a resource " +
                        "erased since is returned as its tombstone. " +
                        "Only a success is recorded; a request that was refused may be corrected and retried under " +
                        "the same key. The same key with a different method, path or body is 422; the same key " +
                        "while the first attempt is still in flight is 409.",
                ).schema(StringSchema()),
        )
        operation.responses
            .filterKeys { it.startsWith("2") }
            .values
            .forEach { response ->
                response.addHeaderObject(
                    IDEMPOTENCY_REPLAYED_HEADER,
                    Header()
                        .description(
                            "`true` when this response answers a replay of an earlier request under the same " +
                                "Idempotency-Key (doc 06 §1): nothing was written again, and the body is the resource " +
                                "as it stands now. Absent on a fresh response.",
                        ).schema(StringSchema()),
                )
            }
    }

    private fun problemResponse(status: HttpStatus): ApiResponse =
        ApiResponse()
            .description(status.reasonPhrase)
            .content(Content().addMediaType(PROBLEM_JSON, MediaType().schema(Schema<Any>().`$ref`(PROBLEM_DETAIL_REF))))

    /** RFC 9457 as doc 06 §2 specifies it, minus `traceId`, which does not exist until tracing does. */
    private fun problemDetail(): Schema<*> =
        ObjectSchema()
            .description("RFC 9457 problem details (doc 06 §2). Switch on `code`; `detail` is prose and may change.")
            .addProperty("type", StringSchema().format("uri"))
            .addProperty("title", StringSchema())
            .addProperty("status", IntegerSchema())
            .addProperty("detail", StringSchema())
            .addProperty("instance", StringSchema().format("uri"))
            .addProperty("code", StringSchema().apply { enum = ErrorCode.entries.map { it.name } })
            .addProperty("errors", ArraySchema().items(Schema<Any>().`$ref`(FIELD_VIOLATION_REF)))
            .required(listOf("type", "title", "status", "code"))

    private fun fieldViolation(): Schema<*> =
        ObjectSchema()
            .description("One rejected field. Never carries the rejected value.")
            .addProperty("field", StringSchema())
            .addProperty("code", StringSchema())
            .addProperty("message", StringSchema())
            .required(listOf("field", "code", "message"))

    companion object {
        const val BEARER = "bearerAuth"
        const val PROBLEM_DETAIL = "ProblemDetail"
        const val FIELD_VIOLATION = "FieldViolation"
        private const val PROBLEM_DETAIL_REF = "#/components/schemas/$PROBLEM_DETAIL"
        private const val FIELD_VIOLATION_REF = "#/components/schemas/$FIELD_VIOLATION"
        private const val PROBLEM_JSON = "application/problem+json"
        private const val PATH_PARAMETER = "path"
        private const val HEADER_PARAMETER = "header"
        private const val ETAG = "ETag"
        private const val IF_MATCH = "If-Match"

        /** `SubmitEntryRequest`'s schema name and its `text` property — see [documentEntryTextLimits]. */
        private const val SUBMIT_ENTRY_REQUEST = "SubmitEntryRequest"
        private const val ENTRY_TEXT_PROPERTY = "text"

        /** Mirrors `gratitude.domain.EntryText.MAX_OCTETS` — `internal`, and this module does not depend on `gratitude`. */
        private const val ENTRY_TEXT_MAX_OCTETS = 8192

        /** Mirrors `gratitude.domain.EntryText.MAX_GRAPHEMES` — see [ENTRY_TEXT_MAX_OCTETS]'s own note. */
        private const val ENTRY_TEXT_MAX_GRAPHEMES = 500

        /**
         * The operations that answer `409`, by id.
         *
         * A list rather than a rule, because "can this conflict" is a property
         * of an operation's logic and not of its shape — and a wrong entry is a
         * generated client modelling a state the API never returns. `blockBond`
         * is deliberately absent: it is accepted on a bond that has already
         * ended, which is the only thing it could have conflicted with
         * (ADR-0028).
         */
        private val CONFLICTING_OPERATIONS =
            setOf(
                "createBond",
                "createBondInvite",
                "accept",
                "leaveBond",
                "revokeBondInvite",
                "patchBond",
                "replaceMemberSettings",
                // Slice B5. `cancelBondTimezoneChange` and `cancelBondDeletion`
                // are deliberately absent: cancelling is never a conflict, it is
                // either done or there was nothing there (a `404`).
                "proposeBondTimezone",
                "confirmBondTimezone",
                "requestBondDeletion",
                // Fix round 1, C2: BOND_ARCHIVED, DAY_CLOSED, ENTRY_ALREADY_EXISTS
                // and IDEMPOTENCY_KEY_IN_FLIGHT are all 409s this operation can
                // give — the headline behaviour of the slice (BR-2) among them
                // — and none of them was visible to a generated client without
                // this entry.
                "submitEntry",
            )

        /**
         * Operations that require `If-Match` (doc 06 §1) and can therefore answer
         * `412` and `428`.
         *
         * By id, for the same reason the conflict list is: it is a property of
         * the operation's rule rather than of its shape. `replaceMemberSettings`
         * has a body and a path parameter exactly like `patchBond` and takes no
         * condition at all, because nobody else can write that row (ADR-0029).
         */
        private val CONDITIONAL_OPERATIONS = setOf("patchBond")

        /**
         * Operations carrying `@Idempotent` (doc 06 §1), by id — the reason
         * [requireIdempotencyKey] exists at all: springdoc cannot read this
         * off the controller the way it reads an actual `@RequestHeader`.
         */
        private val IDEMPOTENT_OPERATIONS = setOf("submitEntry")

        private const val IDEMPOTENCY_KEY = "Idempotency-Key"
        private const val IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed"

        /**
         * Response schemas whose resource carries a row version, and therefore
         * an `ETag`. One entry per versioned aggregate, not one per endpoint —
         * an operation is covered by what it returns.
         */
        val VERSIONED_RESOURCE_SCHEMAS = setOf("BondResponse")
        private val VERSIONED_RESOURCE_REFS = VERSIONED_RESOURCE_SCHEMAS.map { "#/components/schemas/$it" }.toSet()
    }
}

/**
 * The ECMA-262-compatible *documented* equivalent of `bond.web.NOT_ONLY_SPACE`'s
 * Java-only `(?sU).*\S.*` (F9, whole-branch review — this constant used to be
 * named `NOT_ONLY_SPACE`, live inside [OpenApiConfiguration]'s own companion,
 * and its KDoc claimed it "mirrors `gratitude.web.NOT_ONLY_SPACE`", which an
 * earlier fix round deleted; that mirror pointed at nothing). File-scoped
 * rather than a class member, for the reason [discriminatePartnerEntry]'s own
 * KDoc gives: [OpenApiConfiguration.documentEntryTextLimits] (a class member)
 * and [documentBondNamePattern] (top-level, below) both need it.
 *
 * `(?sU)` is a Java `Pattern` inline-flag group: `s` for `DOTALL` (`.`
 * crosses a newline) and `U` for `UNICODE_CHARACTER_CLASS` (`\S` excludes
 * every Unicode whitespace code point, not just the ASCII ones). Neither
 * flag has an ECMA-262 equivalent an inline group can express — `new
 * RegExp("(?sU).*\\S.*")` is a JS `SyntaxError: Invalid group`, and Python's
 * `re.compile` and .NET's `Regex` reject the `U` flag the same way a
 * generated client's runtime would.
 *
 * `[\s\S]*\S[\s\S]*` says the same thing without either flag: `[\s\S]`
 * matches any character at all (the classic flag-free `DOTALL` substitute,
 * since a character class that is the union of "whitespace" and "not
 * whitespace" excludes nothing), and the lone `\S` in the middle demands at
 * least one non-whitespace character somewhere in the string — exactly
 * `.*\S.*`'s own shape. No `(?U)` counterpart is needed: JS, Python and
 * .NET's `\S` already excludes the Unicode whitespace category `(?U)` exists
 * to add in Java, so the two patterns reject the same strings in every case
 * this constant is used for (a lone non-breaking space, an all-whitespace
 * multi-line value). This is the *documented* pattern only — the server's
 * own enforcement (`bond.web.NOT_ONLY_SPACE`, `gratitude.web.ValidEntryText`)
 * is untouched by this constant.
 */
private const val NOT_ONLY_SPACE_DOCUMENTED_PATTERN = "[\\s\\S]*\\S[\\s\\S]*"

/** `CreateBondRequest`'s schema name and its `name` property — see [documentBondNamePattern]. */
private const val CREATE_BOND_REQUEST = "CreateBondRequest"
private const val BOND_NAME_PROPERTY = "name"

/**
 * `CreateBondRequest.name`'s documented `pattern`, corrected the same way
 * [OpenApiConfiguration.documentEntryTextLimits] corrects
 * `SubmitEntryRequest.text`'s (F9, whole-branch review). A top-level
 * function, not a member — [discriminatePartnerEntry]'s own KDoc gives the
 * reason: the class already sat at detekt's `TooManyFunctions` threshold.
 *
 * Unlike `text`, `name` carries a real `@field:Pattern(regexp =
 * bond.web.NOT_ONLY_SPACE)`, which springdoc already reads and publishes as
 * this schema's `pattern` — so, left alone, this document would keep
 * publishing `bond.web.NOT_ONLY_SPACE`'s own Java-only `(?sU).*\S.*`
 * verbatim. This overrides the *published* value only: `@field:Pattern`'s
 * `regexp` keeps enforcing the original, server-side — nothing here touches
 * `CreateBondRequest` itself.
 */
private fun documentBondNamePattern(api: OpenAPI) {
    val name =
        api.components.schemas[CREATE_BOND_REQUEST]
            ?.properties
            ?.get(BOND_NAME_PROPERTY) ?: return
    name.pattern = NOT_ONLY_SPACE_DOCUMENTED_PATTERN
}

/** `TodayResponse`'s schema name and its `partnerEntry` property — see [discriminatePartnerEntry] and [requireDiscriminatorProperty]. */
private const val TODAY_RESPONSE = "TodayResponse"
private const val PARTNER_ENTRY_PROPERTY = "partnerEntry"
private const val DISCRIMINATOR_PROPERTY = "status"
private const val ENTRY_RESPONSE = "EntryResponse"
private const val LOCKED_ENTRY_RESPONSE = "LockedEntryResponse"
private const val ENTRY_RESPONSE_REF = "#/components/schemas/$ENTRY_RESPONSE"
private const val LOCKED_ENTRY_RESPONSE_REF = "#/components/schemas/$LOCKED_ENTRY_RESPONSE"
private const val ERASED_ENTRY_RESPONSE = "ErasedEntryResponse"
private const val ERASED_ENTRY_RESPONSE_REF = "#/components/schemas/$ERASED_ENTRY_RESPONSE"

/**
 * Gives `TodayResponse.partnerEntry`'s `oneOf` a `discriminator` — the fix
 * `gratitude.web.PartnerEntryResponse`'s own KDoc names, for whichever task
 * next regenerated the document (this one). A top-level function, not a
 * member of [OpenApiConfiguration] — that class already sat at detekt's
 * `TooManyFunctions` threshold, and this rule is no more a property of the
 * *class* than [documentETags] or [requireIfMatch] are; it is called from
 * [OpenApiConfiguration.problemResponsesAndPublicEndpoints] exactly the same
 * way.
 *
 * **springdoc already resolves the `oneOf` itself.** `PartnerEntryResponse`
 * is a Kotlin `sealed interface`, which compiles to a JVM sealed type
 * (`Class.permittedSubclasses`), and swagger-core reads that to split the
 * property into `oneOf: [EntryResponse, ErasedEntryResponse,
 * LockedEntryResponse]` without an
 * annotation on any of them — the KDoc's fear of an *empty* `partnerEntry`
 * schema does not hold against this springdoc version. What springdoc does
 * not add is a `discriminator`: a `oneOf` without one is a set of shapes a
 * generated client must try structurally, one at a time, to find out which
 * branch it received, rather than reading one field and knowing — the same
 * gap a `sealed class` closes on the Kotlin side that this document was
 * leaving open on the wire side.
 *
 * `status` is the discriminator, because all three branches already carry a
 * field of that name and nothing was added to any type to support this:
 * [com.moyi.gratitude.domain.EntryStatus] (`SUBMITTED`, `REVEALED`,
 * `DELETED`) on [com.moyi.gratitude.web.EntryResponse]'s branch, the
 * one-value `LockedEntryStatus.LOCKED` on
 * [com.moyi.gratitude.web.LockedEntryResponse]'s, and the one-value
 * `ErasedEntryStatus.REMOVED` on
 * [com.moyi.gratitude.web.ErasedEntryResponse]'s (Task 8, fix rounds 1–2: a
 * partner's entry erased before it was ever revealed). **`REMOVED` is its
 * own literal precisely so this discriminator can exist**: `DELETED` is
 * already [com.moyi.gratitude.web.EntryResponse]'s wide tombstone, and a
 * discriminator maps a value to exactly one schema. Every value any of the enums
 * can hold is mapped explicitly rather than left to the OpenAPI default (a
 * mapping miss falls back to the value naming a schema directly —
 * `"SUBMITTED"` names no schema in this document, so an unmapped value
 * would be ambiguous exactly where this exists to remove ambiguity).
 *
 * The named `PartnerEntryResponse` schema in `components.schemas` itself
 * stays the empty object springdoc produces for the interface — it is
 * referenced only as each branch's own `allOf` marker, asserts nothing on
 * its own, and rewriting it to a `oneOf` of the three types that already
 * `allOf` it would be a schema that describes itself. Harmless, and left
 * alone.
 */
private fun discriminatePartnerEntry(api: OpenAPI) {
    val partnerEntry =
        api.components.schemas[TODAY_RESPONSE]
            ?.properties
            ?.get(PARTNER_ENTRY_PROPERTY)
    partnerEntry?.takeIf { it.oneOf.orEmpty().isNotEmpty() }?.discriminator =
        Discriminator()
            .propertyName(DISCRIMINATOR_PROPERTY)
            .mapping(
                mapOf(
                    "SUBMITTED" to ENTRY_RESPONSE_REF,
                    "REVEALED" to ENTRY_RESPONSE_REF,
                    "DELETED" to ENTRY_RESPONSE_REF,
                    "LOCKED" to LOCKED_ENTRY_RESPONSE_REF,
                    "REMOVED" to ERASED_ENTRY_RESPONSE_REF,
                ),
            )
}

/**
 * Says `partnerEntry` may be `null` — it is `PartnerEntryResponse?`, absent
 * until the partner has written, and `GET /today` serialises that as
 * `"partnerEntry": null` (`RevealGateTest`). springdoc adds the
 * `{"type": "null"}` branch to a nullable property that is a single `$ref`
 * (`myEntry` has one) but not to a nullable sealed interface's `oneOf`, so
 * without this a strict generated client would reject the most common
 * response there is: the one before the partner writes.
 *
 * The branch is added to the existing `oneOf`, the same shape `myEntry`
 * already has, rather than wrapping the three in an outer `anyOf`. The
 * discriminator still describes every object the field can hold; `null` has
 * no `status` to discriminate on and matches only the null branch.
 * Idempotent, so a second pass over the same document adds nothing.
 */
private fun admitAbsentPartnerEntry(api: OpenAPI) {
    val partnerEntry =
        api.components.schemas[TODAY_RESPONSE]
            ?.properties
            ?.get(PARTNER_ENTRY_PROPERTY)
            ?.takeIf { it.oneOf.orEmpty().isNotEmpty() } ?: return
    if (partnerEntry.oneOf.none { NULL_TYPE in it.types.orEmpty() }) {
        partnerEntry.addOneOfItem(Schema<Any>().apply { types = setOf(NULL_TYPE) })
    }
}

/** JSON Schema's `"type": "null"` (OpenAPI 3.1) — see [admitAbsentPartnerEntry]. */
private const val NULL_TYPE = "null"

/**
 * Marks [DISCRIMINATOR_PROPERTY] — and every other field — `required` on each of
 * [discriminatePartnerEntry]'s branches — F9, whole-branch review.
 * [discriminatePartnerEntry] gives `partnerEntry`'s `oneOf` a `discriminator`
 * keyed on `status`, but none of its three branches
 * ([com.moyi.gratitude.web.EntryResponse],
 * [com.moyi.gratitude.web.LockedEntryResponse],
 * [com.moyi.gratitude.web.ErasedEntryResponse]) declared a `required` array of
 * its own, which left the discriminator property optional in every branch —
 * legal JSON Schema, but the OpenAPI spec requires a discriminator's own
 * property to be `required`, and several generators (and every strict
 * validator) drop a discriminator that is not, which would have silently
 * undone the fix [discriminatePartnerEntry] makes.
 *
 * Each schema is `allOf: [$ref PartnerEntryResponse, {type: object,
 * properties: {...}}]` (a Kotlin data class `allOf`-ing the sealed interface
 * it implements) — `status` lives on the second, inline element of that list,
 * not on the named schema itself, so this reaches into `allOf.last()` rather
 * than the schema's own (empty) `properties`.
 */
private fun requireDiscriminatorProperty(api: OpenAPI) {
    listOf(ENTRY_RESPONSE, LOCKED_ENTRY_RESPONSE, ERASED_ENTRY_RESPONSE).forEach { name ->
        val fields =
            api.components.schemas[name]
                ?.allOf
                ?.lastOrNull() ?: return@forEach
        // Every field of every branch is always present, the discriminator
        // among them. For EntryResponse, `text` above all: it is nullable,
        // and only `required` lets a client tell a tombstone (`"text": null`)
        // from a field that was never sent. For the two narrow branches the
        // author is the one thing they carry besides the discriminator.
        fields.properties
            .orEmpty()
            .keys
            .filter { it !in fields.required.orEmpty() }
            .forEach(fields::addRequiredItem)
    }
}
