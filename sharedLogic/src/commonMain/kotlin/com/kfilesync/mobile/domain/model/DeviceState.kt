package com.kfilesync.mobile.domain.model


/**
 * Device trust state machine (design doc §6.5.1).
 *
 * Legal transitions:
 * Discovered --confirmPairing--> Paired
 * Paired     --revoke----------> Revoked
 *
 * All other transitions return Result.failure(InvalidStateTransition).
 */
enum class DeviceState {Discovered, Paired, Revoked}