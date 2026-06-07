package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.ContentHash
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.EntryType
import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.port.DirectoryScanner
import com.kfilesync.mobile.domain.port.FileIndexRepository
import com.kfilesync.mobile.domain.port.FileSource
import com.kfilesync.mobile.domain.port.HashPort
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Result of a full or incremental index pass (T4.1).
 *
 * - [added]: entries that were not previously indexed.
 * - [modified]: entries whose mtime + size changed (we then rehashed and
 * bumped the version vector).
 * - [deleted]: entries that disappeared from the filesystem since last
 * scan; marked as tombstones.
 * - [unchanged]: existing rows whose mtime + size matched the stored
 * values — no rehash, no version-vector bump.
 *
 * The orchestration layer ([SyncService]) reports these via the sync UI
 * and feeds [added] / [modified] / [deleted] into the next push half of the
 * sync session.
 */
data class IndexerResult(
    val added: List<FileEntry>,
    val modified: List<FileEntry>,
    val deleted: List<FileEntry>,
    val unchanged: List<FileEntry>
)

/**
 * Indexer domain service (design doc §14 T4.1).
 *
 * Responsibilities:
 * 1. Walk the share's local mirror via [DirectoryScanner].
 * 2. Apply [ignore] rules to skip platform cruft + user-defined ignores.
 * 3. Detect added / modified / deleted entries against the persisted index.
 * 4. Rehash modified files (BLAKE3 per chunk + SHA-256 whole-file).
 * 5. Persist the new index in a single batched transaction.
 *
 * The indexer is the *one* place that turns on-disk bytes into [FileEntry]
 * rows; [SyncService] consumes the [IndexerResult] and never touches the
 * filesystem directly. This separation keeps the sync orchestration
 * testable with a FakeDirectoryScanner + FakeFileSource.
 *
 * **Chunk size**: 1 MiB by default — enough to amortize the per-read syscall
 * cost while keeping memory bounded on low-end devices. Phase 5 may tune
 * this per file size.
 */
class Indexer(
    private val fileIndexRepository: FileIndexRepository,
    private val directoryScanner: DirectoryScanner,
    private val fileSource: FileSource,
    private val ignore: IgnoreSpec,
    private val hashPort: HashPort,
    private val clock: () -> Instant = { Clock.System.now() },
    private val rehashChunkSize: Int = DEFAULT_REHASH_CHUNK
) {

    /**
     * Run a full scan of [shareId] at [rootLocator]. Compares against the
     * persisted index; rehashes modified files; tombstones deleted ones;
     * batches the resulting upserts in one transaction.
     *
     * [me] is the local device id, stamped into every version-vector bump.
     */
    suspend fun fullScan(shareId: ShareId, rootLocator: String, me: DeviceId): IndexerResult {
        val now = clock()

        // 1. Walk the filesystem + apply ignores.
        val scanned = directoryScanner.scan(rootLocator).filterNot { entry ->
            ignore.shouldIgnore(entry.relativePath, entry.isDirectory)
        }
        val scannedByPath = scanned.associateBy { it.relativePath }

        // 2. Snapshot the persisted index (including tombstones so re-creates work).
        val persisted = (fileIndexRepository.getIndex(shareId) + fileIndexRepository.getTombstones(shareId))
            .associateBy { it.path }

        val added = mutableListOf<FileEntry>()
        val modified = mutableListOf<FileEntry>()
        val deleted = mutableListOf<FileEntry>()
        val unchanged = mutableListOf<FileEntry>()

        // 3. Pass 1: scanned-side classification (added / modified / unchanged).
        for ((path, entry) in scannedByPath) {
            val prior = persisted[path]
            if (entry.isDirectory) {
                // Directories don't get hashed; record presence only on first sight.
                if (prior == null) {
                    val fresh = FileEntry.newLocal(
                        shareId = shareId,
                        path = path,
                        me = me,
                        size = 0L,
                        sha256 = null,
                        blocks = emptyList(),
                        entryType = EntryType.Directory,
                        now = now
                    )
                    added += fresh
                } else if (prior.deleted) {
                    // Resurrected directory — model as modified so the version vector bumps.
                    val resurrected = prior.copy(
                        deleted = false,
                        deletedAt = null,
                        modifiedAt = now,
                        modifiedBy = me,
                        versionVector = prior.versionVector.increment(me),
                        updatedAt = now
                    )
                    modified += resurrected
                } else {
                    unchanged += prior
                }
                continue
            }

            if (prior == null) {
                val hashed = hashFile(shareId, path, entry.locator, entry.sizeBytes, me, now)
                added += hashed
            } else if (prior.deleted) {
                // Resurrected file: rehash, then merge prior tombstone vector
                // and bump the local counter so the result is STRICTLY newer than
                // the tombstone. Without the bump, max(prior, fresh=1) = prior
                // peers would classify the resurrection as "unchanged" instead
                // of "new version".
                val hashed = hashFile(shareId, path, entry.locator, entry.sizeBytes, me, now)
                val resurrectedVec = prior.versionVector.merge(hashed.versionVector).increment(me)
                modified += hashed.copy(versionVector = resurrectedVec)
            } else if (isUnchangedQuickCheck(prior, entry)) {
                unchanged += prior
            } else {
                // Mtime or size changed: rehash and bump.
                val hashed = hashFile(shareId, path, entry.locator, entry.sizeBytes, me, now)
                val newVec = prior.versionVector.increment(me)
                modified += hashed.copy(versionVector = newVec)
            }
        }

        // 4. Pass 2: persisted-side classification (deletions).
        for ((path, prior) in persisted) {
            if (prior.deleted) continue // already a tombstone, skip
            if (scannedByPath.containsKey(path)) continue
            // Was indexed, isn't on disk: tombstone it.
            deleted += prior.markDeleted(me, now)
        }

        // 5. Batch persist (added + modified + deleted) in one transaction.
        val toWrite = added + modified + deleted
        if (toWrite.isNotEmpty()) {
            fileIndexRepository.upsertEntriesBatch(toWrite)
        }

        Napier.i(
            "indexer share=${shareId.value.take(8)}: " +
                    "+${added.size} ~${modified.size} -${deleted.size} =${unchanged.size}"
        )

        return IndexerResult(added = added, modified = modified, deleted = deleted, unchanged = unchanged)
    }

    /**
     * Cheap mtime+size check: if both match the persisted row, skip the
     * rehash. Mtime resolution on Android (SAF) is second-precision; we
     * tolerate that by comparing on millisecond boundaries.
     */
    private fun isUnchangedQuickCheck(
        prior: FileEntry,
        scanned: com.kfilesync.mobile.domain.port.ScannedEntry
    ): Boolean {
        if (prior.size != scanned.sizeBytes) return false
        val priorMs = prior.modifiedAt?.toEpochMilliseconds()
        val scannedMs = scanned.lastModifiedEpochMs
        if (priorMs == null || scannedMs == null) return false
        // Allow 1-second slop to absorb FAT-style mtime granularity on
        // external storage.
        return kotlin.math.abs(priorMs - scannedMs) <= 1_000L
    }

    /**
     * Hash a single file: walks [FileSource.readChunk] from chunk 0 to N-1,
     * feeds bytes into a streaming SHA-256 (whole-file) and a per-chunk
     * BLAKE3. Result is a fresh [FileEntry] stamped with the local device's
     * version-vector entry (counter = 1).
     */
    private suspend fun hashFile(
        shareId: ShareId,
        path: String,
        locator: String,
        size: Long,
        me: DeviceId,
        now: Instant
    ): FileEntry {
        // Empty file: no chunks; sha256 is the empty-input SHA-256.
        if (size == 0L) {
            return FileEntry.newLocal(
                shareId = shareId,
                path = path,
                me = me,
                size = 0L,
                sha256 = ContentHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
                blocks = listOf(emptyChunkBlake3Hex()),
                entryType = EntryType.File,
                now = now
            )
        }

        val sha = hashPort.newSha256()
        val blocks = mutableListOf<String>()
        val buf = ByteArray(rehashChunkSize)
        val chunkHasher = hashPort.newBlake3()
        var read = 0L
        var chunkIndex = 0

        try {
            while (read < size) {
                val n = fileSource.readChunk(locator, chunkIndex, rehashChunkSize, buf)
                if (n <= 0) break
                sha.update(buf, 0, n)
                chunkHasher.reset()
                chunkHasher.update(buf, 0, n)
                blocks += chunkHasher.hexLower()
                read += n.toLong()
                chunkIndex += 1
            }
        } finally {
            runCatching { fileSource.close(locator) }
        }

        return FileEntry.newLocal(
            shareId = shareId,
            path = path,
            me = me,
            size = read,
            sha256 = ContentHash(sha.hexLower()),
            blocks = blocks,
            entryType = EntryType.File,
            now = now
        )
    }

    companion object {
        /** Default rehash chunk size (1 MiB) — see class KDoc. */
        const val DEFAULT_REHASH_CHUNK: Int = 1_048_576

        /**
         * Cached BLAKE3 of an empty input — used for zero-byte files.
         * This is the well-known BLAKE3 hash of an empty byte sequence.
         */
        private const val EMPTY_BLAKE3: String =
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"

        private fun emptyChunkBlake3Hex(): String = EMPTY_BLAKE3
    }
}