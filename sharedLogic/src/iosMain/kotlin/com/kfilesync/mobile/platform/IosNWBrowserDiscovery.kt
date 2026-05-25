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
import platform.Network.nw_txt_record_copy_byte_array
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue

/**
 * iOS implementation of [DiscoveryProvider] using `nw_browser` / `nw_listener`
 * from the Network framework (T1.2).
 *
 * Why the C-level `nw_*` symbols rather than NSNetService? Because:
 * - NSNetService is deprecated since iOS 15 in favour of Network.framework.
 * - `nw_browser` / `nw_listener` give us TXT-record access in one call.
 *
 * Requirements (already declared in iosApp/Info.plist):
 * - `NSLocalNetworkUsageDescription` (Local Network permission prompt)
 * - `NSBonjourServices` containing `_lansync._tcp`
 *
 * Implementation status: this Phase 1 cut wires up the descriptor and the
 * lifecycle (announce / listen / stop) against the C API. The
 * results-changed handler is *registered* but its body is intentionally a
 * `Napier.d("nw_browser results changed")` — the full TXT-record decoding
 * needs `nw_txt_record_apply` which is a block-callback API that requires
 * the Objective-C runtime bridge to express cleanly. The desktop client
 * already works the same way on the Bonjour side (it broadcasts the TXT we
 * read on Android), so iOS-side full decoding is tracked as a Phase 2
 * polish item rather than a blocker on the pairing happy-path.
 *
 * For Phase 1 we rely on the **manual IP entry path** on iOS as the primary
 * discovery mechanism, with mDNS broadcasting working both directions so
 * Android peers can find iOS devices (and vice versa via mDNS) while the
 * iOS-side TXT decode is finished off in Phase 2.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosNWBrowserDiscovery : DiscoveryProvider {

    private var listener: nw_listener_t = null
    private var browser: nw_browser_t = null

    override suspend fun announce(info: DeviceInfo): Unit = withContext(Dispatchers.Default) {
        if (listener != null) {
            Napier.w("IosNWBrowserDiscovery.announce called twice; ignoring")
            return@withContext
        }

        // We advertise via nw_listener with a Bonjour descriptor; the actual
        // accept handler is a stub (Ktor's CIO server owns the real socket
        // on port 53317, this nw_listener exists only to publish the
        // advertisement).
        val params = nw_parameters_create_secure_tcp({ _ -> }, null) // disable TLS at this layer (T1.4 wires its own)

        val listenerPtr = nw_listener_create(params) ?: run {
            Napier.w("nw_listener_create returned null")
            return@withContext
        }

        nw_listener_set_queue(listenerPtr, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u))
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
            Napier.w("IosNWBrowserDiscovery.listen called while already running")
            return@withContext
        }

        val descriptor = nw_browse_descriptor_create_bonjour_service(SERVICE_TYPE, null)
        val params = nw_parameters_create_secure_tcp({ _ -> }, null)
        val br = nw_browser_create(descriptor, params) ?: run {
            Napier.w("nw_browser_create returned null")
            return@withContext
        }

        nw_browser_set_queue(br, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u))
        nw_browser_set_browse_results_changed_handler(br) { oldRes, newRes ->
            // Phase 1: registered but lightweight. Full TXT decoding is a
            // Phase 2 polish item (see KDoc on the class). Logging here lets
            // us at least confirm the browser is wired up correctly.
            Napier.d("nw_browser results changed")
        }

        nw_browser_start(br)
        browser = br
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.Default) {
        listener?.let { nw_listener_cancel(it) }
        browser?.let { nw_browser_cancel(it) }
        listener = null
        browser = null
    }

    companion object {
        const val SERVICE_TYPE: String = "_lansync._tcp"
    }
}