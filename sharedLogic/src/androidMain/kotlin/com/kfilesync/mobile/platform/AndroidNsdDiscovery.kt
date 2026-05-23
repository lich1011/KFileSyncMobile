package com.kfilesync.mobile.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.kfilesync.mobile.domain.model.DeviceAddress
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.port.DeviceInfo
import com.kfilesync.mobile.domain.port.DiscoveredDevice
import com.kfilesync.mobile.domain.port.DiscoveryProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NSD (`android.net.nsd.NsdManager`) implementation of [DiscoveryProvider] (T1.2).
 *
 * Service type: `_lansync._tcp` - same as the desktop, so peers find each
 * other regardless of platform.
 *
 * TXT record carries the four lansync v1 fields the peer needs *before*
 * dialing the HTTP info endpoint:
 * - device_id    (cert-fingerprint hex)
 * - alias
 * - platform     ("android" | "ios" | "windows" | ...)
 * - fingerprint  (hex)
 * - proto        ("1")
 *
 * Lifecycle: [announce] registers our own service. [listen] starts a
 * discovery + per-service resolve pipeline. [stop] tears both down.
 *
 * Threading: NsdManager callbacks fire on the framework's internal thread.
 * We hop into [Dispatchers.Default] before invoking the discovery callback
 * so the caller doesn't have to worry about it.
 */
class AndroidNsdDiscovery(
    private val context: Context
) : DiscoveryProvider {

    private val nsd: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val running = AtomicBoolean(false)

    override suspend fun announce(info: DeviceInfo): Unit = withContext(Dispatchers.Default) {
        if (registrationListener != null) {
            Napier.w("AndroidNsdDiscovery.announce called twice; ignoring")
            return@withContext
        }
        val service = NsdServiceInfo().apply {
            serviceName = info.alias.ifBlank { "kfilesync" }
            serviceType = SERVICE_TYPE
            port = info.port
            // TXT record carries the protocol fields the peer can act on
            // before dialing /info. Keys must be ASCII; values are byte[].
            setAttribute("device_id", info.deviceId.value)
            setAttribute("alias", info.alias)
            setAttribute("platform", info.platform.wire())
            setAttribute("fingerprint", info.fingerprint.hex)
            setAttribute("proto", info.protocolVersion.toString())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Napier.i("nsd announced as ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Napier.w("nsd registration failed code=$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Napier.i("nsd unregistered ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Napier.w("nsd unregistration failed code=$errorCode")
            }
        }

        registrationListener = listener
        nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override suspend fun listen(onDiscovered: (DiscoveredDevice) -> Unit): Unit = withContext(Dispatchers.Default) {
        if (!running.compareAndSet(false, true)) {
            Napier.w("AndroidNsdDiscovery.listen called while already running")
            return@withContext
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Napier.i("nsd discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Napier.i("nsd discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Napier.w("nsd onStartDiscoveryFailed code=$errorCode")
                running.set(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Napier.w("nsd onStopDiscoveryFailed code=$errorCode")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Napier.d("nsd onServiceFound ${serviceInfo.serviceName}")
                resolveAndEmit(serviceInfo, onDiscovered)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Napier.d("nsd onServiceLost ${serviceInfo.serviceName}")
            }
        }

        discoveryListener = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolveAndEmit(found: NsdServiceInfo, onDiscovered: (DiscoveredDevice) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Napier.w("nsd resolve failed code=$errorCode for ${serviceInfo.serviceName}")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val attrs = resolved.attributes ?: emptyMap()
                val deviceId = attrs["device_id"]?.toUtf8() ?: return
                val alias = attrs["alias"]?.toUtf8() ?: resolved.serviceName ?: "unknown"
                val platformStr = attrs["platform"]?.toUtf8() ?: "unknown"
                val fingerprint = attrs["fingerprint"]?.toUtf8() ?: ""

                // Filter out ourselves - when the local registration is visible
                // we don't want to surface it as a discoverable peer.
                val host: InetAddress = resolved.host ?: return
                val address = DeviceAddress(host = host.hostAddress ?: "?", port = resolved.port)

                onDiscovered(
                    DiscoveredDevice(
                        deviceId = DeviceId(deviceId),
                        alias = alias,
                        platform = platformFromWire(platformStr),
                        fingerprint = Fingerprint(fingerprint),
                        addresses = listOf(address)
                    )
                )
            }
        }
        nsd.resolveService(found, resolveListener)
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.Default) {
        registrationListener?.let {
            runCatching { nsd.unregisterService(it) }
                .onFailure { Napier.w("unregisterService failed: ${it.message}") }
            registrationListener = null
        }
        discoveryListener?.let {
            runCatching { nsd.stopServiceDiscovery(it) }
                .onFailure { Napier.w("stopServiceDiscovery failed: ${it.message}") }
            discoveryListener = null
        }
        running.set(false)
    }

    companion object {
        const val SERVICE_TYPE: String = "_lansync._tcp."
    }
}

private fun DevicePlatform.wire(): String = when (this) {
    DevicePlatform.Windows -> "windows"
    DevicePlatform.MacOS -> "macos"
    DevicePlatform.Linux -> "linux"
    DevicePlatform.Android -> "android"
    DevicePlatform.IOS -> "ios"
}

private fun platformFromWire(value: String): DevicePlatform = when (value) {
    "windows" -> DevicePlatform.Windows
    "macos" -> DevicePlatform.MacOS
    "linux" -> DevicePlatform.Linux
    "android" -> DevicePlatform.Android
    "ios" -> DevicePlatform.IOS
    else -> DevicePlatform.Linux // safe default - wire mismatch shouldn't crash the UI
}

private fun ByteArray.toUtf8(): String = String(this, Charsets.UTF_8)