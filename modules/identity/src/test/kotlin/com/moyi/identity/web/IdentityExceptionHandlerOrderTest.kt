package com.moyi.identity.web

import com.moyi.common.web.ExceptionHandlerAdviceOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.OrderUtils

/**
 * That *this* advice carries the precedence, not merely that the mechanism
 * exists.
 *
 * `common:web`'s own ordering test uses a throwaway advice class, because
 * `common` must not depend on a module to test itself — which means it can
 * prove the comparator works and cannot prove that identity opted in. This
 * assertion is the other half, and it lives here because this is the only
 * module that can see the class.
 *
 * Deleting the `@Order` leaves every endpoint test green: the tie is then
 * broken by bean discovery order, which currently happens to favour this
 * advice. That is why the guard needs a test that looks at the annotation
 * rather than at a response.
 */
internal class IdentityExceptionHandlerOrderTest {
    @Test
    fun `the identity advice outranks the catch-all`() {
        OrderUtils.getOrder(IdentityExceptionHandler::class.java) shouldBe ExceptionHandlerAdviceOrder.MODULE
    }
}
