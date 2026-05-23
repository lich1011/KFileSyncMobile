package com.kfilesync.mobile.domain.model

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Device trust state machine (design doc §6.5.1).
 *
 * Legal transitions:
 * Discovered --confirmPairing--> Paired
 * Paired     --revoke----------> Revoked
 *
 * All other transitions return Result.failure(InvalidStateTransition).
 */
sealed class DeviceState {

    data class Discovered(val discoveredAt: Instant) : DeviceState()
    data class Paired(val certificatePem: String, val pairedAt: Instant) : DeviceState()
    data class Revoked(val revokedAt: Instant) : DeviceState()

    fun confirmPairing(certificatePem: String): Result<DeviceState> = when (this) {
        is Discovered -> Result.success(Paired(certificatePem, Clock.System.now()))
        else -> Result.failure(IllegalStateException("Only Discovered can be paired (was $this)"))
    }

    fun revoke(): Result<DeviceState> = when (this) {
        is Paired -> Result.success(Revoked(Clock.System.now()))
        else -> Result.failure(IllegalStateException("Only Paired can be revoked (was $this)"))
    }
}