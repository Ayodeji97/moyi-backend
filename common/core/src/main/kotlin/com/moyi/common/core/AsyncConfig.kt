package com.moyi.common.core

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync

/**
 * Switches `@Async` on for the whole application — once, here, because it is
 * an application-wide switch and a module that declared it for itself would
 * be declaring it for everyone.
 *
 * No executor is configured. With `spring.threads.virtual.enabled=true`
 * (application.yml) Boot's `applicationTaskExecutor` runs each task on a
 * virtual thread, and Boot registers that executor as the one `@Async` uses.
 * A pool would be the wrong shape for work that is mostly waiting on a
 * provider's HTTP response.
 *
 * Used first by the identity module's `SendVerificationEmail`, an
 * `AFTER_COMMIT` listener that leaves the request thread so the response
 * time does not include the email provider's.
 */
@Configuration
@EnableAsync
class AsyncConfig
