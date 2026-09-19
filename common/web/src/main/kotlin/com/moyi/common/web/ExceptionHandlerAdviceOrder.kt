package com.moyi.common.web

/**
 * The precedence a module's own `@RestControllerAdvice` must declare so that
 * it is consulted before [GlobalExceptionHandler]'s catch-all.
 *
 * It exists as a named constant because the number is meaningless on its own
 * and the failure it prevents is silent: an advice that forgets it does not
 * break, it just stops being reached for its own exceptions the moment bean
 * discovery order changes, and the symptom is a 500 where a 503 belonged.
 */
object ExceptionHandlerAdviceOrder {
    /**
     * Any value below `Ordered.LOWEST_PRECEDENCE` works. Zero, rather than
     * `HIGHEST_PRECEDENCE`, leaves room on both sides for an advice that
     * later needs to sit before or after a module's.
     */
    const val MODULE = 0
}
