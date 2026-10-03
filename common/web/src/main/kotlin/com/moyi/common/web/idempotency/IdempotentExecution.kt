package com.moyi.common.web.idempotency

import com.moyi.common.core.IdGenerator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Which resource an `idempotency_keys.result_id` names — V11's
 * `result_kind` CHECK, as a type, so a kind the table would refuse cannot be
 * written in the first place and a replay always knows what to re-read.
 * A new idempotent resource adds a value here and to that CHECK together.
 */
enum class ResultKind { ENTRY, }

/**
 * What [IdempotencyInterceptor] read off an `@Idempotent` request, handed to
 * [IdempotentExecution.once] by the handler ([IdempotencyInterceptor.requestOf]).
 *
 * `internal` constructor: only the interceptor builds one, from the verified
 * caller and the request it actually received, so a handler cannot pass a
 * key or a fingerprint it made up.
 *
 * [path] is the raw `HttpServletRequest.requestURI`, not a canonicalised
 * form (Task 6 carry 3): a retry is byte-identical to the request it retries,
 * so two spellings of one path only ever come from two different requests,
 * and a mismatch fails in the safe direction — `422`, never somebody else's
 * replay.
 *
 * **The query string is not bound**: `requestURI` excludes it, and so does
 * the fingerprint. No idempotent endpoint takes one today; the first that
 * does must decide whether it is part of the target, and bind it if so.
 */
class IdempotentRequest internal constructor(
    val userId: UUID,
    val key: String,
    val method: String,
    val path: String,
    val fingerprint: String,
)

/**
 * What a mutating block hands back to [IdempotentExecution.once]: its
 * [value] for the fresh response, and the result's **identity** for the
 * record (Task 6 carry 1). [resultId] and [kind] are constructor parameters,
 * not optional ones, so an idempotent endpoint that forgets to name what it
 * produced does not compile — it cannot write a completed row with a null
 * `result_id`.
 *
 * Success only: [status] must be `2xx`. A refusal is *thrown*, so it rolls
 * the reservation back with everything else — see [IdempotentExecution].
 */
class IdempotentResult<out T : Any>(
    val value: T,
    val resultId: UUID,
    val kind: ResultKind,
    val status: Int,
    val etag: String? = null,
    val location: String? = null,
) {
    init {
        require(status in SUCCESS) { "an idempotent result is a success; a refusal is thrown and rolls back, got $status" }
    }

    private companion object {
        val SUCCESS = 200..299
    }
}

/**
 * Ruling P5's shape. On a fresh run [value] is the block's own; on a replay
 * it is `null` and [wasReplayed] is `true`, and **the caller re-reads the
 * resource by [resultId] through its ordinary *read* authorisation** — a
 * replay is authorised as a read of the result, not as a second write. That
 * is what makes erasure and lost access beat a replay (spec §5.4): an entry
 * withdrawn since renders as its tombstone, and a caller who may no longer
 * read the resource gets the read's own refusal, not the cached `201`. A
 * caller who may still read it gets it back even where a new write would
 * now be refused.
 *
 * [etag] and [location] are V11's replay allowlist, carried beside P5's five
 * fields so a replay can reproduce the headers the first response carried; a
 * caller that recomputes them from the re-read (the better answer when the
 * resource can change) is free to ignore these.
 *
 * `LongParameterList` is suppressed for that reason: seven is P5's five
 * plus the allowlist's two, each a distinct column of the row, and the
 * constructor is `internal` — only [IdempotentExecution] ever calls it.
 */
@Suppress("LongParameterList")
class IdempotentOutcome<out T : Any> internal constructor(
    val value: T?,
    val resultId: UUID,
    val resultKind: ResultKind,
    val status: Int,
    val wasReplayed: Boolean,
    val etag: String?,
    val location: String?,
)

/**
 * Spec §5.4's one transaction: **lock, reserve, mutate, complete** — all in
 * the caller's transaction, so they commit or roll back as one.
 *
 * **Why not the interceptor any more.** The previous shape reserved in
 * `preHandle` (its own transaction), mutated in the handler (a second one)
 * and completed in `afterCompletion` (a third). A crash between the first and
 * the third left a committed reservation with no result — every retry a
 * permanent `409 IDEMPOTENCY_KEY_IN_FLIGHT` for 24 hours, for a request that
 * never happened — and a crash between the second and the third left a
 * committed entry under a key that would run it again.
 *
 * In order, inside the caller's transaction:
 * 1. [IdempotencyKeyStore.lockFor] — `pg_try_advisory_xact_lock`, **nonblocking**:
 *    `false` is `409 IDEMPOTENCY_KEY_IN_FLIGHT` at once. Taken *before*
 *    the lookup (spec §5.4), so the lookup and everything after it are
 *    serialised per key without anyone ever waiting for this lock.
 * 2. The lookup. A live row is the key's first use: a different method,
 *    path or fingerprint is `422 IDEMPOTENCY_KEY_REUSED`; a match is a
 *    replay, returned with the stored identity and **without running the
 *    block**. An expired row is deleted — under the same lock, as spec §5.4
 *    requires of expired-key replacement.
 * 3. The reservation insert, then [block], then [IdempotencyKeyStore.complete]
 *    with the identity the block returned.
 *
 * **A failed attempt leaves no key.** A refusal (any `ApiException`) or a
 * crash is thrown out of the block, the caller's transaction rolls back, and
 * the reservation goes with the write: the next request under the same key —
 * a corrected body included — is a fresh one. Nothing but a success is ever
 * replayed, which is also what spec §5.4's "returns the original status and
 * stable result identity" presumes: a refusal has no result identity.
 *
 * **Lock order against a caller's own locks: this first.** A lock that is
 * only ever *tried* can never be waited on, so it cannot close a wait cycle
 * whichever side of the caller's locks it sits; but taking it first is what
 * keeps a second same-key request from waiting at all. `SubmitEntry`'s bond
 * lock is blocking — taken first, the second request would queue behind the
 * first request's bond lock before ever reaching this one.
 *
 * Why no unique-violation catch: a failed `INSERT` aborts a Postgres
 * transaction, so inside one the old insert-and-catch reservation cannot
 * recover. The advisory lock makes lookup-then-insert safe instead — every
 * writer of a key's row holds it. That relies on the caller's transaction
 * being `READ COMMITTED` (Postgres's and this application's default), so the
 * lookup, a statement of its own, sees a row the previous holder committed
 * just before releasing the lock; under a snapshot fixed earlier it could
 * miss it, and the insert would then fail at `idempotency_keys_unique` — a
 * `500`, never a second execution.
 *
 * A plain class, wired by [IdempotencyConfiguration] — see that class for
 * why nothing in this package is `@Component`.
 */
class IdempotentExecution(
    private val store: IdempotencyKeyStore,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    /**
     * @throws IdempotencyKeyInFlightException another transaction holds this key's lock
     * @throws IdempotencyKeyReusedException the key's first use was a different request
     * @throws IllegalStateException no transaction is active — the lock would be released at once
     */
    fun <T : Any> once(
        request: IdempotentRequest,
        block: () -> IdempotentResult<T>,
    ): IdempotentOutcome<T> {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "IdempotentExecution.once must run inside the caller's transaction: its advisory lock and its " +
                "reservation are only worth anything if they commit or roll back with the write they guard."
        }
        if (!store.lockFor(request.userId, request.key)) throw IdempotencyKeyInFlightException()

        val now = clock.instant()
        val existing = store.find(request.userId, request.key, now)
        if (existing != null) return replay(existing, request)

        store.deleteExpired(request.userId, request.key, now)
        val reservation =
            IdempotencyRecord(
                id = ids.timeOrdered(),
                userId = request.userId,
                method = request.method,
                path = request.path,
                idempotencyKey = request.key,
                requestHash = request.fingerprint,
                responseStatus = null,
                resultId = null,
                resultKind = null,
                responseEtag = null,
                responseLocation = null,
                createdAt = now,
                expiresAt = now.plus(TTL),
            )
        store.insert(reservation)

        val result = block()
        store.complete(reservation.id, result)
        return IdempotentOutcome(
            value = result.value,
            resultId = result.resultId,
            resultKind = result.kind,
            status = result.status,
            wasReplayed = false,
            etag = result.etag,
            location = result.location,
        )
    }

    /**
     * A mismatch on method, path or fingerprint is reuse (Ruling A: V11's
     * unique key is `(user_id, idempotency_key)` alone, so this comparison is
     * the whole of what refuses it). A row with no status cannot be seen from
     * outside the transaction that wrote it any more — reserve and complete
     * commit together — so that branch is defensive, and answers in the safe
     * direction.
     */
    private fun <T : Any> replay(
        existing: IdempotencyRecord,
        request: IdempotentRequest,
    ): IdempotentOutcome<T> {
        val matches =
            existing.method == request.method &&
                existing.path == request.path &&
                existing.requestHash == request.fingerprint
        if (!matches) throw IdempotencyKeyReusedException()
        val status = existing.responseStatus ?: throw IdempotencyKeyInFlightException()
        return IdempotentOutcome(
            value = null,
            resultId = checkNotNull(existing.resultId) { "V11's CHECK pairs result_id with result_kind; a completed row has both" },
            resultKind = ResultKind.valueOf(checkNotNull(existing.resultKind)),
            status = status.toInt(),
            wasReplayed = true,
            etag = existing.responseEtag,
            location = existing.responseLocation,
        )
    }

    private companion object {
        /** Doc 06 §1. */
        val TTL: Duration = Duration.ofHours(24)
    }
}
