package com.moyi.identity.infra.database

import com.moyi.identity.domain.Device
import com.moyi.identity.domain.UserId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** The `devices` table in domain terms: what FR-007 does with it, and the fold push tokens make (ADR-0025). */
@Component
internal class DeviceStore(
    private val devices: DeviceRepository,
) {
    fun insert(device: Device) {
        devices.save(device.toEntity())
    }

    fun findAllOf(userId: UserId): Map<UUID, Device> = devices.findAllByUserId(userId.value).associate { it.getId() to it.toDomain() }

    /** A rotation is the device being seen again. */
    fun touch(
        id: UUID,
        now: Instant,
    ) {
        devices.touch(id, now)
    }

    fun find(id: UUID): Device? = devices.findById(id)?.toDomain()

    /** Marks [ids], earlier sign-ins of [userId] from the phone that now holds their push token, as folded. */
    fun fold(
        ids: Set<UUID>,
        userId: UserId,
        now: Instant,
    ) {
        if (ids.isNotEmpty()) devices.fold(ids, userId.value, now)
    }
}
