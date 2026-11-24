package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.application.dto.PairConfirmDto
import com.kfilesync.mobile.application.dto.PairRequestDto
import com.kfilesync.mobile.application.dto.PairResultDto
import com.kfilesync.mobile.application.dto.PairRevokeDto
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.domain.DomainError
import com.kfilesync.mobile.domain.event.PairingCompleted
import com.kfilesync.mobile.domain.event.PairingRequestReceived
import com.kfilesync.mobile.domain.event.TrustRevoked
import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.DeviceType
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.model.Nonce
import com.kfilesync.mobile.domain.model.PairingCode
import com.kfilesync.mobile.domain.model.PairingDirection
import com.kfilesync.mobile.domain.model.PairingSession
import com.kfilesync.mobile.domain.model.SessionExpiry
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.PairingRequestRepository
import com.kfilesync.mobile.domain.service.PairingSessionState
import com.kfilesync.mobile.domain.service.PairingStateMachine
import com.kfilesync.mobile.infrastructure.crypto.HashProvider
import com.kfilesync.mobile.infrastructure.crypto.PemUtils
import com.kfilesync.mobile.infrastructure.crypto.SecureRng
import com.kfilesync.mobile.infrastructure.crypto.nextHex
import com.kfilesync.mobile.infrastructure.crypto.nextIntBelow
import com.kfilesync.mobile.infrastructure.crypto.toHexLower
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pairing application service (T1.3 + T1.4 cert exchange).
 *
 * Hardened against issue #10 - true two-channel OOB ceremony:
 *
 * - Each side generates its **own** PIN locally and displays it.
 * - `/pair/request` carries identity + fingerprint, NOT a PIN.
 * - User reads each side's PIN to the other (the OOB channel).
 * - `/pair/confirm` carries the PIN the originator *typed* (= the PIN the
 *   *peer displayed). The peer validates against its own locally-stored PIN.
 *
 * The previous design had the originator send its PIN to the peer in
 * `/pair/request`, which meant anyone who could POST to the peer could set
 * the expected PIN - bypassing the OOB user step entirely.
 *
 * Phase 1 happy path (outgoing, this device initiates):
 * 1. `initiateOutgoing(peer)` - generate OUR PIN (displayed on this device),
 * persist a PairingSession (direction = Outgoing). POST `/pair/request`
 * to the peer; peer creates its own session with its own PIN.
 * 2. User reads peer's PIN to us and we type it. `submitOutgoingConfirm`
 * POSTs `/pair/confirm` to the peer with that typed PIN; peer
 * validates against its own stored PIN. On success the peer echoes
 * its cert PEM, we record the peer as Paired.
 *
 * Happy path (incoming):
 * 1. `/pair/request` lands -> `receiveIncoming` generates OUR PIN and
 * persists a session (direction = Incoming).
 * 2. User reads our PIN to the originator; originator's `/pair/confirm`
 * lands -> `confirmIncoming` validates the supplied PIN against our
 * session's PIN. On match we record the originator as Paired and
 * echo our cert PEM.
 *
 * Both directions reach the same final state:
 * - peer recorded as `DeviceState.Paired(certificatePem = peerCert)`
 * - PairingCompleted event published
 * - PinnedTrustSnapshot picks up the new fingerprint via its event-bus
 *   subscriber
 * - TrustBootstrapState flips `ever_paired = true` (via SecurityHandler)
 *
 * All random values (PIN, nonce, session id) come from [SecureRng] -
 * the OS CSPRNG. The old `kotlin.random.Random` calls were not
 * cryptographically secure (issues #47, #48, #58).
 */
class PairingService(
    private val pairingRepo: PairingRequestRepository,
    private val deviceRepo: DeviceRepository,
    private val eventBus: EventBus,
    private val localIdentityProvider: LocalIdentityProvider,
    private val httpClient: LanSyncHttpClient,
    private val clock: () -> Instant = { Clock.System.now() },
    private val pinGenerator: () -> PairingCode = { generateSecurePin() },
    private val nonceGenerator: () -> Nonce = { generateSecureNonce() },
    private val sessionIdGenerator: () -> String = { generateSecureSessionId() },
    private val stateMachine: PairingStateMachine = PairingStateMachine(),
    private val discoveryCoordinator: DiscoveryCoordinator? = null,
    private val pinnedTrustSnapshot: com.kfilesync.mobile.infrastructure.network.PinnedTrustSnapshot? = null
) {
    /** Best-effort peer address captured on incoming `/pair/request`, keyed by sessionId. */
    private val recentPeerHosts = mutableMapOf<String, String>()

    /**
     * Outbound: open a session targeted at [peer] and POST `/pair/request`.
     *
     * The returned [PairingSession] carries OUR PIN - the UI displays
     * this for the user to read to the peer.
     */
    suspend fun initiateOutgoing(peer: PairingTarget): PairingSession {
        val now = clock()
        pairingRepo.cleanupExpired(now = now)
        cancelPendingSessionsFor(peer.deviceId, now)
        val local = localIdentityProvider.current()
        val sessionId = sessionIdGenerator()
        val nonce = nonceGenerator()
        val pin = pinGenerator()
        // Delegates to core's trust::pairing_state::start_outbound for the
        // state-machine fields (expiry/attempts) - core's PreparedRequest
        // half of the result is intentionally discarded, see
        // PairingStateMachine's doc comment.
        val state = stateMachine.startOutbound(
            requestId = sessionId,
            nonce = nonce.value,
            targetDeviceId = peer.deviceId.value,
            fromDeviceId = local.deviceId.value,
            fromAlias = local.alias,
            fromPlatform = local.platform.toWire(),
            fromFingerprintHex = local.fingerprint.hex,
            ourPin = pin.digits,
            maxAttempts = PairingSession.MAX_ATTEMPTS,
            nowMs = now.toEpochMilliseconds(),
            pinTtlMs = PairingSession.DEFAULT_TTL.inWholeMilliseconds
        )
        val session = PairingSession(
            sessionId = sessionId,
            peerDeviceId = peer.deviceId,
            direction = PairingDirection.Outgoing,
            pin = pin,
            nonce = nonce,
            peerFingerprint = peer.fingerprint,
            peerAlias = peer.alias,
            peerPlatform = peer.platform,
            expiry = SessionExpiry(Instant.fromEpochMilliseconds(state.expiresAtMs)),
            attempts = state.attempts,
            maxAttempts = state.maxAttempts,
            createdAt = now
        )
        pairingRepo.save(session)
        Napier.i(message = "initiated outgoing pair session=${session.sessionId} peer=${peer.alias}")

        // Notify the peer asynchronously. Body carries identity + fingerprint
        // but NOT the PIN - the peer generates its own PIN; the user reads
        // each side's PIN to the other.
        val baseUrl = peer.baseUrl
        if (baseUrl != null) {
            val dto = PairRequestDto(
                requestId = session.sessionId,
                fromDeviceId = local.deviceId.value,
                fromAlias = local.alias,
                fromPlatform = local.platform.toWire(),
                fromFingerprint = local.fingerprint.hex,
                nonce = session.nonce.value,
                expiresAtEpochMs = session.expiry.expiresAt.toEpochMilliseconds()
            )
            val ok = httpClient.postPairRequest(baseUrl, body = dto)
            if (!ok) Napier.w(message = "/pair/request to $baseUrl rejected; user can retry from UI")
        }
        return session
    }

    /**
     * Server-side: `/pair/request` route handler delegates to this.
     *
     * Generates OUR PIN locally - the originator never gets to set it.
     */
    suspend fun receiveIncoming(request: PairRequestDto, remoteHost: String? = null): PairingSession {
        val now = clock()
        pairingRepo.cleanupExpired(now = now)
        val peerId = DeviceId(value = request.fromDeviceId)
        cancelPendingSessionsFor(peerId, now)
        val normalizedHost = normalizeRemoteHost(remoteHost)
        val pin = pinGenerator()
        val state = stateMachine.receiveIncoming(
            requestId = request.requestId,
            fromDeviceId = request.fromDeviceId,
            ourPin = pin.digits,
            maxAttempts = PairingSession.MAX_ATTEMPTS,
            nowMs = now.toEpochMilliseconds(),
            pinTtlMs = PairingSession.DEFAULT_TTL.inWholeMilliseconds
        )
        val session = PairingSession(
            sessionId = request.requestId,
            peerDeviceId = peerId,
            direction = PairingDirection.Incoming,
            pin = pin,
            nonce = Nonce(value = request.nonce),
            peerFingerprint = Fingerprint(hex = request.fromFingerprint),
            peerAlias = request.fromAlias,
            peerPlatform = DevicePlatform.fromWire(value = request.fromPlatform),
            expiry = SessionExpiry(Instant.fromEpochMilliseconds(state.expiresAtMs)),
            attempts = state.attempts,
            maxAttempts = state.maxAttempts,
            createdAt = now
        )
        if (normalizedHost != null) {
            recentPeerHosts[session.sessionId] = normalizedHost
        }
        pairingRepo.save(session)
        Napier.i(message = "received incoming pair session=${session.sessionId} from=${request.fromAlias}")
        eventBus.publish(
            event = PairingRequestReceived(
                sessionId = session.sessionId,
                peerDeviceId = session.peerDeviceId,
                peerAlias = session.peerAlias,
                peerPlatform = session.peerPlatform,
                peerFingerprint = session.peerFingerprint.hex,
                peerAddress = normalizedHost,
                pin = session.pin.digits,
                expiresAtEpochMs = session.expiry.expiresAt.toEpochMilliseconds()
            )
        )
        return session
    }

    /**
     * Outbound side: user typed the peer's PIN. Send `/pair/confirm` to the
     * peer carrying that typed PIN + our cert PEM. On success, write the
     * peer's cert (from the response) into our DeviceRepository as a Paired
     * device.
     */
    suspend fun submitOutgoingConfirm(
        sessionId: String,
        peerPin: String,
        peerBaseUrl: String,
        peerAlias: String,
        peerPlatform: DevicePlatform
    ): Result<Unit> {
        val session = pairingRepo.findById(sessionId)
            ?: return Result.failure(exception = DomainError.PermissionDenied(reason = "unknown pairing session"))
        val now = clock()
        val candidate = runCatching { PairingCode(digits = peerPin) }.getOrElse {
            return Result.failure(exception = DomainError.PermissionDenied(reason = "invalid PIN format"))
        }

        val current = session.expireIfNeeded(now)
        if (!current.isOpen) {
            if (current.status == com.kfilesync.mobile.domain.model.PairingStatus.Succeeded) {
                return Result.success(Unit)
            }
            pairingRepo.save(session = current)
            return Result.failure(exception = DomainError.InvalidStateTransition(reason = "session ${current.status}"))
        }

        val local = localIdentityProvider.current()
        val resp = httpClient.postPairConfirm(
            baseUrl = peerBaseUrl,
            body = PairConfirmDto(
                requestId = sessionId,
                pin = candidate.digits,
                certificatePem = local.certificatePem
            )
        )

        return if (resp.accepted && resp.peerCertificatePem != null) {
            val peerCert = resp.peerCertificatePem!!
            if (!certMatchesDeviceId(peerCert, current.peerDeviceId.value)) {
                Napier.w("outgoing pair session=$sessionId rejected: returned cert does not match peerDeviceId ${current.peerDeviceId.value}")
                return Result.failure(exception = DomainError.PermissionDenied(reason = "peer certificate does not match device id"))
            }
            val succeeded = current.copy(attempts = current.attempts + 1, status = com.kfilesync.mobile.domain.model.PairingStatus.Succeeded)
            pairingRepo.save(session = succeeded)
            val explicitAddr = parseBaseUrlAddress(peerBaseUrl)
            writePairedDevice(
                session = succeeded,
                certPem = peerCert,
                peerAlias = peerAlias,
                peerPlatform = peerPlatform,
                explicitAddress = explicitAddr
            )
            eventBus.publish(
                event = PairingCompleted(
                    localDevice = local.deviceId,
                    peerDevice = succeeded.peerDeviceId
                )
            )
            Napier.i(message = "outgoing pair session=$sessionId succeeded; trust established")
            Result.success(value = Unit)
        } else {
            // Double-confirm race: if our own inbound handler already finalized
            // this exact session (peer confirmed our PIN concurrently), treat as success.
            val latestSession = pairingRepo.findById(sessionId)
            val reasonLower = resp.reason.orEmpty().lowercase()
            if (latestSession?.status == com.kfilesync.mobile.domain.model.PairingStatus.Succeeded ||
                ((reasonLower.contains("no such pairing session") || reasonLower.contains("succeeded")) &&
                    deviceRepo.findById(current.peerDeviceId)?.state == DeviceState.Paired)
            ) {
                return Result.success(Unit)
            }
            // The peer rejected the PIN over HTTP - this device never verifies
            // locally (that's the peer's job on its own session), it only
            // tracks the failed-attempt count for retry/lockout bookkeeping.
            val nextAttempts = current.attempts + 1
            val next = if (nextAttempts >= current.maxAttempts) current.copy(attempts = nextAttempts, status = com.kfilesync.mobile.domain.model.PairingStatus.Failed)
            else current.copy(attempts = nextAttempts)
            pairingRepo.save(session = next)
            Result.failure(exception = DomainError.PermissionDenied(reason = resp.reason ?: "wrong PIN"))
        }
    }

    /**
     * Server-side: `/pair/confirm` route handler delegates to this.
     *
     * Validates the submitted PIN against OUR locally-stored session PIN.
     * Returns (Result, ourCertPem) so the route can echo our cert in the
     * 200 OK response.
     */
    suspend fun confirmIncoming(body: PairConfirmDto): IncomingConfirmOutcome {
        val session = pairingRepo.findById(sessionId = body.requestId)
            ?: return IncomingConfirmOutcome(
                result = Result.failure(exception = DomainError.PermissionDenied(reason = "unknown pairing session")),
                ourCertPem = null
            )
        val now = clock()
        val candidate = runCatching { PairingCode(digits = body.pin) }.getOrElse {
            return IncomingConfirmOutcome(
                result = Result.failure(exception = DomainError.PermissionDenied(reason = "invalid PIN format")),
                ourCertPem = null
            )
        }

        val current = session.expireIfNeeded(now)
        if (!current.isOpen) {
            // Idempotent double-confirm: if this exact session already succeeded
            // (e.g. user entered PINs on both sides) and the caller's cert matches
            // the paired peer's deviceId, echo our cert with success instead of 403.
            if (current.status == com.kfilesync.mobile.domain.model.PairingStatus.Succeeded &&
                certMatchesDeviceId(body.certificatePem, current.peerDeviceId.value)
            ) {
                val local = localIdentityProvider.current()
                return IncomingConfirmOutcome(
                    result = Result.success(Unit),
                    ourCertPem = local.certificatePem
                )
            }
            pairingRepo.save(session = current)
            return IncomingConfirmOutcome(
                result = Result.failure(exception = DomainError.InvalidStateTransition(reason = "session ${current.status}")),
                ourCertPem = null
            )
        }

        val (state, verdict) = stateMachine.verifyPeerPin(
            session = current.toStateMachineState(),
            providedPin = candidate.digits,
            nowMs = now.toEpochMilliseconds()
        )
        val outcome = current.applyVerdict(verdict, newAttempts = state.attempts)
        pairingRepo.save(session = outcome.session)
        return if (outcome.result.isSuccess) {
            val succeeded = outcome.session
            if (!certMatchesDeviceId(body.certificatePem, succeeded.peerDeviceId.value)) {
                Napier.w("incoming pair session=${succeeded.sessionId} rejected: cert does not match ${succeeded.peerDeviceId.value}")
                return IncomingConfirmOutcome(
                    result = Result.failure(exception = DomainError.PermissionDenied(reason = "certificate does not match requesting device id")),
                    ourCertPem = null
                )
            }
            // Use the cert PEM + alias/platform captured at receiveIncoming()
            // time and persisted on the session - PairConfirmDto no longer
            // carries them (fixes issue #12 - was hardcoded "Peer").
            val peerAlias = succeeded.peerAlias.ifBlank { "Peer" }
            val peerPlatform = succeeded.peerPlatform
            val explicitAddr = recentPeerHosts.remove(succeeded.sessionId)?.let {
                com.kfilesync.mobile.domain.model.DeviceAddress(host = it, port = 53317)
            }
            writePairedDevice(
                session = succeeded,
                certPem = body.certificatePem,
                peerAlias = peerAlias,
                peerPlatform = peerPlatform,
                explicitAddress = explicitAddr
            )
            val local = localIdentityProvider.current()
            eventBus.publish(
                event = PairingCompleted(
                    localDevice = local.deviceId,
                    peerDevice = succeeded.peerDeviceId
                )
            )
            Napier.i(message = "incoming pair session=${succeeded.sessionId} succeeded; trust established")
            IncomingConfirmOutcome(
                result = Result.success(value = Unit),
                ourCertPem = local.certificatePem
            )
        } else {
            IncomingConfirmOutcome(result = outcome.result, ourCertPem = null)
        }
    }

    /**
     * Revoke trust for [deviceId]. Cascade work (closing transfers, scrubbing
     * keys) is driven by the [TrustRevoked] event consumed by SecurityHandler.
     */
    suspend fun revoke(deviceId: DeviceId): Result<Unit> {
        val existing = deviceRepo.findById(deviceId)
            ?: return Result.failure(exception = DomainError.DeviceNotFound(deviceId))

        // Best-effort notify the peer with OUR local device_id (the revoking party).
        val local = localIdentityProvider.current()
        existing.addresses.firstOrNull()?.let { addr ->
            httpClient.postPairRevoke(
                baseUrl = "https://${addr.host}:${addr.port}",
                body = PairRevokeDto(
                    deviceId = local.deviceId.value,
                    reason = "user-initiated",
                    revokedAtMs = clock().toEpochMilliseconds()
                )
            )
        }
        deviceRepo.updateTrustStatus(deviceId, status = com.kfilesync.mobile.domain.model.TrustStatus.Revoked)
        pinnedTrustSnapshot?.refresh()
        eventBus.publish(event = TrustRevoked(deviceId = deviceId))
        Napier.i(message = "revoked trust for ${existing.alias} (${deviceId.value})")
        return Result.success(value = Unit)
    }

    /** Handle an inbound `/pair/revoke` from [peerId] without bouncing a revoke back. */
    suspend fun onRemoteRevoke(peerId: DeviceId): Result<Unit> {
        val existing = deviceRepo.findById(peerId)
            ?: return Result.failure(exception = DomainError.DeviceNotFound(peerId))
        deviceRepo.updateTrustStatus(peerId, status = com.kfilesync.mobile.domain.model.TrustStatus.Revoked)
        pinnedTrustSnapshot?.refresh()
        eventBus.publish(event = TrustRevoked(deviceId = peerId))
        Napier.i(message = "remote peer ${existing.alias} (${peerId.value}) revoked trust")
        return Result.success(value = Unit)
    }

    private fun certMatchesDeviceId(certPem: String, expectedDeviceId: String): Boolean {
        val der = PemUtils.pemToDer(certPem) ?: return false
        if (der.isEmpty()) return false
        val actual = HashProvider.sha256(der).toHexLower()
        val expected = expectedDeviceId.lowercase()
        if (actual.length != expected.length) return false
        var diff = 0
        for (i in actual.indices) {
            diff = diff or (actual[i].code xor expected[i].code)
        }
        return diff == 0
    }

    /** Cancel a pending session (user pressed cancel in the UI). */
    suspend fun cancel(sessionId: String) {
        val session = pairingRepo.findById(sessionId) ?: return
        pairingRepo.save(session.cancel())
    }

    private suspend fun writePairedDevice(
        session: PairingSession,
        certPem: String,
        peerAlias: String,
        peerPlatform: DevicePlatform,
        explicitAddress: com.kfilesync.mobile.domain.model.DeviceAddress? = null
    ) {
        val now = clock()
        // Prefer the freshest reachable address (explicit pairing endpoint or live
        // discovery) over a potentially stale persisted IP from an older Wi-Fi network.
        val existing = deviceRepo.findById(session.peerDeviceId)
        val discoveredAddrs = discoveryCoordinator?.devices?.value
            ?.firstOrNull { it.deviceId == session.peerDeviceId }
            ?.addresses
        val addresses = discoveredAddrs?.takeIf { it.isNotEmpty() }
            ?: explicitAddress?.let { listOf(it) }
            ?: existing?.addresses?.takeIf { it.isNotEmpty() }
            ?: emptyList()
        val device = Device(
            id = session.peerDeviceId,
            alias = peerAlias,
            platform = peerPlatform,           // fixes #13: was hardcoded Linux
            deviceType = inferDeviceType(peerPlatform),
            addresses = addresses,
            state = DeviceState.Paired,
            pairedAt = now,
            certificatePem = certPem
        )
        deviceRepo.save(device)
        deviceRepo.updateTrustStatus(session.peerDeviceId, status = com.kfilesync.mobile.domain.model.TrustStatus.Paired)
        pinnedTrustSnapshot?.refresh()
    }

    private suspend fun cancelPendingSessionsFor(peerId: DeviceId, now: Instant) {
        val pending = runCatching { pairingRepo.findPending(now) }.getOrDefault(emptyList())
        for (stale in pending) {
            if (stale.peerDeviceId == peerId && stale.isOpen) {
                pairingRepo.save(stale.cancel())
            }
        }
    }

    private fun normalizeRemoteHost(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.removePrefix("::ffff:").removePrefix("::FFFF:")
    }

    private fun parseBaseUrlAddress(baseUrl: String): com.kfilesync.mobile.domain.model.DeviceAddress? {
        val noScheme = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")
        if (noScheme.isBlank()) return null
        val host: String
        val port: Int
        if (noScheme.startsWith("[")) {
            val end = noScheme.indexOf(']')
            if (end <= 1) return null
            host = noScheme.substring(1, end)
            port = noScheme.substring(end + 1).removePrefix(":").toIntOrNull() ?: 53317
        } else {
            val colon = noScheme.lastIndexOf(':')
            if (colon > 0) {
                host = noScheme.substring(0, colon)
                port = noScheme.substring(colon + 1).toIntOrNull() ?: 53317
            } else {
                host = noScheme
                port = 53317
            }
        }
        return normalizeRemoteHost(host)?.let {
            com.kfilesync.mobile.domain.model.DeviceAddress(host = it, port = port)
        }
    }

    private fun inferDeviceType(platform: DevicePlatform): DeviceType = when (platform) {
        DevicePlatform.Android, DevicePlatform.IOS -> DeviceType.Mobile
        DevicePlatform.Windows, DevicePlatform.MacOS, DevicePlatform.Linux -> DeviceType.Desktop
    }

    private fun PairingSession.toStateMachineState(): PairingSessionState = PairingSessionState(
        requestId = sessionId,
        targetDeviceId = peerDeviceId.value,
        ourPin = pin.digits,
        expiresAtMs = expiry.expiresAt.toEpochMilliseconds(),
        attempts = attempts,
        maxAttempts = maxAttempts
    )
}

/**
 * Minimal target descriptor for an outgoing pairing initiation. Built from
 * a [com.kfilesync.mobile.domain.port.DiscoveredDevice] (mDNS) or a
 * [ManualIpProbe] hit. We deliberately don't take a full DiscoveredDevice
 * here so callers can construct one without a fingerprint when re-pairing.
 */
data class PairingTarget(
    val deviceId: DeviceId,
    val alias: String,
    val platform: DevicePlatform,
    val fingerprint: Fingerprint,
    val baseUrl: String?
)

/** Returned by [PairingService.confirmIncoming] so the route can echo our cert. */
data class IncomingConfirmOutcome(
    val result: Result<Unit>,
    val ourCertPem: String?
)

// --------- secure random helpers ---------

private fun generateSecurePin(): PairingCode {
    val n = SecureRng.nextIntBelow(bound = 1_000_000)
    return PairingCode(digits = n.toString().padStart(length = 6, padChar = '0'))
}

private fun generateSecureNonce(): Nonce = Nonce(value = SecureRng.nextHex(length = 16))

private fun generateSecureSessionId(): String = SecureRng.nextHex(length = 8)