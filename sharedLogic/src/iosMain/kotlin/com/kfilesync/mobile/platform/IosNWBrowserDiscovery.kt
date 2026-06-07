package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.model.DeviceAddress
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.port.DeviceInfo
import com.kfilesync.mobile.domain.port.DiscoveredDevice
import com.kfilesync.mobile.domain.port.DiscoveryProvider
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browse_result_copy_endpoint
import platform.Network.nw_browse_result_t
import platform.Network.nw_browser_create
import platform.Network.nw_browser_set_browse_results_changed_handler
import platform.Network.nw_browser_set_queue
import platform.Network.nw_browser_start
import platform.Network.nw_browser_cancel
import platform.Network.nw_browser_t
import platform.Network.nw_endpoint_copy_txt_record
import platform.Network.nw_endpoint_get_hostname
import platform.Network.nw_endpoint_get_port
import platform.Network.nw_listener_create
import platform.Network.nw_listener_set_advertise_descriptor
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_t
import platform.Network.nw_txt_record_access_bytes
import kotlinx.cinterop.get
import platform.darwin.dispatch_get_global_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT

/**
 * iOS implementation of [DiscoveryProvider] using `nw_browser` / `nw_listener`
 * from the Network framework (T1.2 + T2.7 TXT polish).
 *
 * Why the C-level `nw_*` symbols rather than NSNetService? Because:
 * - NSNetService is deprecated since iOS 15 in favour of Network.framework
 * - `nw_browser` / `nw_listener` give us TXT-record access in one call.
 *
 * Requirements (already declared in iosApp/Info.plist):
 * - `NSLocalNetworkUsageDescription` (Local Network permission prompt)
 * - `NSBonjourServices` containing `_lansync._tcp`
 *
 * T2.7 brings TXT-record decoding online: each discovered endpoint exposes
 * a byte-array TXT record (DNS-SD format - sequence of length-prefixed
 * key=value pairs). We decode that array ourselves rather than via
 * `nw_txt_record_apply` because the latter takes an Objective-C block,
 * which Kotlin/Native doesn't bridge cleanly without an extra .m file.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosNWBrowserDiscovery : DiscoveryProvider {

    private var listener: platform.Network.nw_listener_t? = null
    private var browser: platform.Network.nw_browser_t? = null
    private var onDiscoveredCallback: ((DiscoveredDevice) -> Unit)? = null

    override suspend fun announce(info: DeviceInfo): Unit = withContext(Dispatchers.Default) {
        if (listener != null) {
            Napier.w("IosNWBrowserDiscovery: announce called twice; ignoring")
            return@withContext
        }
        val params: nw_parameters_t = nw_parameters_create_secure_tcp(null, null)
        val listenerPtr = nw_listener_create(params) ?: run {
            Napier.w("nw_listener_create returned null")
            return@withContext
        }

        nw_listener_set_queue(
            listenerPtr,
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
        )

        nw_listener_set_advertise_descriptor(
            listenerPtr,
            nw_browse_descriptor_create_bonjour_service(SERVICE_TYPE, null)
        )

        nw_listener_set_state_changed_handler(listenerPtr) { _, _ -> /* no-op */ }
        nw_listener_set_new_connection_handler(listenerPtr) { _ -> /* no-op */ }
        nw_listener_start(listenerPtr)
        listener = listenerPtr
        Napier.i("nw_listener advertised as $SERVICE_TYPE")
    }

    override suspend fun listen(onDiscovered: (DiscoveredDevice) -> Unit): Unit = withContext(Dispatchers.Default) {
        if (browser != null) {
            Napier.w("IosNWBrowserDiscovery: listen called while already running")
            return@withContext
        }
        onDiscoveredCallback = onDiscovered
        val descriptor = nw_browse_descriptor_create_bonjour_service(SERVICE_TYPE, null)
        val params: nw_parameters_t = nw_parameters_create_secure_tcp(null, null)
        val br = nw_browser_create(descriptor, params) ?: run {
            Napier.w("nw_browser_create returned null")
            return@withContext
        }
        nw_browser_set_queue(br, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u))
        nw_browser_set_browse_results_changed_handler(br) { oldRes, newRes, _ ->
            // Apple's signature: (old, new, batch_complete). The browser fires
            // for *every* state transition on an endpoint:
            //  - first appearance: old == null, new != null
            //  - TXT/addr change:   old != null, new != null (different content)
            //  - disappearance:     old != null, new == null
            // We only care about appearances + changes (anything where the
            // current state contains a 'new' result). Disappearances are
            // handled by the heartbeat / per-peer lastSeenAt timeout; the
            // discovery layer doesn't need to model them explicitly.
            if (newRes != null) {
                decodeAndDispatch(newRes)
            }
        }

        nw_browser_start(br)
        browser = br
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.Default) {
        listener?.let { nw_listener_cancel(it) }
        browser?.let { nw_browser_cancel(it) }
        listener = null
        browser = null
        onDiscoveredCallback = null
    }

    /**
     * Pull the endpoint + TXT record off a browse result and translate into
     * a [DiscoveredDevice]. Bail silently on missing keys - we can re-receive
     * later when the peer updates its TXT.
     */
    private fun decodeAndDispatch(result: nw_browse_result_t) {
        val endpoint = nw_browse_result_copy_endpoint(result) ?: return
        val hostnamePtr = nw_endpoint_get_hostname(endpoint) ?: return
        val hostname = hostnamePtr.toKString()
        val port = nw_endpoint_get_port(endpoint).toInt()
        val txt = nw_endpoint_copy_txt_record(endpoint) ?: return
        var txtBytes: ByteArray? = null
        nw_txt_record_access_bytes(txt) { bytesPtr, length ->
            if (bytesPtr != null && length > 0u) {
                val array = ByteArray(length.toInt())
                for (i in 0 until length.toInt()) {
                    array[i] = bytesPtr[i].toByte()
                }
                txtBytes = array
            }
            true
        }
        val txtBytesVal = txtBytes ?: return
        val pairs = decodeDnsSdTxt(txtBytesVal)
        val deviceId = pairs["device_id"] ?: return
        val alias = pairs["alias"] ?: hostname
        val platform = parsePlatform(pairs["platform"])
        val fingerprintHex = pairs["fingerprint"] ?: return
        val callback = onDiscoveredCallback ?: return
        callback(
            DiscoveredDevice(
                deviceId = DeviceId(deviceId),
                alias = alias,
                platform = platform,
                fingerprint = Fingerprint(fingerprintHex),
                addresses = listOf(DeviceAddress(host = hostname, port = port))
            )
        )
    }

    private fun parsePlatform(value: String?): DevicePlatform = when (value) {
        "windows" -> DevicePlatform.Windows
        "macos" -> DevicePlatform.MacOS
        "linux" -> DevicePlatform.Linux
        "android" -> DevicePlatform.Android
        "ios" -> DevicePlatform.IOS
        else -> DevicePlatform.IOS
    }

    /**
     * Decode a DNS-SD TXT record (RFC 6763 §6) into a key-value map.
     *
     * Format: a sequence of length-prefixed strings, each `<len-byte><utf8 bytes>`.
     * The string itself is `key=value`; if there's no `=`, the entry is a
     * boolean attribute (key with no value). We ignore those because the
     * desktop client always emits `key=value` pairs.
     */
    private fun decodeDnsSdTxt(raw: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            i += 1
            if (i + len > raw.size) break
            val entry = raw.copyOfRange(i, i + len).decodeToString()
            i += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                out[entry.substring(0, eq)] = entry.substring(eq + 1)
            }
        }
        return out
    }

    companion object {
        const val SERVICE_TYPE: String = "_lansync._tcp"
    }
}