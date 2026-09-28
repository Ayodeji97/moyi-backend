package com.moyi.app

import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.maps.shouldBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JpaTransactionScopeTest(
    @Autowired private val context: ApplicationContext,
) : IntegrationTest() {
    @Test
    fun `the web layer does not retain entities between service transactions`() {
        // The test profile inherits this setting from application.yml, just
        // like production. A request-scoped EntityManager would retain the
        // access guard's bond across transactions, so EndBond's locked read
        // could reuse a stale version and fail a concurrent leave or block.
        context.getBeansOfType(OpenEntityManagerInViewInterceptor::class.java).shouldBeEmpty()
    }
}
