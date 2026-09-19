package com.moyi.common.web

import org.springframework.http.HttpStatus

/**
 * A failure that has a defined place in the API contract: a status, a stable
 * [ErrorCode], and a sentence safe to show a user.
 *
 * **Why an exception at all**, given doc 18 §3 prefers a sealed `Result` type:
 * that rule is about *module boundaries*, where a caller in another module
 * should be made to handle failure explicitly by the type system. These are
 * different — they are conditions the HTTP edge exists to translate, raised
 * far from it, and usually unrecoverable by the caller in between. Threading
 * a `Result` through every layer to be unwrapped once, at the top, would be
 * ceremony that obscures the happy path. When `identity` grows an `api`
 * package that another module calls, that boundary gets a `Result`.
 *
 * [detail] is shown to a user. It must never echo request input (doc 18 §5)
 * and must never quote an underlying exception (§9).
 */
abstract class ApiException(
    val status: HttpStatus,
    val errorCode: ErrorCode,
    val detail: String,
    cause: Throwable? = null,
) : RuntimeException("$errorCode: $detail", cause)
