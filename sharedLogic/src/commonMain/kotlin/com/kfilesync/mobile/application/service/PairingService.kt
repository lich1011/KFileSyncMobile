package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.application.dto.PairConfirmDto
import com.kfilesync.mobile.application.dto.PairRequestDto
import com.kfilesync.mobile.application.dto.PairResultDto
import com.kfilesync.mobile.application.dto.PairRevokeDto
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.domain.DomainError
import com.kfilesync.mobile.domain.event.PairingCompleted
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
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.PairingRequestRepository
import com.kfilesync.mobile.infrastructure.crypto.SecureRng
import com.kfilesync.mobile.infrastructure.crypto.nextHex
import com.kfilesync.mobile.infrastructure.crypto.nextIntBelow
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
 * peer displayed). The peer validates against its own locally-stored PIN.
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
 * subscriber
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
    private val sessionIdGenerator: () -> String = { generateSecureSessionId() }
) {

    /**
     * Outbound: open a session targeted at [peer] and POST `/pair/request`.
     *
     * The returned [PairingSession] carries OUR PIN - the UI displays
     * this for the user to read to the peer.
     */
    suspend fun initiateOutgoing(peer: PairingTarget): PairingSession {
        pairingRepo.cleanupExpired(now = clock())
        val local = localIdentityProvider.current()
        val session = PairingSession.newPending(
            sessionId = sessionIdGenerator(),
            peerDeviceId = peer.deviceId,
            direction = PairingDirection.Outgoing,
            pin = pinGenerator(),
            nonce = nonceGenerator(),
            peerFingerprint = peer.fingerprint,
            createdAt = clock()
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
    suspend fun receiveIncoming(request: PairRequestDto): PairingSession {
        pairingRepo.cleanupExpired(now = clock())
        val session = PairingSession.newPending(
            sessionId = request.requestId,
            peerDeviceId = DeviceId(value = request.fromDeviceId),
            direction = PairingDirection.Incoming,
            pin = pinGenerator(),
            nonce = Nonce(value = request.nonce),
            peerFingerprint = Fingerprint(hex = request.fromFingerprint),
            createdAt = clock(),
            ttl = PairingSession.DEFAULT_TTL
        )
        pairingRepo.save(session)
        Napier.i(message = "received incoming pair session=${session.sessionId} from=${request.fromAlias}")
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
            pairingRepo.save(session = current)
            return Result.failure(exception = DomainError.InvalidStateTransition(reason = "session ${current.status}"))
        }

        val local = localIdentityProvider.current()
        val resp = httpClient.postPairConfirm(
            baseUrl = peerBaseUrl,
            body = PairConfirmDto(
                requestId = sessionId,
                pin = candidate.digits,
                certificatePem = local.certificatePem,
                fromAlias = local.alias,
                fromPlatform = local.platform.toWire()
            )
        )

        return if (resp.ok && resp.peerCertificatePem != null) {
            val peerCert = resp.peerCertificatePem
            val succeeded = current.copy(status = com.kfilesync.mobile.domain.model.PairingStatus.Succeeded)
            pairingRepo.save(session = succeeded)
            writePairedDevice(session = succeeded, certPem = peerCert, peerAlias, peerPlatform)
            eventBus.publish(
                event = PairingCompleted(
                    localDevice = local.deviceId,
                    peerDevice = succeeded.peerDeviceId
                )
            )
            Napier.i(message = "outgoing pair session=$sessionId succeeded; trust established")
            Result.success(value = Unit)
        } else {
            // Persist decrement on PIN reject from peer.
            pairingRepo.save(session = current.decrementAttempt())
            Result.failure(exception = DomainError.PermissionDenied(reason = resp.error ?: "wrong PIN"))
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

        val outcome = session.submitPin(candidate, now)
        return if (outcome.isSuccess) {
            val succeeded = outcome.getOrThrow()
            pairingRepo.save(session = succeeded)
            // Use the cert PEM + alias from the incoming request so we record a
            // useful display name (fixes issue #12 - was hardcoded "Peer").
            val peerAlias = body.fromAlias.ifBlank { "Peer" }
            val peerPlatform = DevicePlatform.fromWire(value = body.fromPlatform)
            writePairedDevice(session = succeeded, certPem = body.certificatePem, peerAlias, peerPlatform)
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
            pairingRepo.save(session.expireIfNeeded(now).let { if (it.isOpen) it.decrementAttempt() else it })
            IncomingConfirmOutcome(result = outcome.map { Unit }, ourCertPem = null)
        }
    }

    /**
     * Revoke trust for [deviceId]. Cascade work (closing transfers, scrubbing
     * keys) is driven by the [TrustRevoked] event consumed by SecurityHandler.
     */
    suspend fun revoke(deviceId: DeviceId): Result<Unit> {
        val existing = deviceRepo.findById(deviceId)
            ?: return Result.failure(exception = DomainError.DeviceNotFound(deviceId))

        // Best-effort notify the peer. Failure shouldn't block local revocation.
        existing.addresses.firstOrNull()?.let { addr ->
            httpClient.postPairRevoke(
                baseUrl = "https://${addr.host}:${addr.port}",
                body = PairRevokeDto(deviceId = deviceId.value, reason = "user-initiated")
            )
        }
        deviceRepo.updateTrustStatus(deviceId, status = com.kfilesync.mobile.domain.model.TrustStatus.Revoked)
        eventBus.publish(event = TrustRevoked(deviceId = deviceId))
        Napier.i(message = "revoked trust for ${existing.alias} (${deviceId.value})")
        return Result.success(value = Unit)
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
        peerPlatform: DevicePlatform
    ) {
        val now = clock()
        // Preserve any addresses already known for this device (e.g. from
        // discovery), so the heartbeat can immediately start pinging.
        val existing = deviceRepo.findById(session.peerDeviceId)
        val addresses = existing?.addresses ?: emptyList()
        val device = Device(
            id = session.peerDeviceId,
            alias = peerAlias,
            platform = peerPlatform,            // fixes #13: was hardcoded Linux
            deviceType = inferDeviceType(peerPlatform),
            addresses = addresses,
            state = DeviceState.Paired(certificatePem = certPem, pairedAt = now)
        )
        deviceRepo.save(device)
        deviceRepo.updateTrustStatus(session.peerDeviceId, status = com.kfilesync.mobile.domain.model.TrustStatus.Paired)
    }

    private fun inferDeviceType(platform: DevicePlatform): DeviceType = when (platform) {
        DevicePlatform.Android, DevicePlatform.IOS -> DeviceType.Mobile
        DevicePlatform.Windows, DevicePlatform.MacOS, DevicePlatform.Linux -> DeviceType.Desktop
    }
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

// -------- secure random helpers --------

private fun generateSecurePin(): PairingCode {
    val n = SecureRng.nextIntBelow(bound = 1_000_000)
    return PairingCode(digits = n.toString().padStart(length = 6, padChar = '0'))
}

private fun generateSecureNonce(): Nonce = Nonce(value = SecureRng.nextHex(length = 16))

private fun generateSecureSessionId(): String = SecureRng.nextHex(length = 8)