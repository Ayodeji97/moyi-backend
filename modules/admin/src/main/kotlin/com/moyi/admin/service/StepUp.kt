package com.moyi.admin.service

import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * The step-up rule (A2 spec §10.2): an irreversible act (an uphold, a quarantine block, a
 * quarantine delete) needs the second factor proven within the last [WINDOW]. The proof is
 * the session's `admin.secondFactorAt`, an `Instant` written by sign-in's code step and by
 * [AdminSignIn.stepUp].
 *
 * The service applies it, not the controller: a console method takes `secondFactorAt` as the
 * session holds it and asks [isFresh] against its own `Clock`, so there is no boolean a
 * controller could set to `true`. A missing value is stale; a value in the future (a clock
 * moved back) is not trusted either. The window is closed at its end: exactly [WINDOW] after
 * the proof is still fresh, one instant later is not.
 */
internal object StepUp {
    val WINDOW = 5.minutes

    fun isFresh(
        secondFactorAt: Instant?,
        now: Instant,
    ): Boolean = secondFactorAt != null && !secondFactorAt.isAfter(now) && !now.isAfter(secondFactorAt.plus(WINDOW.toJavaDuration()))
}

/**
 * An irreversible act (an uphold, a quarantine block, a quarantine delete) asked for without
 * the second factor proven within [StepUp.WINDOW] (A2 spec §10.2). Thrown before anything is
 * read or written; the controller answers it with the code field.
 */
internal class StepUpRequired : RuntimeException("A second factor proven within the last five minutes is required.")
