# # 6. Software Engineering Methodology

[< Back to Overview](00-overview.md)

---

## 6.1 Domain-Driven Design (DDD)

### 6.1.1 Bounded Contexts Aligned with Desktop

The mobile client reuses the desktop's 5 bounded contexts to ensure domain model consistency:

### 6.1.2 Aggregate Roots and Value Objects

Domain model identical to the desktop client, implemented in Kotlin:

| Aggregate | Aggregate Root | Internal Entities | Value Objects |
| :--- | :--- | :--- | :--- |
| Device Aggregate | `Device` | - | `DeviceId`, `Fingerprint`, `DeviceAddress`, `TrustStatus` |
| PairingSession Aggregate | `PairingSession` | - | `PairingCode`, `Nonce`, `SessionExpiry` |
| Transfer Aggregate | `TransferJob` | `TransferItem` | `ChunkManifest`, `TransferProgress`, `Checkpoint` |
| Share Aggregate | `Share` | `ShareMember` | `SharePermission`, `SyncMode` |
| FileIndex Aggregate | `FileEntry` | - | `VersionVector`, `BlockList`, `ContentHash` |
| SyncSession Aggregate | `SyncSession` | `SyncPlan` | `SyncConflict`, `Tombstone` |

**Value Object Kotlin Implementation Examples:**

```kotlin
// DeviceId value object: immutable, compared by value
@JvmInline
value class DeviceId(val value: String) // SHA-256 hex of certificate DER

// VersionVector value object: immutable
data class VersionVector(val entries: Map<DeviceId, Long> = emptyMap()) {
    
    fun isAncestorOf(other: VersionVector): Boolean =
        entries.all { (device, counter) ->
            (other.entries[device] ?: 0L) >= counter
        }
        
    fun conflictsWith(other: VersionVector): Boolean =
        !isAncestorOf(other) && !other.isAncestorOf(this)
        
    fun increment(device: DeviceId): VersionVector =
        VersionVector(entries + (device to (entries[device] ?: 0L) + 1L))
        
    fun merge(other: VersionVector): VersionVector {
        val allKeys = entries.keys + other.entries.keys
        return VersionVector(
            allKeys.associateWith { key ->
                maxOf(entries[key] ?: 0L, other.entries[key] ?: 0L)
            }
        )
    }
}

// SharePermission enum value object
enum class SharePermission {
    ReadOnly, ReadWrite, SendOnly, ReceiveOnly;
    
    fun canPush(): Boolean = this == ReadWrite || this == SendOnly
    fun canPull(): Boolean = this == ReadWrite || this == ReadOnly || this == ReceiveOnly
}
```

**Domain Services (aligned with desktop):**

| Domain Service | Responsibility | Kotlin Implementation Notes |
| -------------- | -------------- |-----------------------------|
|`ConflictResolver`|Deterministic conflict resolution algorithm|"Pure function, no side effects, implemented in commonMain"|
|`SyncPlanGenerator`|Compare both sides' indexes and generate a sync plan|"Pure function, implemented in commonMain"|
|`PolicyEnforcer`|Unified authorization: device trust -> share membership -> permission check|"Specification pattern, implemented in commonMain"|

### 6.1.3 Domain Events
Same event definitions as the desktop client, implemented in Kotlin:
```kotlin
// Domain event interface
interface DomainEvent {
val eventType: String
val occurredAt: Instant
val aggregateId: String
}

// Identity Context events
data class DeviceDiscovered(
val deviceId: DeviceId,
val alias: String,
val addresses: List<DeviceAddress>,
override val occurredAt: Instant = Clock.System.now(),
override val aggregateId: String = deviceId.value
) : DomainEvent {
override val eventType = "device.discovered"
}

data class PairingCompleted(
val localDevice: DeviceId,
val peerDevice: DeviceId,
val pairedAt: Instant = Clock.System.now(),
override val occurredAt: Instant = pairedAt,
override val aggregateId: String = peerDevice.value
) : DomainEvent {
override val eventType = "pairing.completed"
}

data class TrustRevoked(
val deviceId: DeviceId,
val revokedAt: Instant = Clock.System.now(),
override val occurredAt: Instant = revokedAt,
override val aggregateId: String = deviceId.value
) : DomainEvent {
override val eventType = "trust.revoked"
}

// Transfer Context events
data class TransferCompleted(
val jobId: JobId,
val totalBytes: Long,
override val occurredAt: Instant = Clock.System.now(),
override val aggregateId: String = jobId.value
) : DomainEvent {
override val eventType = "transfer.completed"
}

data class ChunkVerificationFailed(
val jobId: JobId,
val fileId: FileId,
val chunkIndex: Int,
override val occurredAt: Instant = Clock.System.now(),
override val aggregateId: String = jobId.value
) : DomainEvent {
override val eventType = "chunk.verification_failed"
}

// Sync Context events
data class ConflictDetected(
val shareId: ShareId,
val filePath: String,
val localVersion: VersionVector,
val remoteVersion: VersionVector,
override val occurredAt: Instant = Clock.System.now(),
override val aggregateId: String = shareId.value
) : DomainEvent {
override val eventType = "conflict.detected"
}
```

---

### 6.2 Clean Architecture (Hexagonal Variant)
The mobile client uses Clean Architecture layering, aligned with the desktop's Hexagonal Architecture:

```
+-----------------------------------------------------------------------+
|  Driving Side      ┌─────────────────────────────┐      Driven Side   |
|  (Driving          │  Domain Core (Pure Kotlin)  │      (Driven       |
|   Adapters)        │  commonMain - zero platform │       Adapters)    |
|                    │  dependencies               │                    |
|  +------------+    │                             │    +------------+  |
|  | Ktor       ├───►│  Aggregates / Value Objects │───►| SQLDelight |  |
|  | Server     |    │  Domain Services / Events   │    | Repository |  |
|  +------------+    │                             │    +------------+  |
|                    │  +────────────+ +─────────+ │                    |
|  +------------+    │  | SyncEngine | |Conflict─| │    +------------+  |
|  | NsdManager |├───►│  +------------+ |Resolver | │───►| AndroidKey-|  |
|  | NWBrowser  |    │  | PolicyEnf. | +─────────+ │    | Store / iOS|  |
|  +------------+    │  +------------+ |SyncPlan─| │    | Keychain   |  |
|                    │                 |Gen      │ │    +------------+  |
|  +------------+    │                 +---------+ │                    |
|  | Compose    ├───►│  Uses Ports (interface) to  │    +------------+  |
|  | UI         |    │  talk to the outside world  │───►|FileObserver|  |
|  +------------+    │                             │    |DispatchSrc |  |
|                    │                             │    +------------+  |
|  +------------+    │                             │                    |
|  | WorkMgr /  ├───►│                             │    +------------+  |
|  | BGTask     |    │                             │───►| Ktor TLS   |  |
|  +------------+    └─────────────────────────────┘    +------------+  |
+-----------------------------------------------------------------------+
```
### 6.2.1 Port Definitions (Kotlin Interfaces)

**Driven Ports - defined in commonMain:**

```kotlin
// ---- Storage Ports ----

interface DeviceRepository {
    suspend fun findById(id: DeviceId): Device?
    suspend fun findPaired(): List<Device>
    suspend fun save(device: Device)
    suspend fun updateTrustStatus(id: DeviceId, status: TrustStatus)
}

interface ShareRepository {
    suspend fun findById(id: ShareId): Share?
    suspend fun findByMember(deviceId: DeviceId): List<Share>
    suspend fun save(share: Share)
    suspend fun addMember(shareId: ShareId, member: ShareMember)
    suspend fun removeMember(shareId: ShareId, deviceId: DeviceId)
}

interface FileIndexRepository {
    suspend fun getIndex(shareId: ShareId): List<FileEntry>
    suspend fun getIncremental(shareId: ShareId, sinceVersion: Long): List<FileEntry>
    suspend fun upsertEntry(entry: FileEntry)
    suspend fun upsertEntriesBatch(entries: List<FileEntry>)
    suspend fun findBlocksByHash(hash: String): List<BlockLocation>
}

interface TransferRepository {
    suspend fun saveJob(job: TransferJob)
    suspend fun updateProgress(jobId: JobId, progress: TransferProgress)
    suspend fun findIncompleteJobs(): List<TransferJob>
}

// ---- Infrastructure Ports ----

interface KeyStore {
    fun storePrivateKey(id: DeviceId, key: ByteArray)
    fun loadPrivateKey(id: DeviceId): ByteArray
    fun deletePrivateKey(id: DeviceId)
}

// expect/actual pattern: declared in commonMain, implemented per platform
interface FileWatcher {
    suspend fun watch(path: String, onChange: (FileEvent) -> Unit): WatchHandle
    suspend fun unwatch(handle: WatchHandle)
}

interface DiscoveryProvider {
    suspend fun announce(info: DeviceInfo)
    suspend fun listen(onDiscovered: (DiscoveredDevice) -> Unit)
    suspend fun stop()
}

interface EventBus {
    fun publish(event: DomainEvent)
    fun <T : DomainEvent> subscribe(eventType: KClass<T>, handler: (T) -> Unit)
}
```
**Driving Ports - application service interfaces:**

```kotlin
// ---- Driving Ports - application service interfaces ----

interface DeviceAppService {
    suspend fun discoverDevices(): List<DiscoveredDevice>
    suspend fun initiatePairing(target: DeviceId): PairingSession
    suspend fun confirmPairing(sessionId: String, pin: String): Result<Unit>
    suspend fun revokeTrust(deviceId: DeviceId)
    fun observeDevices(): Flow<List<Device>>
    fun observeDiscoveredDevices(): Flow<List<DiscoveredDevice>>
}

interface TransferAppService {
    suspend fun sendFiles(target: DeviceId, files: List<PlatformFile>): JobId
    suspend fun acceptTransfer(sessionId: String)
    suspend fun rejectTransfer(sessionId: String)
    suspend fun pauseTransfer(jobId: JobId)
    suspend fun resumeTransfer(jobId: JobId)
    suspend fun cancelTransfer(jobId: JobId)
    fun observeTransfers(): Flow<List<TransferJob>>
}

interface ShareAppService {
    suspend fun acceptInvitation(shareId: ShareId, localPath: String)
    suspend fun leaveShare(shareId: ShareId)
    fun observeShares(): Flow<List<Share>>
}

interface SyncAppService {
    suspend fun syncNow(shareId: ShareId, peerId: DeviceId)
    suspend fun resolveConflict(conflictId: String, resolution: ConflictResolution)
    fun observeSyncStatus(): Flow<SyncStatus>
    fun observeConflicts(): Flow<List<SyncConflict>>
}
```
### 6.2.2 Adapter Implementation Mapping

| Port (Interface) | Adapter (Implementation) | Source Set | Platform |
| --- | --- | --- | --- |
| `DeviceRepository` | `SqlDelightDeviceRepository` | commonMain | Shared |
| `ShareRepository` | `SqlDelightShareRepository` | commonMain | Shared |
| `FileIndexRepository` | `SqlDelightFileIndexRepository` | commonMain | Shared |
| `TransferRepository` | `SqlDelightTransferRepository` | commonMain | Shared |
| `KeyStore` | `AndroidKeyStoreAdapter` | androidMain | Android |
| `KeyStore` | `IosKeychainAdapter` | iosMain | iOS |
| `FileWatcher` | `AndroidFileObserverAdapter` | androidMain | Android |
| `FileWatcher` | `IosDispatchSourceAdapter` | iosMain | iOS |
| `DiscoveryProvider` | `AndroidNsdDiscovery` | androidMain | Android |
| `DiscoveryProvider` | `IosNWBrowserDiscovery` | iosMain | iOS |
| `EventBus` | `SharedFlowEventBus` | commonMain | Shared |

---

## 6.3 KMP Module Structure

*(Please refer to `00-overview.md` for the comprehensive module tree structure).*

---

## 6.4 expect/actual Pattern Usage

KMP's `expect/actual` mechanism abstracts platform differences, naturally corresponding to the Port/Adapter concept of hexagonal architecture:

```kotlin
// commonMain - expect declaration (equivalent to a Port)
expect class PlatformKeyStore() : KeyStore

// androidMain - actual implementation (equivalent to an Android Adapter)
actual class PlatformKeyStore : KeyStore {
    private val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    
    actual override fun storePrivateKey(id: DeviceId, key: ByteArray) {
        // Uses AndroidKeyStore API
    }
    
    actual override fun loadPrivateKey(id: DeviceId): ByteArray {
        // Loads from AndroidKeyStore
    }
    
    actual override fun deletePrivateKey(id: DeviceId) {
        keyStore.deleteEntry(id.value)
    }
}

// iosMain - actual implementation (equivalent to an iOS Adapter)
actual class PlatformKeyStore : KeyStore {
    actual override fun storePrivateKey(id: DeviceId, key: ByteArray) {
        // Uses Security.framework SecItemAdd
    }
    
    actual override fun loadPrivateKey(id: DeviceId): ByteArray {
        // Uses Security.framework SecItemCopyMatching
    }
    
    actual override fun deletePrivateKey(id: DeviceId) {
        // Uses Security.framework SecItemDelete
    }
}

```