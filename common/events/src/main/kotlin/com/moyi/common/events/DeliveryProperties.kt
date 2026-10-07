package com.moyi.common.events

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How long one delivery may take: the claim, the handler and the
 * acknowledgement, which are one transaction ([JdbcOutboxDispatcher]).
 *
 * **Why there is a bound at all.** A handler takes locks (the withdrawal's
 * takes a bond's), and a lock can be held by a session that never lets go.
 * Unbounded, that one delivery holds the poller for as long as the other
 * session lives, nothing behind it is delivered, and nothing says so.
 * Bounded, the delivery fails, is recorded and backed off like any other
 * failure, and the ones behind it proceed.
 *
 * A minute is far longer than any handler here should need (it erases one
 * member's entries in one bond) and short enough that a stuck delivery shows
 * in the failure count before anyone has to go looking. Less than a second is
 * refused: a transaction counts its deadline in whole seconds, and zero would
 * mean no limit, to Spring and to Postgres alike.
 */
@ConfigurationProperties(prefix = "moyi.outbox")
internal data class DeliveryProperties(
    val deliveryTimeout: Duration = Duration.ofSeconds(DEFAULT_DELIVERY_TIMEOUT_SECONDS),
) {
    init {
        require(deliveryTimeout >= Duration.ofSeconds(1)) {
            "moyi.outbox.delivery-timeout must be at least one second; less would round to none, which means waiting for ever."
        }
    }

    /** The bound as a transaction takes it: whole seconds, a part of one counted as one. */
    val transactionSeconds: Int
        get() = Math.toIntExact(deliveryTimeout.plusMillis(MILLIS_PER_SECOND - 1).toSeconds())

    private companion object {
        const val DEFAULT_DELIVERY_TIMEOUT_SECONDS = 60L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
