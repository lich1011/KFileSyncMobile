package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.DiscoveredDevice
import com.kfilesync.mobile.domain.port.EventBus
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Driving port: device discovery & pairing use cases (design doc §6.2.1).
 *
 * Phase 1 wiring:
 * - `observeDevices()` collects from [DeviceRepository.findAll] (incl.
 * discovered & paired, sans revoked).
 * - `observeDiscoveredDevices()` delegates to [DiscoveryCoordinator.devices].
 * - pairing / revocation route through [PairingService].
 *
 * `discoverDevices()` is a one-shot snapshot helper kept around for the
 * Phase 0 PoC button; ongoing discovery happens through the coordinator's
 * StateFlow.
 */
interface DeviceAppService {

    suspend fun discoverDevices(): List<DiscoveredDevice>

    suspend fun initiatePairing(target: DiscoveredDevice): PairingSessionDescriptor

    suspend fun confirmPairing(
        sessionId: String,
        peerPin: String,
        peerBaseUrl: String,
        peerAlias: String,
        peerPlatform: com.kfilesync.mobile.domain.model.DevicePlatform
    ): Result<Unit>

    suspend fun cancelPairing(sessionId: String)

    suspend fun revokeTrust(deviceId: DeviceId): Result<Unit>

    /** Hot stream of every known device (Discovered & Paired). */
    fun observeDevices(): Flow<List<Device>>

    /** Hot stream of devices currently discovered on the LAN. */
    fun observeDiscoveredDevices(): Flow<List<DiscoveredDevice>>

    /** Pulls the latest paired-and-discovered devices from the repo into the StateFlow. */
    suspend fun refreshPaired()
}

/** Light DTO returned by [DeviceAppService.initiatePairing]. */
data class PairingSessionDescriptor(
    val sessionId: String,
    val pin: String,
    val expiresAtEpochMs: Long
)

/**
 * Phase 1 implementation.
 *
 * `paired` is held as a MutableStateFlow so the UI gets the current snapshot
 * on subscribe; we re-pull from the repo on every domain event that
 * meaningfully changes the list (pairing complete, trust revoked). For
 * Phase 1 we do that pull eagerly via [refreshPaired]; Phase 4 will wire
 * the repo itself as a Flow.
 *
 * `discovered` is delegated to the optional [discoveryCoordinator]; if it's
 * null (Phase 0 wiring during tests) we fall back to a never-emitting flow.
 */
class DeviceServiceImpl(
    private val deviceRepository: DeviceRepository,
    private val pairingService: PairingService,
    private val discoveryCoordinator: DiscoveryCoordinator? = null,
    @Suppress("unused") private val eventBus: EventBus
) : DeviceAppService {

    private val paired = MutableStateFlow<List<Device>>(emptyList())

    override suspend fun discoverDevices(): List<DiscoveredDevice> =
        discoveryCoordinator?.devices?.value ?: emptyList()

    override suspend fun initiatePairing(target: DiscoveredDevice): PairingSessionDescriptor {
        val pairingTarget = PairingTarget(
            deviceId = target.deviceId,
            alias = target.alias,
            platform = target.platform,
            fingerprint = target.fingerprint,
            baseUrl = target.addresses.firstOrNull()?.let { "https://${it.host}:${it.port}" }
        )
        val session = pairingService.initiateOutgoing(pairingTarget)
        return PairingSessionDescriptor(
            sessionId = session.sessionId,
            pin = session.pin.digits,
            expiresAtEpochMs = session.expiry.expiresAt.toEpochMilliseconds()
        )
    }

    override suspend fun confirmPairing(
        sessionId: String,
        peerPin: String,
        peerBaseUrl: String,
        peerAlias: String,
        peerPlatform: com.kfilesync.mobile.domain.model.DevicePlatform
    ): Result<Unit> {
        val outcome = pairingService.submitOutgoingConfirm(
            sessionId = sessionId,
            peerPin = peerPin,
            peerBaseUrl = peerBaseUrl,
            peerAlias = peerAlias,
            peerPlatform = peerPlatform
        )
        if (outcome.isSuccess) refreshPaired()
        return outcome
    }

    override suspend fun cancelPairing(sessionId: String) {
        pairingService.cancel(sessionId)
    }

    override suspend fun revokeTrust(deviceId: DeviceId): Result<Unit> {
        val outcome = pairingService.revoke(deviceId)
        if (outcome.isSuccess) refreshPaired()
        return outcome
    }

    override fun observeDevices(): Flow<List<Device>> = paired.asStateFlow()

    override fun observeDiscoveredDevices(): Flow<List<DiscoveredDevice>> =
        discoveryCoordinator?.devices ?: MutableStateFlow<List<DiscoveredDevice>>(emptyList()).asStateFlow()

    override suspend fun refreshPaired() {
        val list = deviceRepository.findAll()
        Napier.d("refreshPaired() -> ${list.size} devices")
        paired.value = list
    }
}