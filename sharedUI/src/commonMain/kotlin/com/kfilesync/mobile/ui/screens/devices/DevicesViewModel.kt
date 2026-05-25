package com.kfilesync.mobile.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.service.DeviceAppService
import com.kfilesync.mobile.application.service.DiscoveryCoordinator
import com.kfilesync.mobile.application.service.HeartbeatService
import com.kfilesync.mobile.application.service.ManualIpProbe
import com.kfilesync.mobile.application.service.OnlineState
import com.kfilesync.mobile.application.service.PairingSessionDescriptor
import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.port.DiscoveredDevice
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for the Devices tab (T1.7).
 *
 * Aggregates four upstream sources into a single [DevicesUiState]:
 * - [DeviceAppService.observeDevices] – paired + discovered devices we know about (everything except revoked).
 * - [DiscoveryCoordinator.devices] – live mDNS / manual-IP discoveries.
 * - [HeartbeatService.presence] – per-device online/offline flag.
 * - [pairingUi] – currently open pairing dialog state.
 *
 * The combine() upstream guarantees the UI always sees a *consistent*
 * snapshot – i.e. it never flashes a half-updated state where the device
 * list is current but the presence map is stale.
 */
class DevicesViewModel(
    private val deviceService: DeviceAppService,
    private val discoveryCoordinator: DiscoveryCoordinator,
    private val heartbeatService: HeartbeatService,
    private val manualIpProbe: ManualIpProbe,
    private val localIdentityProvider: LocalIdentityProvider
) : ViewModel() {

    private val _pairingUi = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val pairingUi: StateFlow<PairingUiState> = _pairingUi.asStateFlow()

    private val _uiState = MutableStateFlow(DevicesUiState.EMPTY)
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        // Refresh the paired list once on creation so we have something on
        // the very first frame; subsequent refreshes are driven by pairing /
        // revocation events.
        viewModelScope.launch { deviceService.refreshPaired() }

        combine(
            deviceService.observeDevices(),
            discoveryCoordinator.devices,
            heartbeatService.presence,
            _pairingUi
        ) { paired, discovered, presence, pairing ->
            buildState(paired, discovered, presence, pairing)
        }.onEach { _uiState.value = it }.launchIn(viewModelScope)
    }

    // ---- UI actions ----

    fun beginPairing(target: DiscoveredDevice) {
        viewModelScope.launch {
            try {
                val descriptor = deviceService.initiatePairing(target)
                _pairingUi.value = PairingUiState.AwaitingPin(
                    target = target,
                    descriptor = descriptor
                )
            } catch (t: Throwable) {
                Napier.w("beginPairing failed", t)
                _pairingUi.value = PairingUiState.Failed(message = t.message ?: "Pairing failed")
            }
        }
    }

    fun submitPin(pin: String) {
        val current = _pairingUi.value as? PairingUiState.AwaitingPin ?: return
        viewModelScope.launch {
            // Pick the first reachable address from the discovery payload.
            // The pinned HTTPS client below will reject the handshake if the
            // peer's leaf cert doesn't match the fingerprint we recorded at
            // discovery time (T1.4).
            val addr = current.target.addresses.firstOrNull()
            if (addr == null) {
                _pairingUi.value = PairingUiState.Failed(message = "No reachable address for ${current.target.alias}")
                return@launch
            }

            val peerBaseUrl = "https://${addr.host}:${addr.port}"
            val outcome = deviceService.confirmPairing(
                sessionId = current.descriptor.sessionId,
                pin = pin,
                peerBaseUrl = peerBaseUrl,
                peerAlias = current.target.alias
            )

            _pairingUi.value = if (outcome.isSuccess) PairingUiState.Succeeded(current.target)
            else PairingUiState.Failed(outcome.exceptionOrNull()?.message ?: "Wrong PIN")
        }
    }

    fun cancelPairing() {
        val current = _pairingUi.value
        if (current is PairingUiState.AwaitingPin) {
            viewModelScope.launch { deviceService.cancelPairing(current.descriptor.sessionId) }
        }
        _pairingUi.value = PairingUiState.Idle
    }

    fun dismissPairingResult() {
        _pairingUi.value = PairingUiState.Idle
    }

    fun revoke(deviceId: DeviceId) {
        viewModelScope.launch {
            deviceService.revokeTrust(deviceId)
        }
    }

    fun probeManual(host: String, port: Int) {
        viewModelScope.launch {
            val peer = manualIpProbe.probe(host = host, port = port)
            if (peer != null) discoveryCoordinator.addManual(peer)
            else Napier.w("manual IP probe $host:$port did not respond")
        }
    }

    // ---- state assembly ----

    private fun buildState(
        paired: List<Device>,
        discovered: List<DiscoveredDevice>,
        presence: Map<DeviceId, OnlineState>,
        pairing: PairingUiState
    ): DevicesUiState {
        // De-dup: if a discovered device is already in the paired list,
        // suppress it from the "discoveries" panel to avoid double rows.
        val pairedIds = paired.map { it.id }.toSet()
        val unpaired = discovered.filter { it.deviceId !in pairedIds }

        return DevicesUiState(
            paired = paired.map { dev ->
                PairedDeviceRow(
                    device = dev,
                    online = presence[dev.id]?.let { it is OnlineState.Online } ?: false
                )
            },
            discovered = unpaired,
            pairing = pairing,
            selfAlias = localIdentityProvider.current().alias
        )
    }
}

// ---- UI state types ----

/** Top-level UI state for the Devices screen. */
data class DevicesUiState(
    val paired: List<PairedDeviceRow>,
    val discovered: List<DiscoveredDevice>,
    val pairing: PairingUiState,
    val selfAlias: String
) {
    companion object {
        val EMPTY = DevicesUiState(
            paired = emptyList(),
            discovered = emptyList(),
            pairing = PairingUiState.Idle,
            selfAlias = ""
        )
    }
}

data class PairedDeviceRow(
    val device: Device,
    val online: Boolean
)

/** Pairing dialog Lifecycle (design doc §11.3). */
sealed class PairingUiState {
    /** Dialog is closed. */
    data object Idle : PairingUiState()

    /** Dialog is open; we're showing our local PIN and waiting for the peer's PIN. */
    data class AwaitingPin(
        val target: DiscoveredDevice,
        val descriptor: PairingSessionDescriptor
    ) : PairingUiState()

    /** Pairing finished – render a success toast then auto-dismiss. */
    data class Succeeded(val peer: DiscoveredDevice) : PairingUiState()

    /** Pairing failed – wrong PIN, expired, network error. */
    data class Failed(val message: String) : PairingUiState()
}