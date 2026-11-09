package com.moyi.export.api

/**
 * The timed half of the data export (FR-009, FR-094, ADR-0059), for the job that has no caller:
 * `scheduling` knows when, this module knows what.
 */
interface DataExportJobs {
    /**
     * Builds up to [limit] exports that are waiting, oldest first, each into a zip in the media
     * storage, and tells each person by email that theirs is ready. How many it made ready.
     * An export whose build fails is tried again on a later run, and given up as `FAILED` after
     * the third attempt; one failure does not stop the others.
     */
    fun prepareDue(limit: Int): Int

    /** Expires up to [limit] exports whose 24 hours are over, deleting each archive. How many it expired. */
    fun expireDue(limit: Int): Int
}
