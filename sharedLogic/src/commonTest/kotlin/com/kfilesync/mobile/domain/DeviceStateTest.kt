package com.kfilesync.mobile.domain

import com.kfilesync.mobile.domain.DomainError
import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.DeviceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class DeviceStateTest {

    private val now = Clock.System.now()
    private val baseDevice = Device(
        id = DeviceId("test-id"),
        alias = "test-device",
        platform = DevicePlatform.Android,
        deviceType = DeviceType.Mobile,
        discoveredAt = now
    )

    // ---------- confirmPairing ----------

    @Test
    fun discovered_can_transition_to_paired() {
        val device = baseDevice.copy(state = DeviceState.Discovered)
        val result = device.confirmPairing("CERT_PEM_DATA")
        assertTrue(result.isSuccess)
        val paired = result.getOrThrow()
        assertEquals(DeviceState.Paired, paired.state)
        assertEquals("CERT_PEM_DATA", paired.certificatePem)
    }

    @Test
    fun paired_cannot_pair_again() {
        val device = baseDevice.copy(state = DeviceState.Paired, certificatePem = "CERT_PEM", pairedAt = now)
        val result = device.confirmPairing("NEW_CERT")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DomainError.InvalidStateTransition)
    }

    @Test
    fun revoked_cannot_pair() {
        val device = baseDevice.copy(state = DeviceState.Revoked, revokedAt = now)
        val result = device.confirmPairing("CERT_PEM")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DomainError.InvalidStateTransition)
    }

    // ---------- revoke ----------

    @Test
    fun paired_can_be_revoked() {
        val device = baseDevice.copy(state = DeviceState.Paired, certificatePem = "CERT_PEM", pairedAt = now)
        val result = device.revoke()
        assertTrue(result.isSuccess)
        assertEquals(DeviceState.Revoked, result.getOrThrow().state)
    }

    @Test
    fun discovered_cannot_be_revoked() {
        val device = baseDevice.copy(state = DeviceState.Discovered)
        val result = device.revoke()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DomainError.InvalidStateTransition)
    }

    @Test
    fun revoked_cannot_be_revoked_again() {
        val device = baseDevice.copy(state = DeviceState.Revoked, revokedAt = now)
        val result = device.revoke()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DomainError.InvalidStateTransition)
    }
}
