package com.moyi.bond.service

import java.security.MessageDigest

/**
 * A validator for the caller-visible representation, including data outside the
 * bond row. The version alone misses invite rotation/expiry and display-name
 * changes, and can make a conditional GET return 304 for a changed body.
 * Private member settings and block records must never enter this digest.
 */
internal fun BondView.entityTag(): String {
    val fields =
        buildList<Any?> {
            addAll(
                listOf(
                    bond.id.value,
                    bond.type,
                    bond.name,
                    bond.anchorTimezone.id,
                    bond.revealTimeLocal,
                    bond.strictMode,
                    bond.status,
                    bond.maxMembers,
                    bond.createdAt,
                    bond.archivedAt,
                    bond.deletionScheduledFor,
                ),
            )
            members.forEach { view ->
                val member = view.member
                addAll(listOf(member.id.value, view.displayName, member.role, member.joinedAt, member.leftAt))
            }
            addAll(
                listOf(
                    timezoneChange?.id?.value,
                    timezoneChange?.payload,
                    timezoneChange?.proposedByMemberId?.value,
                    timezoneChange?.proposedAt,
                    timezoneChange?.expiresAt,
                ),
            )
            addAll(listOf(deletion?.proposedByMemberId?.value, deletion?.proposedAt, deletion?.expiresAt))
            addAll(listOf(me.id.value, me.role))
            addAll(listOf(invite?.invite?.id?.value, invite?.invite?.code?.value, invite?.link, invite?.invite?.expiresAt))
        }
    // Length-prefix each value so delimiters in a name cannot collide with the
    // next field. Null and the literal string "null" remain different values.
    val canonical = fields.joinToString("") { value -> value?.toString()?.let { "${it.length}:$it" } ?: "-1:" }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).toHexString()
    return "\"${bond.version}-$digest\""
}
