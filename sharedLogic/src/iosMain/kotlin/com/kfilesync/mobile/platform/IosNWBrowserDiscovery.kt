package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.DeviceInfo
import com.kfilesync.mobile.domain.port.DiscoveredDevice
import com.kfilesync.mobile.domain.port.DiscoveryProvider

/**
 * iOS implementation of [DiscoveryProvider] using `NWBrowser` / `NWListener`
 * from the Network framework (advertised on `_lansync._tcp`).
 *
 * Phase 1 (T1.2). Requires `NSBonjourServices: _lansync._tcp` and the
 * `NSLocalNetworkUsageDescription` keys in Info.plist; the iosApp shell
 * already declares both.
 */
class IosNWBrowserDiscovery : DiscoveryProvider {

    override suspend fun announce(info: DeviceInfo): Unit = TODO("Phase 1 T1.2")

    override suspend fun listen(onDiscovered: (DiscoveredDevice) -> Unit): Unit = TODO("Phase 1 T1.2")

    override suspend fun stop(): Unit = TODO("Phase 1 T1.2")
}