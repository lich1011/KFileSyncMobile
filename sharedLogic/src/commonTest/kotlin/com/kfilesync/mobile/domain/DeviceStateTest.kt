package com.kfilesync.mobile.domain

import com.kfilesync.mobile.domain.DomainError
import com.kfilesync.mobile.domain.model.DeviceState
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

class DeviceStateTest {

    private val now = Clock.System.now()

    // ---------- confirmPairing ----------

    @Test
    fun discovered_can_transition_to_paired() {
        val state = DeviceState.Discovered(discoveredAt = now)
        val result = state.confirmPairing("CERT_PEM_DATA")
        assertTrue(result.isSuccess)
        val paired = result.getOrThrow()
        assertIs<DeviceState.Paired>(paired)
        kotlin.test.assertEquals("CERT_PEM_DATA", paired.certificatePem)
    }

    @Test
    fun paired_cannot_pair_again() {
        val state = DeviceState.Paired("CERT_PEM", now)
        val result = state.confirmPairing("NEW_CERT")
        assertTrue(result.isFailure)
        assertIs<DomainError.InvalidStateTransition>(result.exceptionOrNull())
    }

    @Test
    fun revoked_cannot_pair() {
        val state = DeviceState.Revoked(revokedAt = now)
        val result = state.confirmPairing("CERT_PEM")
        assertTrue(result.isFailure)
        assertIs<DomainError.InvalidStateTransition>(result.exceptionOrNull())
    }

    // ---------- revoke ----------

    @Test
    fun paired_can_be_revoked() {
        val state = DeviceState.Paired("CERT_PEM", now)
        val result = state.revoke()
        assertTrue(result.isSuccess)
        assertIs<DeviceState.Revoked>(result.getOrThrow())
    }

    @Test
    fun discovered_cannot_be_revoked() {
        val result = DeviceState.Discovered(discoveredAt = now).revoke()
        assertTrue(result.isFailure)
        assertIs<DomainError.InvalidStateTransition>(result.exceptionOrNull())
    }

    @Test
    fun revoked_cannot_be_revoked_again() {
        val result = DeviceState.Revoked(revokedAt = now).revoke()
        assertTrue(result.isFailure)
        assertIs<DomainError.InvalidStateTransition>(result.exceptionOrNull())
    }
}
