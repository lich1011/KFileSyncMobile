package com.kfilesync.mobile.infrastructure.persistence

import com.kfilesync.mobile.db.Devices
import com.kfilesync.mobile.db.KFileSyncDatabase
import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceAddress
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DevicePlatform
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.DeviceType
import com.kfilesync.mobile.domain.model.TrustStatus
import com.kfilesync.mobile.domain.port.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * SQLDelight-backed implementation of [DeviceRepository].
 *
 * The on-disk schema (Device.sq) uses snake_case strings for enums and JSON
 * for the list-of-addresses column, matching the desktop's SQLite layout so
 * a future "import from desktop" flow can read either side's database.
 *
 * Every method dispatches I/O off the caller's thread via `Dispatchers.Default`
 * so domain code never blocks the UI loop. The actual sqldelight drivers
 * (AndroidSqliteDriver / NativeSqliteDriver) are themselves synchronous; the
 * dispatcher hop keeps that detail out of the call site.
 */
class SqlDelightDeviceRepo(
    private val db: KFileSyncDatabase,
    private val json: Json = DEFAULT_JSON
) : DeviceRepository {

    override suspend fun findById(id: DeviceId): Device? = withContext(Dispatchers.Default) {
        db.deviceQueries.findById(id.value)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun findPaired(): List<Device> = withContext(Dispatchers.Default) {
        db.deviceQueries.findPaired()
            .executeAsList()
            .map { it.toDomain() }
    }

    override suspend fun findAll(): List<Device> = withContext(Dispatchers.Default) {
        db.deviceQueries.findPaired()
            .executeAsList()
            .map { it.toDomain() }
    }

    override suspend fun save(device: Device): Long = withContext(Dispatchers.Default) {
        val now = Clock.System.now().toEpochMilliseconds()
        val (trustStatus, pairedAt) = trustColumns(device.state)
        db.deviceQueries.insert(
            device_id = device.id.value,
            alias = device.alias,
            platform = platformToWire(device.platform),
            device_type = deviceTypeToWire(device.deviceType),
            certificate_pem = (device.state as? DeviceState.Paired)?.certificatePem ?: "",
            trust_status = trustStatus,
            addresses = addressesToJsonOrNull(device.addresses),
            paired_at = pairedAt,
            last_seen_at = now,
            created_at = now
        ).value
    }

    override suspend fun updateTrustStatus(id: DeviceId, status: TrustStatus): Long = withContext(Dispatchers.Default) {
        val pairedAt = if (status == TrustStatus.Paired) Clock.System.now().toEpochMilliseconds() else null
        db.deviceQueries.updateTrustStatus(
            trust_status = trustStatusToWire(status),
            paired_at = pairedAt,
            device_id = id.value
        ).value
    }

    override suspend fun updateLastSeen(
        id: DeviceId,
        lastSeenAt: Instant,
        addresses: List<DeviceAddress>?
    ): Long = withContext(Dispatchers.Default) {
        // The schema lets us update last_seen_at and addresses together; we
        // pass either the new list (re-encoded) or null to leave the column
        // untouched. SQLDelight will pass null straight through to SQLite.
        db.deviceQueries.updateLastSeen(
            last_seen_at = lastSeenAt.toEpochMilliseconds(),
            addresses = addresses?.let { addressesToJsonOrNull(it) },
            device_id = id.value
        ).value
    }

    override suspend fun delete(id: DeviceId): Long = withContext(Dispatchers.Default) {
        db.deviceQueries.delete(device_id = id.value).value
    }

    // ------------------ row -> domain mapping ------------------

    private fun Devices.toDomain(): Device {
        val addrs = addresses?.let { parseAddressList(it) } ?: emptyList()
        val state = buildState(trust_status, certificate_pem, paired_at)
        return Device(
            id = DeviceId(device_id),
            alias = alias,
            platform = platformFromWire(platform),
            deviceType = deviceTypeFromWire(device_type),
            addresses = addrs,
            state = state
        )
    }

    private fun buildState(trustStatus: String, certPem: String, pairedAt: Long?): DeviceState = when (trustStatus) {
        "paired" -> DeviceState.Paired(
            certificatePem = certPem,
            pairedAt = Instant.fromEpochMilliseconds(pairedAt ?: 0L)
        )
        "revoked" -> DeviceState.Revoked(
            revokedAt = Instant.fromEpochMilliseconds(pairedAt ?: 0L)
        )
        else -> DeviceState.Discovered(
            discoveredAt = Instant.fromEpochMilliseconds(pairedAt ?: 0L)
        )
    }

    private fun trustColumns(state: DeviceState): Pair<String, Long?> = when (state) {
        is DeviceState.Discovered -> "discovered" to state.discoveredAt.toEpochMilliseconds()
        is DeviceState.Paired -> "paired" to state.pairedAt.toEpochMilliseconds()
        is DeviceState.Revoked -> "revoked" to state.revokedAt.toEpochMilliseconds()
    }

    private fun addressesToJsonOrNull(addresses: List<DeviceAddress>): String? {
        if (addresses.isEmpty()) return null else return json.encodeToString(
            ListSerializer(DeviceAddressDto.serializer()),
            addresses.map { DeviceAddressDto(it.host, it.port) }
        )
    }

    private fun parseAddressList(raw: String): List<DeviceAddress> =
        runCatching {
            json.decodeFromString(ListSerializer(DeviceAddressDto.serializer()), raw)
                .map { DeviceAddress(it.host, it.port) }
        }.getOrDefault(emptyList())

    companion object {
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

@Serializable
private data class DeviceAddressDto(val host: String, val port: Int)

private fun platformToWire(p: DevicePlatform): String = when (p) {
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
    else -> throw IllegalArgumentException("unknown DevicePlatform: $value")
}

private fun deviceTypeToWire(t: DeviceType): String = when (t) {
    DeviceType.Desktop -> "desktop"
    DeviceType.Mobile -> "mobile"
}

private fun deviceTypeFromWire(value: String): DeviceType = when (value) {
    "desktop" -> DeviceType.Desktop
    "mobile" -> DeviceType.Mobile
    else -> throw IllegalArgumentException("unknown DeviceType: $value")
}

private fun trustStatusToWire(s: TrustStatus): String = when (s) {
    TrustStatus.Discovered -> "discovered"
    TrustStatus.Paired -> "paired"
    TrustStatus.Revoked -> "revoked"
}