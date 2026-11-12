package com.moyi.admin.domain

/**
 * Why an admin looked a member's account up (spec §9.3, owner ruling 6 in §14a), chosen on
 * the lookup form and recorded as the audit row's `metadata.purpose`.
 *
 * [disclosed] says whether the member's data export lists the lookup. A lookup the member
 * asked for, or one made to investigate a fault, is listed. One made for a report or a
 * moderation case (`SAFETY`) or for a law-enforcement request (`LEGAL`) is withheld, because
 * listing it could tip off the person it is about; such a lookup needs a typed note of 1–400
 * characters, written to the row's `justification`, so a withheld lookup always says why.
 */
internal enum class LookupPurpose(
    val disclosed: Boolean,
) {
    MEMBER_REQUEST(true),
    OPERATIONS(true),
    SAFETY(false),
    LEGAL(false),
}
