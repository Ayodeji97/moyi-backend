package com.moyi.admin.infra

import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * Save, flush and read, and no more: the log is append-only, so the repository
 * declares no delete and no bulk write (a `JpaRepository` would offer both,
 * and the trigger would be the only thing saying no).
 */
internal interface AuditLogRepository : Repository<AuditLogEntity, UUID> {
    fun save(row: AuditLogEntity): AuditLogEntity

    /** Sends the pending insert now, so that an act learns its record failed before it returns anything. */
    fun flush()

    fun findById(id: UUID): AuditLogEntity?
}
