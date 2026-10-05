package com.moyi.common.web.idempotency

/**
 * Marks a handler method accepting `Idempotency-Key` (doc 06
 * §1). Read by [IdempotencyInterceptor] before the handler runs.
 *
 * Doc 06 §1 has required this header on every unsafe write a client might
 * retry since before any code existed — a lost response on a flaky mobile
 * connection is exactly when a `POST` gets resent, and without this the
 * resend performs the thing a second time. An absent header is
 * `422 VALIDATION_FAILED` unless [required] is false (PATCH accepts an omitted
 * key). A present one is fingerprinted by
 * [IdempotencyInterceptor] and handed to the handler, which **must** run its
 * write through [IdempotentExecution.once] inside its own transaction
 * (fetching the request with [IdempotencyInterceptor.requestOf]): the
 * annotation alone records nothing. A replay is answered with the first
 * attempt's status and the same resource, re-read by the handler from its
 * current state, instead of running the write again.
 *
 * `POST /entries` (task 7) is the first handler to carry this — `common:web`
 * has no domain endpoint of its own, so this module's own test proves the
 * behaviour against a throwaway fixture controller, the same way
 * [com.moyi.common.web.GlobalExceptionHandlerTest] does for the error
 * contract.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Idempotent(
    val required: Boolean = true,
)
