package com.kfilesync.mobile.domain.port

import com.kfilesync.mobile.domain.model.DeviceAddress
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.Fingerprint

/**
 * Self-description published by the local device into the mDNS / NSD / NWBrowser
 * advertisement payload. Phase 1 (T1.2) wires this up to real lansync v1 TXT
 * records so the desktop client can discover us.
 */
data class DeviceInfo(
    val deviceId: DeviceId,
    val alias: String,
    val platform: DevicePlatform,
    val fingerprint: Fingerprint,
    val port: Int = 53317,
    val protocolVersion: Int = 1
)

/**
 * A device observed on the LAN (not yet trusted). Fingerprint is captured at
 * discovery time and pinned at pairing time to defend against MITM.
 */
data class DiscoveredDevice(
    val deviceId: DeviceId,
    val alias: String,
    val platform: DevicePlatform,
    val fingerprint: Fingerprint,
    val addresses: List<DeviceAddress>
)

/**
 * Driven port for LAN discovery. Adapters:
 * - AndroidNsdDiscovery (NsdManager, androidMain)
 * - IosNWBrowserDiscovery (NWBrowser/NWListener, iosMain)
 *
 * Phase 1 (T1.2) provides the real adapters. Phase 0 only exposes the contract.
 */
interface DiscoveryProvider {

    suspend fun announce(info: DeviceInfo)

    suspend fun listen(onDiscovered: (DiscoveredDevice) -> Unit)

    suspend fun stop()
}