package com.moyi.common.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotationAwareOrderComparator
import org.springframework.core.annotation.Order
import org.springframework.core.annotation.OrderUtils
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * That a module's advice actually sorts ahead of the catch-all.
 *
 * Worth a test of its own because the behavioural test could not tell the
 * difference: removing the order annotation left every endpoint test green,
 * since the tie was being broken by bean discovery order and that order
 * happened to be the one we wanted. A guard whose absence changes nothing
 * observable is a guard nobody will notice deleting.
 */
class ExceptionHandlerAdviceOrderTest {
    @Order(ExceptionHandlerAdviceOrder.MODULE)
    @RestControllerAdvice
    private class ModuleAdvice

    @RestControllerAdvice
    private class UnorderedAdvice

    @Test
    fun `a module advice sorts ahead of the catch-all`() {
        // Deliberately supplied in the wrong order: a stable sort would leave
        // it alone, so passing means the comparator actually moved it.
        val advices = mutableListOf<Any>(GlobalExceptionHandlerMarker(), ModuleAdvice())

        AnnotationAwareOrderComparator.sort(advices)

        (advices.first() is ModuleAdvice) shouldBe true
    }

    @Test
    fun `an advice with no order is already at the lowest precedence`() {
        // The fact that makes @Order(LOWEST_PRECEDENCE) on the catch-all a
        // no-op, and therefore the reason the module side has to carry the
        // annotation instead.
        // `getOrder` returning null is precisely what Spring then treats as
        // LOWEST_PRECEDENCE — there is no distinction between "unordered" and
        // "ordered last", which is the whole trap.
        OrderUtils.getOrder(UnorderedAdvice::class.java) shouldBe null
        OrderUtils.getOrder(UnorderedAdvice::class.java, Ordered.LOWEST_PRECEDENCE) shouldBe Ordered.LOWEST_PRECEDENCE
        OrderUtils.getOrder(ModuleAdvice::class.java) shouldBe ExceptionHandlerAdviceOrder.MODULE
    }

    /** Stands in for the real handler, which needs constructor arguments this test has no use for. */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @RestControllerAdvice
    private class GlobalExceptionHandlerMarker
}
