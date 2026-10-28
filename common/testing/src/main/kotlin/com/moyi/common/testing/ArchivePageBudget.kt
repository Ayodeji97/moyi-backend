package com.moyi.common.testing

/**
 * What the archive's largest page was measured with, shared by the test that
 * measures it and the test that holds identity to it.
 *
 * `gratitude`'s `DaysFeedTest` builds the largest page there can be with a
 * partner's name of [MEASURED_DISPLAY_NAME_LENGTH] characters on every day,
 * and measures it under the 256 KB a response may be (ADR-0036 decision 6 and
 * question 5). It cannot see identity's own limit, which is `internal` to
 * that module. `app`'s `OpenApiContractTest` can see the limit as the API
 * states it, `RegisterRequest.displayName`'s `maxLength`, and fails if it is
 * ever larger than this: a longer name would make a page the measurement
 * never covered.
 */
object ArchivePageBudget {
    const val MEASURED_DISPLAY_NAME_LENGTH = 80
}
