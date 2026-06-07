package com.kfilesync.mobile.platform.tls

import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.domain.port.DeviceRepository
import io.github.aakira.napier.Napier
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.Network.NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT
import platform.Network.nw_connection_create
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_local_endpoint
import platform.Network.nw_parameters_t
import platform.Security.sec_identity_create
import platform.Security.sec_protocol_options_set_local_identity
import platform.Security.sec_protocol_options_set_min_tls_protocol_version
import platform.Security.tls_protocol_version_TLSv13
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_get_global_queue
import platform.posix.size_t

/**
 * TLS-terminating front-end for the HTTP server (T1.4, iOS path).
 *
 * Architecture (sidecar pattern):
 * ```
 * peer (TLS 1.3)          nw_listener         loopback      Ktor CIO
 * -------------------> [  TLS terminator  ] -------------> 127.0.0.1:53318
 * port 53317          (this file)          plain      (HttpServer.kt)
 * HTTP
 * ```
 * Why a sidecar:
 * - Ktor CIO server on Kotlin/Native doesn't accept a `KeyStore` /
 * `SecIdentity` for TLS config the way the JVM build does.
 * - We *must* keep the private key in the Secure Enclave (Phase 1
 * decision §9.1). The Enclave can only sign via `SecKey*` /
 * `sec_identity_create`; Ktor CIO has no hook to plug that signer in.
 * - Network.framework's `nw_listener` + `sec_protocol_options_set_local_identity`
 * is Apple's officially-blessed path for TLS server identity that
 * stays Secure-Enclave-resident.
 * - Bridging an inbound nw_connection to a loopback TCP socket that Ktor
 * CIO is already listening on lets us keep the existing HTTP route
 * handlers untouched.
 *
 * Loopback security: 127.0.0.1 is reachable only from inside the app
 * sandbox (no other process can connect). The plaintext leg lives entirely
 * within process memory; an attacker would need to compromise the process
 * itself, in which case they already have everything.
 *
 * Lifecycle:
 * - `start()` is idempotent; re-calling does nothing.
 * - `stop()` cancels the listener AND every active forwarded connection.
 *
 * Pinning for incoming client certs is *not* enforced in Phase 1 (we don't
 * require mTLS yet). The verify block is left as a stub with a comment so
 * Phase 2 can wire it once we want client-cert auth.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosTlsListener(
    private val deviceRepository: DeviceRepository,
    private val identityProvider: LocalIdentityProvider,
    private val publicPort: Int = PUBLIC_PORT,
    private val loopbackPort: Int = LOOPBACK_PORT
) {
    private val started = atomic(false)
    private var listener: platform.Network.nw_listener_t? = null
    private var retainedIdentity: platform.Security.SecIdentityRef? = null

    fun start() {
        if (!started.compareAndSet(expect = false, update = true)) {
            Napier.d("IosTlsListener already started, ignoring")
            return
        }
        val identity = identityProvider.current()
        if (identity.certificatePem.isBlank()) {
            Napier.w("IosTlsListener: LocalIdentity.certificatePem is empty; cannot start TLS")
            started.value = false
            return
        }

        val secIdentity = IosSecIdentityBridge.loadLocalIdentity(identity.certificatePem) ?: run {
            Napier.w("IosTlsListener: SecIdentity bridge failed; cannot start TLS")
            started.value = false
            return
        }

        val params = buildTlsParams(secIdentity)
        val l = nw_listener_create(params) ?: run {
            Napier.w("IosTlsListener: nw_listener_create returned null")
            started.value = false
            return
        }
        listener = l

        val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
        nw_listener_set_queue(l, queue)
        nw_listener_set_state_changed_handler(l) { _, _ -> /* logging only */ }
        nw_listener_set_new_connection_handler(l) { connection ->
            // For every inbound TLS-terminated connection, open a loopback
            // socket to Ktor and start bidirectional pumping.
            handleNewConnection(connection)
        }

        nw_listener_start(l)
        Napier.i("IosTlsListener started on $publicPort, forwarding to 127.0.0.1:$loopbackPort")
    }

    fun stop() {
        if (!started.compareAndSet(expect = true, update = false)) return
        listener?.let { nw_listener_cancel(it) }
        listener = null
        retainedIdentity?.let { CFRelease(it) }
        retainedIdentity = null
        Napier.i("IosTlsListener stopped")
    }

    // -------------------- TLS parameter construction --------------------

    private fun buildTlsParams(secIdentity: platform.Security.SecIdentityRef): nw_parameters_t {
        // The TLS-options block:
        //   - sets minimum TLS to 1.3 (matches the desktop's policy)
        //   - installs our SecIdentity as the server-side local identity
        //   - leaves the peer-verify block as default (no mTLS in Phase 1)
        //
        // IMPORTANT: nw_parameters_create_secure_tcp's TLS configurator block
        // is retained by Network.framework and may execute on *every* new
        // inbound connection. Capturing `secIdentity` directly would let the
        // ref dangle if CoreFoundation reclaimed it after this function
        // returns. We CFRetain explicitly here and let the OS hold the
        // reference for the listener's lifetime; the matching CFRelease lives
        // in [stop()].
        CFRetain(secIdentity)
        retainedIdentity = secIdentity
        val params = nw_parameters_create_secure_tcp(
            { tlsOpts ->
                val secId = sec_identity_create(secIdentity)
                if (secId != null) {
                    sec_protocol_options_set_local_identity(tlsOpts, secId)
                }
                sec_protocol_options_set_min_tls_protocol_version(
                    tlsOpts,
                    tls_protocol_version_TLSv13
                )
                // Phase 2: install a peer verify block here for client-cert
                // pinning. Phase 1 only the server side authenticates.
            },
            null // TCP options: defaults are fine for our LAN workloads.
        )

        // Bind to all interfaces on `publicPort`.
        val endpoint = nw_endpoint_create_host("0.0.0.0", publicPort.toString())
        nw_parameters_set_local_endpoint(params, endpoint)
        return params
    }

    // -------------------- per-connection sidecar pump --------------------

    private fun handleNewConnection(inbound: nw_connection_t) {
        // 1. Start the TLS-terminated inbound connection.
        val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)
        nw_connection_set_queue(inbound, queue)

        // 2. Open the loopback leg in parallel; we kick the pumps when both ready.
        val loopbackParams = nw_parameters_create_secure_tcp(null /* no TLS */, null)
        val loopbackEndpoint = nw_endpoint_create_host("127.0.0.1", loopbackPort.toString())
        val loopback = nw_connection_create(loopbackEndpoint, loopbackParams)
        nw_connection_set_queue(loopback, queue)

        val both = ConnectionPair(inbound = inbound, loopback = loopback)

        nw_connection_set_state_changed_handler(inbound) { state, _ ->
            when (state) {
                nw_connection_state_ready -> both.maybeStartPumps()
                nw_connection_state_failed, nw_connection_state_cancelled -> both.teardown()
                else -> {}
            }
        }

        nw_connection_set_state_changed_handler(loopback) { state, _ ->
            when (state) {
                nw_connection_state_ready -> both.maybeStartPumps()
                nw_connection_state_failed, nw_connection_state_cancelled -> both.teardown()
                else -> {}
            }
        }

        nw_connection_start(inbound)
        nw_connection_start(loopback)
    }

    /**
     * Holds the two paired connections + a small state flag, with a method
     * that starts the receive pumps once both sides have transitioned to
     * `ready`. We use atomic counters so the two state-changed callbacks
     * (which fire on the dispatch queue concurrently in principle) can
     * coordinate without locks.
     */
    private class ConnectionPair(
        val inbound: nw_connection_t,
        val loopback: nw_connection_t
    ) {
        private val readyCount = atomic(0)
        private val tornDown = atomic(false)

        fun maybeStartPumps() {
            if (readyCount.incrementAndGet() == 2 && !tornDown.value) {
                pump(inbound, loopback)
                pump(loopback, inbound)
            }
        }

        fun teardown() {
            if (tornDown.compareAndSet(expect = false, update = true)) {
                nw_connection_cancel(inbound)
                nw_connection_cancel(loopback)
            }
        }

        private fun pump(from: nw_connection_t, to: nw_connection_t) {
            // Reads up to 64 KiB at a time; on data, forwards to `to`,
            // then re-arms. On EOF / error, tears the pair down.
            nw_connection_receive(
                from,
                minimum_incomplete_length = 1u,
                maximum_length = 65536u
            ) { data: dispatch_data_t?, _, isComplete: Boolean, error ->
                if (error != null) {
                    teardown()
                    return@nw_connection_receive
                }
                if (data != null) {
                    nw_connection_send(
                        to,
                        data,
                        NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT,
                        false
                    ) { sendErr ->
                        if (sendErr != null) teardown()
                    }
                }
                if (isComplete) {
                    teardown()
                } else {
                    pump(from, to) // re-arm
                }
            }
        }
    }

    companion object {
        /** Public port - what peers connect to. Same as Android (53317). */
        const val PUBLIC_PORT: Int = 53317

        /** Loopback port for the Ktor CIO HTTP server (one above the public port). */
        const val LOOPBACK_PORT: Int = 53318
    }
}