package com.moyi.common.web.idempotency

/**
 * Marks a handler method whose caller must send `Idempotency-Key` (doc 06
 * §1). Read by [IdempotencyInterceptor] before the handler runs.
 *
 * Doc 06 §1 has required this header on every unsafe write a client might
 * retry since before any code existed — a lost response on a flaky mobile
 * connection is exactly when a `POST` gets resent, and without this the
 * resend performs the thing a second time. An absent header is
 * `422 VALIDATION_FAILED`; a present one reserves a row under V11's own
 * unique key, `(user_id, idempotency_key)` — the endpoint is stored
 * alongside it and compared in code, not part of that key (F6, whole-branch
 * review; see [IdempotencyInterceptor.replay] for why) — so a retry is
 * answered from the first attempt's stored response instead of running the
 * handler again.
 *
 * `POST /entries` (task 7) is the first handler to carry this — `common:web`
 * has no domain endpoint of its own, so this module's own test proves the
 * behaviour against a throwaway fixture controller, the same way
 * [com.moyi.common.web.GlobalExceptionHandlerTest] does for the error
 * contract.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Idempotent
