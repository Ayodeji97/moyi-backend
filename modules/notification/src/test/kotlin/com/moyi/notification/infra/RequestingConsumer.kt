package com.moyi.notification.infra

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands where N3's `gratitude.notify` will stand: a consumer that, inside
 * its own outbox delivery, asks for a push. Built to check the spec's one
 * [inferred] item for N1 (§2.2): that publishing to the outbox from inside a
 * delivery's transaction meets ADR-0035's rule that publishers run at READ
 * COMMITTED. It records the level its transaction really ran at, as Postgres
 * reports it.
 */
@Component
internal class RequestingConsumer(
    private val notifications: Notifications,
    private val jdbc: JdbcTemplate,
) : EventConsumer {
    val isolationLevels: MutableList<String> = CopyOnWriteArrayList()

    override val id = "test.requests-a-push"
    override val eventTypes = setOf(FACT)
    override val startFrom = StartFrom.NOW

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        isolationLevels += jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String::class.java)!!
        val userId = checkNotNull(event.references["userId"])
        val bondId = checkNotNull(event.references["bondId"])
        notifications.request(
            PushRequest(
                userId = userId,
                bondId = bondId,
                category = PushCategory.REVEAL,
                dedupeKey = "reveal:${event.aggregateId}:$userId",
                staleAfter = event.occurredAt.plus(Duration.ofDays(1)),
                recipient = PushRecipient(ZoneId.of("UTC"), null, NotificationPreferences.DEFAULTS),
            ),
        )
    }

    companion object {
        const val FACT = "NotificationTest.FactHappened"
    }
}
