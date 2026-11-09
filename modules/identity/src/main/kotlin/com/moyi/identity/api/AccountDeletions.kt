package com.moyi.identity.api

/**
 * The scheduled half of account deletion (FR-008, ADR-0058), for the job that has no caller:
 * `scheduling` knows when, identity knows what.
 *
 * Each due request is carried out in a transaction of its own: the account is anonymised,
 * every way back into it is closed, and one `AccountDeleted {userId}` event is published in
 * the same commit, from which every other module erases what it holds of that person. A run
 * that fails part-way commits nothing for that account, and the next run takes it again.
 */
interface AccountDeletions {
    /** Carries out up to [limit] requests whose cancellation window has closed; how many it carried out. */
    fun executeDue(limit: Int): Int

    companion object {
        /** The event's type. Its one reference is [USER_ID]; it says nothing else about the person. */
        const val EVENT = "AccountDeleted"
        const val USER_ID = "userId"
    }
}
