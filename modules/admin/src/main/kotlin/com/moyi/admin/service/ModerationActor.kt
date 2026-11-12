package com.moyi.admin.service

import java.util.UUID

/**
 * The actor string the moderation console passes to every report and quarantine function
 * (A2 spec §6): `admin:<accountId>`, 42 characters, inside both module logs' 1–100 `CHECK`.
 * Built in this one place, so that `entry_report_log`, `media_moderation_log` and `audit_log`
 * agree on who acted, and an operator's `scripts/moyi` name can never be mistaken for it.
 */
internal object ModerationActor {
    private const val PREFIX = "admin:"

    fun of(adminId: UUID): String = PREFIX + adminId
}
