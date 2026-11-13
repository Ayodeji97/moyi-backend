package com.moyi.admin.infra

import com.moyi.bond.api.BondAdminLookup
import com.moyi.common.security.TokenRevocations
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.PasswordHashing
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.api.SuspensionResult
import com.moyi.identity.api.UserAdminLookup
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Spring context just large enough to test this module, test-only. It exists
 * because everything here is `internal`: nothing in `app` can name an entity.
 *
 * It scans this module and `common`, and deliberately not `com.moyi` as a whole:
 * identity and bond are on the classpath for their `api` ports, and scanning
 * them would boot their beans (the breached-password corpus among them) for a
 * test that needs none of it.
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.admin", "com.moyi.common"])
@ConfigurationPropertiesScan("com.moyi.common", "com.moyi.admin")
@EntityScan("com.moyi.admin")
@EnableJpaRepositories("com.moyi.admin")
internal class AdminTestApplication {
    /**
     * `common:security` builds the API's JWT chain, which wants this port from
     * the identity module. No test here sends a token, so nobody is revoked.
     */
    @Bean
    fun tokenRevocations(): TokenRevocations = TokenRevocations { null }

    /**
     * Identity is not scanned here, so its Argon2 hasher and breach corpus are not built: this
     * stands in for the port. A "hash" is the text behind a prefix, and the breach corpus is
     * [BREACHED]. The real port is exercised by `app`'s sign-in tests.
     */
    @Bean
    fun passwordHashing(): PasswordHashing =
        object : PasswordHashing {
            override fun hash(raw: String): String = "test:$raw"

            override fun matches(
                raw: String,
                hash: String,
            ): Boolean = hash == "test:$raw"

            override fun matchesDummy(raw: String): Boolean = false

            override fun isBreached(raw: String): Boolean = raw == BREACHED
        }

    /** Nobody exists in this context; `app`'s lookup tests run the real ports. */
    @Bean
    fun userAdminLookup(): UserAdminLookup =
        object : UserAdminLookup {
            override fun byId(id: java.util.UUID) = null

            override fun byEmail(email: String) = null
        }

    @Bean
    fun bondAdminLookup(): BondAdminLookup =
        object : BondAdminLookup {
            override fun bondsOf(userId: UUID) = emptyList<com.moyi.bond.api.BondAdminView>()
        }

    /** Identity's port, scripted: [ScriptedAccountSuspension] answers what a test sets and records each call. */
    @Bean
    fun accountSuspension(): AccountSuspension = ScriptedAccountSuspension

    /** No actuator in this context; the sign-in counter needs a registry to count in. */
    @Bean
    fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

    companion object {
        const val BREACHED = "passwordpassword"
    }
}

/**
 * A stand-in for identity's `AccountSuspension`, which this context does not build. It records each
 * call as `suspend:<user>` or `lift:<user>` and answers [answer]. Like the real port it refuses to
 * run outside a transaction, so a test sees a console that forgot to open one.
 */
internal object ScriptedAccountSuspension : AccountSuspension {
    val calls: MutableList<String> = CopyOnWriteArrayList()

    @Volatile var answer: SuspensionResult = SuspensionResult(SuspensionOutcome.SUSPENDED, null, null)

    fun reset() {
        calls.clear()
        answer = SuspensionResult(SuspensionOutcome.SUSPENDED, null, null)
    }

    override fun suspend(userId: UUID): SuspensionResult {
        mandatory()
        calls += "suspend:$userId"
        return answer
    }

    override fun lift(userId: UUID): SuspensionResult {
        mandatory()
        calls += "lift:$userId"
        return answer
    }

    /** The real port is `MANDATORY`; a final object cannot be proxied, so the rule is checked by hand. */
    private fun mandatory() = check(TransactionSynchronizationManager.isActualTransactionActive()) { "No transaction (MANDATORY)." }
}
