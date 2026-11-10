package com.moyi.contracts

import io.swagger.v3.oas.models.media.DateSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.servers.Server
import org.springframework.http.HttpStatus

/**
 * Where the API is. Stated, not taken from the request the document was read with: springdoc's default is the
 * host that asked, which made the committed document say `http://localhost` with "Generated server url" beside it.
 *
 * **The base URL is the host, and every path carries `/api/v1`** (#139). The other reading, a base URL ending in
 * `/api/v1` with paths relative to it, is what doc 06 §1 writes, and it describes the same URLs. It was not taken
 * because every path in the contract, every generated client method, the state map's endpoint names and every
 * smoke probe would change for no change on the wire, and a client whose base URL is configured as the host would
 * then call `/auth/login` at the root. Doc 06 §1 is the line that is stale (ADR-0024, amended 2026-11-08).
 *
 * The first entry is the default a generated client takes. The second is what `docker compose` and `bootRun`
 * serve (the README); a client points at it by its own configuration, as it would at any other.
 */
internal val SERVERS: List<Server> =
    listOf(
        Server().url("https://api.getmoyi.app").description("Production. Every path begins with /api/v1."),
        Server().url("http://localhost:8080").description("Local development: the application run by hand (README)."),
    )

/**
 * The statuses an operation answers by its own logic rather than by its shape, by operation id, as
 * [OpenApiConfiguration]'s conflict list is. Their source is the state map's rows (`docs/state-map/data/`):
 * `scripts/state-map-check` fails when a row answers a status the document does not declare, and when a status
 * the map once listed as a gap in the contract has since been declared. So the list here cannot fall behind a row,
 * and a status it adds is one a row explains.
 *
 * - **410**, a link that was real but is spent or past its time (doc 06 §3.1): `VERIFICATION_TOKEN_EXPIRED` and
 *   `PASSWORD_RESET_TOKEN_EXPIRED`.
 * - **503 `HASHING_CAPACITY_EXCEEDED`**, with `Retry-After: 1` (NFR-005a): every operation that hashes a password.
 * - **503 `CODE_CHECK_UNAVAILABLE`**, with `Retry-After: 60` (owner's ruling 2026-11-09, ADR-0023 amended): `verify`
 *   and `reset` given an `email` and a `code` while the typed-code limit cannot be read. Never for a `token`. And
 *   `checkResetCode` (#169), which takes only a code.
 * - ADR-0047's media statuses: an upload declared too large (413 `MEDIA_QUOTA_EXCEEDED`) or of a type its kind does
 *   not take (415 `MEDIA_TYPE_UNSUPPORTED`); and no storage configured, or none reachable (503
 *   `MEDIA_STORAGE_UNAVAILABLE`).
 *
 * The 401s of login and refresh need no entry: every operation documents 401 (`statusesFor`).
 */
internal fun statusesOf(operationId: String?): List<HttpStatus> = OPERATION_STATUSES[operationId].orEmpty()

private val OPERATION_STATUSES: Map<String, List<HttpStatus>> =
    mapOf(
        "verify" to listOf(HttpStatus.GONE, HttpStatus.SERVICE_UNAVAILABLE),
        "reset" to listOf(HttpStatus.GONE, HttpStatus.SERVICE_UNAVAILABLE),
        "checkResetCode" to listOf(HttpStatus.SERVICE_UNAVAILABLE),
        "register" to listOf(HttpStatus.SERVICE_UNAVAILABLE),
        "login" to listOf(HttpStatus.SERVICE_UNAVAILABLE),
        "createMediaUploadIntent" to
            listOf(HttpStatus.CONTENT_TOO_LARGE, HttpStatus.UNSUPPORTED_MEDIA_TYPE, HttpStatus.SERVICE_UNAVAILABLE),
        "completeMediaUpload" to listOf(HttpStatus.SERVICE_UNAVAILABLE),
    )

/**
 * What a problem response means on one operation, where its status alone says too little: which codes it carries
 * there. Null leaves the status's reason phrase. Prose for a person writing a client; the client switches on
 * `code`, which `ProblemDetail` enumerates.
 */
internal fun describeStatus(
    operationId: String?,
    status: HttpStatus,
    public: Boolean,
): String? =
    STATUS_DESCRIPTIONS[operationId to status]
        ?: if (public && status == HttpStatus.UNAUTHORIZED) PUBLIC_UNAUTHORIZED else null

private const val PUBLIC_UNAUTHORIZED =
    "Unauthorized: `UNAUTHENTICATED`. This route needs no access token, but one that is sent is checked, and an " +
        "expired or unaccepted one is refused. Send no `Authorization` header here."

private const val HASHING_BUSY =
    "Service Unavailable: `HASHING_CAPACITY_EXCEEDED`, with `Retry-After: 1`. Password hashing is momentarily full; " +
        "retry the same request."

private const val CODE_CHECK_DOWN =
    "`CODE_CHECK_UNAVAILABLE`, with `Retry-After: 60`, for an `email` and `code` only: the " +
        "limit on typed codes cannot be read just now, so no code is checked. The code is not spent or counted, and " +
        "the answer is the same whether the address has an account. The link in the same email still works; offer " +
        "it, or try the code again after the wait."

private val STATUS_DESCRIPTIONS: Map<Pair<String, HttpStatus>, String> =
    mapOf(
        ("login" to HttpStatus.UNAUTHORIZED) to
            "Unauthorized: `INVALID_CREDENTIALS` for every refused sign-in (a wrong password, an unknown address, a " +
            "locked account: one answer for all, on purpose); `UNAUTHENTICATED` when an `Authorization` header is " +
            "sent whose token is not accepted. No `WWW-Authenticate`.",
        ("refresh" to HttpStatus.UNAUTHORIZED) to
            "Unauthorized: `REFRESH_TOKEN_INVALID` for a refresh token that is unknown, expired or revoked: sign in " +
            "again. `TOKEN_REUSE_DETECTED` for one already exchanged: every session of its family has been ended, so " +
            "drop the stored credentials and sign in again. `UNAUTHENTICATED` when an `Authorization` header is sent " +
            "whose token is not accepted.",
        ("verify" to HttpStatus.GONE) to
            "Gone: `VERIFICATION_TOKEN_EXPIRED`. The link was real but is past its 24 hours or was already used. Ask " +
            "for a new one. Only a `token` is answered 410; an `email` and `code` never are.",
        ("reset" to HttpStatus.GONE) to
            "Gone: `PASSWORD_RESET_TOKEN_EXPIRED`. The link was real but is past its hour or was already used. Ask " +
            "for a new one. Only a `token` is answered 410; an `email` and `code` never are.",
        ("verify" to HttpStatus.UNPROCESSABLE_ENTITY) to
            "Unprocessable: `VALIDATION_FAILED` for a body that is not exactly one of a `token`, or an `email` and a " +
            "six-digit `code`. `VERIFICATION_TOKEN_INVALID` for a token that matches nothing, and for every refused " +
            "code: no such account, a wrong code, or one expired (15 minutes), burned (5 wrong tries) or spent. One " +
            "answer for all of them, on purpose; ask for a new email.",
        ("reset" to HttpStatus.UNPROCESSABLE_ENTITY) to
            "Unprocessable: `VALIDATION_FAILED` for a body that is not exactly one of a `token`, or an `email` and a " +
            "six-digit `code`, or for the new `password` (`VALID_PASSWORD`, `NOT_BREACHED`); that refusal spends " +
            "nothing. `PASSWORD_RESET_TOKEN_INVALID` for a token that matches nothing, and for every refused code: " +
            "no such account, a wrong code, or one expired (15 minutes), burned (5 wrong tries) or spent. One answer " +
            "for all of them, on purpose; ask for a new email.",
        ("checkResetCode" to HttpStatus.UNPROCESSABLE_ENTITY) to
            "Unprocessable: `VALIDATION_FAILED` for an `email` or `code` of the wrong shape; that spends no try. " +
            "`PASSWORD_RESET_TOKEN_INVALID` for every refused code: no such account, a wrong code, or one expired " +
            "(15 minutes), burned (5 wrong tries) or spent. One answer for all of them, on purpose, and the same " +
            "one `reset-password` gives; a wrong code here is one of the code's 5 tries.",
        ("checkResetCode" to HttpStatus.SERVICE_UNAVAILABLE) to "Service Unavailable: $CODE_CHECK_DOWN",
        ("register" to HttpStatus.SERVICE_UNAVAILABLE) to HASHING_BUSY,
        ("login" to HttpStatus.SERVICE_UNAVAILABLE) to HASHING_BUSY,
        ("verify" to HttpStatus.SERVICE_UNAVAILABLE) to "Service Unavailable: $CODE_CHECK_DOWN",
        ("reset" to HttpStatus.SERVICE_UNAVAILABLE) to "$HASHING_BUSY The link or the code is not spent. Or $CODE_CHECK_DOWN",
    )

/** `ProblemDetail`'s trace extension member: see [traceIdSchema]. */
internal const val TRACE_ID = "traceId"

/** `ProblemDetail`'s extension member for `TIMEZONE_CHANGE_TOO_SOON`: see [allowedFromSchema]. */
internal const val ALLOWED_FROM = "allowedFrom"

/**
 * The W3C trace id of the request that was refused (doc 06 §2), as the server's own log lines for that request
 * carry it (ADR-0024, amended 2026-11-08). Optional, not required: it is present on every error the application
 * answers, and a client that required it would fail to read the one body that lacked it, which is worse than a
 * body without it.
 */
internal fun traceIdSchema(): Schema<*> =
    StringSchema()
        .pattern("^[0-9a-f]{32}$")
        .description(
            "The id of the trace this request was served under: 32 lower-case hex digits (W3C Trace Context). The " +
                "server's log lines for the request carry the same id, so quoting it to support finds them. It names " +
                "a request, nothing in it, and is different on every request.",
        )

/**
 * `TIMEZONE_CHANGE_TOO_SOON`'s date (#142): the first calendar date, in the bond's shared zone, on which a new
 * proposal is accepted all day. Typed, so that a client fills it into its own words rather than reading the
 * English of `detail`. A date and never a countdown: `copy.md` bans urgency, and states.md §8 has the refusal name
 * the day.
 */
internal fun allowedFromSchema(): Schema<*> =
    DateSchema().description(
        "Only with `TIMEZONE_CHANGE_TOO_SOON`: the first date (`YYYY-MM-DD`, in the bond's shared time zone, " +
            "`anchorTimezone`) on which the shared time zone can be changed again, from its start. Absent on every " +
            "other code.",
    )
