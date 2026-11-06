package com.moyi.admin.domain

/** How an audited act ended: it happened, it was turned away, or it broke. */
internal enum class AuditOutcome { SUCCEEDED, REFUSED, FAILED }
