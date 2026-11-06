package com.moyi.admin.infra

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.Repository
import java.util.UUID

internal interface AdminInvitationRepository : Repository<AdminInvitationEntity, UUID> {
    fun findByTokenHash(tokenHash: ByteArray): AdminInvitationEntity?

    /**
     * The same row, locked until the transaction ends (`SELECT … FOR UPDATE`): two
     * enrolments with one token queue here, and the second finds it consumed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByTokenHash(tokenHash: ByteArray): AdminInvitationEntity?
}
