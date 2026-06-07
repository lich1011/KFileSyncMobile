package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.application.dto.DeviceInfoDto
import com.kfilesync.mobile.domain.model.DeviceAddress
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.port.DiscoveredDevice
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import io.github.aakira.napier.Napier

/**
 * Function type for "go fetch /info from this base URL"; lets the probe be
 * unit-tested without spinning up a real HTTP server.
 *
 * Production binding: `LanSyncHttpClient::fetchInfo` (a `suspend (String) -> DeviceInfoDto?`).
 * Test binding: a lambda that returns canned data for the URLs the test cares about.
 */
typealias InfoFetcher = suspend (baseUrl: String) -> DeviceInfoDto?

/**
 * Manual-IP discovery probe (T1.2 fallback path).
 *
 * When mDNS doesn't work (locked-down Wi-Fi, Android missing
 * NEARBY_WIFI_DEVICES permission, iOS user denied the Local Network prompt),
 * the user can type an IP + port into the "Enter IP" dialog and we'll hit
 * 'GET /api/lansync/v1/info' on it. Parse the response, return a
 * [DiscoveredDevice] - same shape as the mDNS path, so the UI doesn't care
 * which mechanism produced it.
 *
 * Pure logic; no platform dependency. Lives in commonMain so both Android
 * and iOS reuse the same probe code.
 *
 * The fetch step is injected as a function so tests don't need a real HTTP
 * client. The default factory wires it to [LanSyncHttpClient.fetchInfo].
 */
class ManualIpProbe(
    private val fetchInfo: InfoFetcher
) {
    /** Convenience constructor for production wiring. */
    constructor(httpClient: LanSyncHttpClient) : this(fetchInfo = { url -> httpClient.fetchInfo(url) })

    /**
     * Probe `https://[host]:[port]` and return the peer's [DiscoveredDevice]
     * descriptor, or null if the peer isn't reachable / doesn't speak
     * lansync v1.
     *
     * [port] defaults to the lansync standard 53317 so the UI can take a
     * bare IP without forcing the user to think about ports.
     */
    suspend fun probe(host: String, port: Int = DEFAULT_PORT): DiscoveredDevice? {
        if (host.isBlank()) return null
        // Issue #34/#35: lansync v1 is TLS on every port; the `http://`
        // scheme used here would never reach a real peer. The pinned client
        // handles cert verification (or accepts under the bootstrap window
        // for the very first pairing) via PinnedTrustSnapshot.
        val baseUrl = "https://$host:$port"
        val info: DeviceInfoDto = fetchInfo(baseUrl) ?: run {
            Napier.d("ManualIpProbe: no /info from $baseUrl")
            return null
        }
        return info.toDiscovered(host, port)
    }

    companion object {
        const val DEFAULT_PORT: Int = 53317
    }
}

private fun DeviceInfoDto.toDiscovered(host: String, port: Int): DiscoveredDevice = DiscoveredDevice(
    deviceId = DeviceId(deviceId),
    alias = alias,
    platform = platformFromWire(platform),
    fingerprint = Fingerprint(fingerprint),
    addresses = listOf(DeviceAddress(host = host, port = port))
)

private fun platformFromWire(value: String): DevicePlatform = when (value) {
    "windows" -> DevicePlatform.Windows
    "macos" -> DevicePlatform.MacOS
    "linux" -> DevicePlatform.Linux
    "android" -> DevicePlatform.Android
    "ios" -> DevicePlatform.IOS
    else -> DevicePlatform.Linux
}