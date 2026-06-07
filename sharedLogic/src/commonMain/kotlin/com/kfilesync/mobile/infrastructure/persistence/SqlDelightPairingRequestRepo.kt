package com.kfilesync.mobile.infrastructure.persistence

import com.kfilesync.mobile.db.KFileSyncDatabase
import com.kfilesync.mobile.db.Pairing_requests
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.Fingerprint
import com.kfilesync.mobile.domain.model.Nonce
import com.kfilesync.mobile.domain.model.PairingCode
import com.kfilesync.mobile.domain.model.PairingDirection
import com.kfilesync.mobile.domain.model.PairingSession
import com.kfilesync.mobile.domain.model.PairingStatus
import com.kfilesync.mobile.domain.model.SessionExpiry
import com.kfilesync.mobile.domain.port.PairingRequestRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * SQLDelight-backed [PairingRequestRepository] (T1.6).
 *
 * The schema (PairingRequest.sq) stores both the inbound and outbound side
 * of a pairing handshake - direction is encoded as a column so we can fish
 * out either flavour with a single query.
 *
 * Lifecycle: pending rows live <= 5 min. The application service calls
 * [cleanupExpired] at boot + before initiating any new outbound session,
 * so we don't accumulate dead rows.
 */
class SqlDelightPairingRequestRepo(
    private val db: KFileSyncDatabase
) : PairingRequestRepository {

    override suspend fun save(session: PairingSession): Long = withContext(Dispatchers.Default) {
        db.pairingRequestQueries.insert(
            request_id = session.sessionId,
            peer_device_id = session.peerDeviceId.value,
            direction = directionToWire(session.direction),
            status = statusToWire(session.status),
            pin = session.pin.digits,
            peer_fingerprint = session.peerFingerprint.hex,
            nonce = session.nonce.value,
            attempts_remaining = session.attemptsRemaining.toLong(),
            created_at = session.createdAt.toEpochMilliseconds(),
            expires_at = session.expiry.expiresAt.toEpochMilliseconds()
        ).value
    }

    override suspend fun findById(sessionId: String): PairingSession? = withContext(Dispatchers.Default) {
        db.pairingRequestQueries.findById(sessionId)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun findPending(now: Instant): List<PairingSession> = withContext(Dispatchers.Default) {
        db.pairingRequestQueries.findPending(now.toEpochMilliseconds())
            .executeAsList()
            .map { it.toDomain() }
    }

    override suspend fun cleanupExpired(now: Instant): Long = withContext(Dispatchers.Default) {
        db.pairingRequestQueries.cleanup(now.toEpochMilliseconds()).value
    }

    // -------- row <-> domain --------

    private fun Pairing_requests.toDomain(): PairingSession = PairingSession(
        sessionId = request_id,
        peerDeviceId = DeviceId(peer_device_id),
        direction = directionFromWire(direction),
        pin = PairingCode(pin),
        nonce = Nonce(nonce),
        peerFingerprint = Fingerprint(peer_fingerprint),
        expiry = SessionExpiry(Instant.fromEpochMilliseconds(expires_at)),
        attemptsRemaining = attempts_remaining.toInt(),
        status = statusFromWire(status),
        createdAt = Instant.fromEpochMilliseconds(created_at)
    )

    private fun directionToWire(d: PairingDirection): String = when (d) {
        PairingDirection.Outgoing -> "outgoing"
        PairingDirection.Incoming -> "incoming"
    }

    private fun directionFromWire(value: String): PairingDirection = when (value) {
        "outgoing" -> PairingDirection.Outgoing
        "incoming" -> PairingDirection.Incoming
        else -> throw IllegalArgumentException("unknown PairingDirection: $value")
    }

    private fun statusToWire(s: PairingStatus): String = when (s) {
        PairingStatus.Pending -> "pending"
        PairingStatus.Succeeded -> "succeeded"
        PairingStatus.Failed -> "failed"
        PairingStatus.Expired -> "expired"
        PairingStatus.Cancelled -> "cancelled"
    }

    private fun statusFromWire(value: String): PairingStatus = when (value) {
        "pending" -> PairingStatus.Pending
        "succeeded" -> PairingStatus.Succeeded
        "failed" -> PairingStatus.Failed
        "expired" -> PairingStatus.Expired
        "cancelled" -> PairingStatus.Cancelled
        else -> throw IllegalArgumentException("unknown PairingStatus: $value")
    }
}