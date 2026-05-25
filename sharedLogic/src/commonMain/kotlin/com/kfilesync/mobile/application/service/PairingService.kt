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
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.DeviceType
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.model.Nonce
import com.kfilesync.mobile.domain.model.PairingCode
import com.kfilesync.mobile.domain.model.PairingDirection
import com.kfilesync.mobile.domain.model.PairingSession
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.domain.port.DiscoveredDevice
import com.kfilesync.mobile.domain.port.EventBus
import com.kfilesync.mobile.domain.port.PairingRequestRepository
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import io.github.aakira.napier.Napier
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pairing application service (T1.3 + T1.4 cert exchange).
 *
 * The Phase 1 happy path (outgoing, this device initiates):
 * 1. 'initiateOutgoing(peer)' - generate PIN + nonce, persist a
 * PairingSession (direction = Outgoing).
 * 2. 'submitOutgoingConfirm(sessionId, pin)' - when the user has typed
 * the peer's PIN locally, POST `/pair/confirm` to the peer carrying
 * our PIN + our cert PEM. The peer validates the PIN against the
 * session it created from `/pair/request`, writes us as a paired
 * device with our cert, and echoes back its own cert PEM in the
 * response. We then write the peer as paired locally with the cert
 * from the response.
 *
 * Happy path (incoming):
 * 1. `/pair/request` lands -> `receiveIncoming(request)` persists a
 * PairingSession (direction = Incoming) and surfaces it to the UI.
 * 2. `/pair/confirm` lands -> `confirmIncoming(...)` validates the PIN,
 * writes the inbound peer as paired with the cert they sent, and
 * returns the success descriptor (with our cert PEM) so the route
 * handler can echo it back.
 *
 * Both directions reach a final state of:
 * - peer recorded as `DeviceState.Paired(certificatePem = peerCert)`
 * - PairingCompleted published
 */
class PairingService(
    private val pairingRepo: PairingRequestRepository,
    private val deviceRepo: DeviceRepository,
    private val eventBus: EventBus,
    private val localIdentityProvider: LocalIdentityProvider,
    private val httpClient: LanSyncHttpClient,
    private val clock: () -> Instant = { Clock.System.now() },
    private val pinGenerator: () -> PairingCode = ::generateRandomPin,
    private val nonceGenerator: () -> Nonce = ::generateRandomNonce,
    private val sessionIdGenerator: () -> String = ::generateSessionId
) {

    /** Outbound: open a session targeted at [peer] and POST `/pair/request`. */
    suspend fun initiateOutgoing(peer: DiscoveredDevice): PairingSession {
        pairingRepo.cleanupExpired(clock())
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
        Napier.i("initiated outgoing pair session=${session.sessionId} peer=${peer.alias}")

        // Notify the peer asynchronously - the request body carries our
        // alias + fingerprint so the peer UI can display the incoming
        // request prompt. Failure here is non-fatal: the user can re-try
        // from the UI. We surface the failure via the session log only.
        val baseUrl = peer.addresses.firstOrNull()?.let { "https://${it.host}:${it.port}" }
        if (baseUrl != null) {
            val dto = PairRequestDto(
                requestId = session.sessionId,
                fromDeviceId = local.deviceId.value,
                fromAlias = local.alias,
                fromFingerprint = local.fingerprint.hex,
                pin = session.pin.digits,
                nonce = session.nonce.value,
                expiresAtEpochMs = session.expiry.expiresAt.toEpochMilliseconds()
            )
            val ok = httpClient.postPairRequest(baseUrl, dto)
            if (!ok) Napier.w("pair/request to $baseUrl rejected; user can retry from UI")
        }
        return session
    }

    /** Server-side: `/pair/request` route handler delegates to this. */
    suspend fun receiveIncoming(request: PairRequestDto): PairingSession {
        pairingRepo.cleanupExpired(clock())
        val session = PairingSession.newPending(
            sessionId = request.requestId,
            peerDeviceId = DeviceId(request.fromDeviceId),
            direction = PairingDirection.Incoming,
            pin = PairingCode(request.pin),
            nonce = Nonce(request.nonce),
            peerFingerprint = Fingerprint(request.fromFingerprint),
            createdAt = clock(),
            ttl = PairingSession.DEFAULT_TTL
        )

        pairingRepo.save(session)
        Napier.i("received incoming pair session=${session.sessionId} from=${request.fromAlias}")
        return session
    }

    /**
     * Outbound side: user typed the peer's PIN. Send `/pair/confirm` to the
     * peer with our PIN + cert. On success, write the peer's cert (from the
     * response) into our DeviceRepository as a Paired device.
     */
    suspend fun submitOutgoingConfirm(
        sessionId: String,
        pin: String,
        peerBaseUrl: String,
        peerAlias: String
    ): Result<Unit> {
        val session = pairingRepo.findById(sessionId)
            ?: return Result.failure(DomainError.PermissionDenied("unknown pairing session"))
        val now = clock()
        val candidate = PairingCode(pin)

        // The wire-side PIN check happens on the peer. Locally we validate
        // the session is still open + decrement attempts on miss; the
        // remote side does the same. We don't validate `pin` against the
        // session's PIN here - the local session stores *our* PIN that we
        // displayed; what we're submitting is *their* PIN.
        val current = session.expireIfNeeded(now)
        if (!current.isOpen) {
            pairingRepo.save(current)
            return Result.failure(DomainError.InvalidStateTransition("session ${current.status}"))
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

        return if (resp.ok && resp.peerCertificatePem != null) {
            val peerCert = resp.peerCertificatePem
            val succeeded = current.copy(status = com.kfilesync.mobile.domain.model.PairingStatus.Succeeded)
            pairingRepo.save(succeeded)
            writePairedDevice(succeeded, peerCert, peerAlias)
            eventBus.publish(
                PairingCompleted(
                    localDevice = local.deviceId,
                    peerDevice = succeeded.peerDeviceId
                )
            )
            Napier.i("outgoing pair session=$sessionId succeeded; trust established")
            Result.success(Unit)
        } else {
            // Persist decrement on PIN reject from peer.
            pairingRepo.save(current.decrementAttempt())
            Result.failure(DomainError.PermissionDenied(resp.error ?: "wrong PIN"))
        }
    }

    /**
     * Server-side: `/pair/confirm` route handler delegates to this.
     * Returns (Result, ourCertPem) so the route can echo our cert in the
     * 200 OK response.
     */
    suspend fun confirmIncoming(body: PairConfirmDto): IncomingConfirmOutcome {
        val session = pairingRepo.findById(body.requestId)
            ?: return IncomingConfirmOutcome(
                result = Result.failure(DomainError.PermissionDenied("unknown pairing session")),
                ourCertPem = null
            )

        val now = clock()
        val candidate = PairingCode(body.pin)
        val outcome = session.submitPin(candidate, now)
        return if (outcome.isSuccess) {
            val succeeded = outcome.getOrThrow()
            pairingRepo.save(succeeded)
            // Use the cert PEM from the incoming request to pin the peer.
            val peerAlias = "Peer" // PEM CN extraction is non-trivial; alias gets refreshed on next /info
            writePairedDevice(succeeded, body.certificatePem, peerAlias)
            val local = localIdentityProvider.current()
            eventBus.publish(
                PairingCompleted(
                    localDevice = local.deviceId,
                    peerDevice = succeeded.peerDeviceId
                )
            )
            Napier.i("incoming pair session=${succeeded.sessionId} succeeded; trust established")
            IncomingConfirmOutcome(
                result = Result.success(Unit),
                ourCertPem = local.certificatePem
            )
        } else {
            pairingRepo.save(session.expireIfNeeded(now).let { if (it.isOpen) it.decrementAttempt() else it })
            IncomingConfirmOutcome(result = outcome.map { }, ourCertPem = null)
        }
    }

    /**
     * Revoke trust for [deviceId]. Cascade work (closing transfers, scrubbing
     * keys) is driven by the [TrustRevoked] event consumed by SecurityHandler.
     */
    suspend fun revoke(deviceId: DeviceId): Result<Unit> {
        val existing = deviceRepo.findById(deviceId)
            ?: return Result.failure(DomainError.DeviceNotFound(deviceId))

        // Best-effort notify the peer. Failure shouldn't block local revocation.
        existing.addresses.firstOrNull()?.let { addr ->
            httpClient.postPairRevoke(
                baseUrl = "https://${addr.host}:${addr.port}",
                body = PairRevokeDto(deviceId = deviceId.value, reason = "user-initiated")
            )
        }

        deviceRepo.updateTrustStatus(deviceId, com.kfilesync.mobile.domain.model.TrustStatus.Revoked)
        eventBus.publish(TrustRevoked(deviceId = deviceId))
        Napier.i("revoked trust for ${existing.alias} (${deviceId.value})")
        return Result.success(Unit)
    }

    /** Cancel a pending session (user pressed cancel in the UI). */
    suspend fun cancel(sessionId: String) {
        val session = pairingRepo.findById(sessionId) ?: return
        pairingRepo.save(session.cancel())
    }

    private suspend fun writePairedDevice(session: PairingSession, certPem: String, peerAlias: String) {
        val now = clock()
        val device = Device(
            id = session.peerDeviceId,
            alias = peerAlias,
            platform = com.kfilesync.mobile.domain.model.DevicePlatform.Linux,
            deviceType = DeviceType.Desktop,
            addresses = emptyList(),
            state = DeviceState.Paired(certificatePem = certPem, pairedAt = now)
        )
        deviceRepo.save(device)
        deviceRepo.updateTrustStatus(session.peerDeviceId, com.kfilesync.mobile.domain.model.TrustStatus.Paired)
    }
}

/** Returned by [PairingService.confirmIncoming] so the route can echo our cert. */
data class IncomingConfirmOutcome(
    val result: Result<Unit>,
    val ourCertPem: String?
)

private fun generateRandomPin(): PairingCode {
    val n = Random.nextInt(0, 1_000_000)
    return PairingCode(n.toString().padStart(6, '0'))
}

private fun generateRandomNonce(): Nonce {
    val bytes = ByteArray(16) { Random.nextInt(256).toByte() }
    val hex = bytes.joinToString("") { ((it.toInt() and 0xFF)).toString(16).padStart(2, '0') }
    return Nonce(hex)
}

private fun generateSessionId(): String {
    val bytes = ByteArray(16) { Random.nextInt(256).toByte() }
    return bytes.joinToString("") { ((it.toInt() and 0xFF)).toString(16).padStart(2, '0') }
}