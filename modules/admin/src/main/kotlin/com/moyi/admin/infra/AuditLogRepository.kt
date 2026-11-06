package com.moyi.admin.infra

import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * Save and read, and no more: the log is append-only, so the repository
 * declares no delete and no bulk write (a `JpaRepository` would offer both,
 * and the trigger would be the only thing saying no).
 */
internal interface AuditLogRepository : Repository<AuditLogEntity, UUID> {
    fun save(row: AuditLogEntity): AuditLogEntity

    fun findById(id: UUID): AuditLogEntity?
}
