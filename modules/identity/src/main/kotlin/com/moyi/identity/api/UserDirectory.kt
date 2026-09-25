package com.moyi.identity.api

import java.util.UUID

/**
 * What another module may know about a user (ADR-0026): enough to ask "has
 * this person verified their address?" and to print a name beside an entry,
 * and nothing else. No email, no status, no timestamps, no password state.
 *
 * The `bond` module calls this before creating or joining a bond (FR-002 —
 * "unverified accounts may sign in but MUST NOT create or join a Bond") and
 * when rendering a bond's members.
 *
 * **The first `api` package in a domain module, and the pattern for every
 * later one.** An interface plus plain DTOs, public, depending on nothing;
 * the implementation stays `internal` in `service`. A caller depends on the
 * module and can reach exactly this — Kotlin's `internal` makes that the
 * compiler's guarantee rather than a convention, which is what doc 25 §6
 * means by a narrow `api` package. `notification.api.EmailSender` set the
 * precedent; this is the first one with a query in it.
 *
 * In a modular monolith (ADR-0001) a call across this boundary is an ordinary
 * in-process method call. The boundary is the **visibility**, not a network
 * hop, and the discipline it buys is the same: if this interface is ever the
 * wrong shape, one file says so.
 */
interface UserDirectory {
    /** `null` for an id that names nobody — a deleted account, or a stale reference. */
    fun find(id: UUID): UserSummary?

    /**
     * The subset of [ids] that exist, keyed by id. Missing ids are simply
     * absent rather than an error: a caller holding one is ordinary.
     */
    fun findAll(ids: Collection<UUID>): Map<UUID, UserSummary>
}

/**
 * A user as seen from outside identity.
 *
 * [toString] prints only the id. A `data class` prints every property by
 * default, and a display name is personal data that would otherwise reach a
 * log the moment anything logged an object holding one (doc 18 §9) — the same
 * mechanism `RegisterRequest` documents for the plaintext password.
 */
data class UserSummary(
    val id: UUID,
    val displayName: String,
    val emailVerified: Boolean,
) {
    override fun toString(): String = "UserSummary(id=$id)"
}
