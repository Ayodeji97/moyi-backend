package com.moyi.identity.infra.database

import com.moyi.identity.domain.Device
import com.moyi.identity.domain.UserId
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** The `devices` table in domain terms. Three things, because that is what FR-007 does with it. */
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
}
