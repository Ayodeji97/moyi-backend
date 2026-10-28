package com.moyi.gratitude.infra

import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushSender
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The push provider of this module's tests: it keeps every message it is
 * handed, which is everything a phone would be sent (ADR-0044 decision 9:
 * the token, the category and the Bond; the localisation keys follow from the
 * category), and takes each. Phase 4 N3's "no content in the captured
 * payload" is read from here.
 */
internal class CapturingPushSender : PushSender {
    val sent = CopyOnWriteArrayList<PushMessage>()

    override fun send(message: PushMessage): PushDelivery {
        sent += message
        return PushDelivery.Accepted("captured-${sent.size}")
    }
}
