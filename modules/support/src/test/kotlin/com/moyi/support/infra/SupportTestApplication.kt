package com.moyi.support.infra

import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.common.web.idempotency.IdempotencyConfiguration
import com.moyi.identity.api.AccountMail
import com.moyi.identity.api.AccountMailOutcome
import com.moyi.identity.api.AccountMessage
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.util.Collections
import java.util.UUID

/**
 * A Spring context just large enough to test this module, as `MediaTestApplication` is: `common` and
 * `support`, and identity only through its `api` port, supplied here as [RecordingAccountMail], so a
 * test here cannot depend on identity's internals any more than the production code can.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common", "com.moyi.support"])
@ConfigurationPropertiesScan("com.moyi.common", "com.moyi.support")
@Import(IdempotencyConfiguration::class)
internal class SupportTestApplication {
    @Bean
    fun tokenRevocations(): TokenRevocations = TokenRevocations { TokenRevocation(invalidBefore = null) }

    @Bean
    fun accountMail(): RecordingAccountMail = RecordingAccountMail()
}

/** identity's port, recording what it was asked to send and to whom; every account is confirmed. */
internal class RecordingAccountMail : AccountMail {
    val sent: MutableList<Pair<UUID, AccountMessage>> = Collections.synchronizedList(mutableListOf())

    override fun sendToAccountHolder(
        userId: UUID,
        message: AccountMessage,
    ): AccountMailOutcome {
        sent.add(userId to message)
        return AccountMailOutcome.ACCEPTED
    }
}
