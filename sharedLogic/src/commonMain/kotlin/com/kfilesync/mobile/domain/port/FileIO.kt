package com.kfilesync.mobile.domain.port

/**
 * A platform-neutral handle to a file the user picked. Concrete adapters
 * resolve it to an Android `Uri` / iOS `NSURL` at the application boundary.
 *
 * `locator` is the opaque platform handle that [FileSource] / [FileSink]
 * understand: a content URI on Android, a security-scoped bookmark on iOS.
 *
 * Defined here (domain port) rather than in the application service layer
 * because both [FilePicker] (domain port) and [TransferAppService]
 * (application service) depend on it — keeping it in the port avoids a
 * reverse dependency from domain → application.
 */
data class PlatformFile(
    val displayName: String,
    val sizeBytes: Long,
    val locator: String,
    val mimeType: String? = null
)

/**
 * Platform-neutral file picker (T2.2).
 *
 * Android impl: wraps the SAF (Storage Access Framework) document picker.
 * iOS impl: wraps `UIDocumentPickerViewController`.
 *
 * Both return a list of [PlatformFile] descriptors whose [PlatformFile.locator]
 * is the platform-opaque handle (content:// URI string on Android,
 * security-scoped bookmark base64 on iOS) that the corresponding [FileSource]
 * adapter uses to actually read the bytes.
 *
 * Suspending so the UI thread can `await` the user's choice without blocking.
 * Returns an empty list when the user cancels.
 */
interface FilePicker {
    suspend fun pickFiles(allowMultiple: Boolean = true): List<PlatformFile>
}

/**
 * Platform-neutral directory picker (T3.2).
 *
 * Used by the share-accept flow to let the user choose where on disk the
 * share's local mirror should live. Android wraps `OPEN_DOCUMENT_TREE`;
 * iOS presents `UIDocumentPickerViewController(forOpeningContentTypes:
 * [.folder])`. Both return an opaque platform locator string (SAF tree
 * URI on Android, security-scoped bookmark on iOS) which the receiver-
 * side [FileSink] already knows how to consume as `targetDirectory`.
 *
 * Returns null when the user cancels.
 */
interface DirectoryPicker {
    suspend fun pickDirectory(): String?
}

/**
 * One discovered entry from a [DirectoryScanner.scan]. The [locator] is
 * the platform-opaque handle (SAF document URI / iOS file:// URL) which
 * [FileSource] later uses to read the bytes for hashing.
 *
 * `relativePath` is the share-root-relative POSIX path used for index keys
 * and IgnoreSpec matching - always forward-slash separated even on Android
 * (where the underlying URI may not use slashes at all).
 */
data class ScannedEntry(
    val locator: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long?
)

/**
 * Platform-neutral directory walker (T4.1).
 *
 * Walks a directory rooted at [rootLocator] (SAF tree URI on Android,
 * security-scoped bookmark on iOS) and yields every entry (files +
 * directories). The traversal is recursive and breadth-first; directories
 * are emitted *before* their children so the indexer can short-circuit on
 * ignored dirs without reading their contents.
 *
 * Lives in domain port land so the indexer can be unit-tested with a
 * `FakeDirectoryScanner` that returns an in-memory tree.
 */
interface DirectoryScanner {
    /**
     * Walk [rootLocator] recursively. The returned list is in deterministic
     * order (sorted by [ScannedEntry.relativePath]) so two scans of the same
     * tree produce identical indexes - important for the version-vector
     * "only push on change" logic.
     */
    suspend fun scan(rootLocator: String): List<ScannedEntry>

    /**
     * Resolve a share-relative POSIX path to its platform locator. Used by
     * the sync engine to hand a [FileEntry] to the transfer service for the
     * push half of a sync session. Returns null if the file no longer exists.
     */
    suspend fun resolve(rootLocator: String, relativePath: String): ScannedEntry?
}

/**
 * Platform-neutral source-side byte reader (T2.2).
 *
 * The sender pipeline calls [readChunk] in order from chunk 0 to chunk N-1.
 * Implementations are responsible for opening the underlying URI / bookmark
 * once and seeking on subsequent calls - `readChunk(0, ...)` on the first
 * call, then `readChunk(1, ...)`, etc.
 *
 * `chunkSize == 0` (small-file path) is signalled by [readWhole].
 */
interface FileSource {
    /** Total file size in bytes. Cached after the first call. */
    suspend fun size(locator: String): Long

    /** Read one chunk by index. Returns the actual byte count read (<= chunkSize). */
    suspend fun readChunk(locator: String, chunkIndex: Int, chunkSize: Int, destination: ByteArray): Int

    /** Read the entire file (small-file path; chunkSize == 0). */
    suspend fun readWhole(locator: String): ByteArray

    /** Close the underlying stream / bookmark, if any. */
    suspend fun close(locator: String)
}

/**
 * Platform-neutral sink-side byte writer (T2.3).
 *
 * The receiver pipeline calls [openTemp] once to get a temp-file locator,
 * then [writeChunk] N times in order. On completion, [finalize] atomically
 * moves the temp file to its final location (and returns the final URI/path).
 * On cancellation, [discard] deletes the temp file.
 *
 * Android impl writes to `Context.cacheDir` until finalize, then copies into
 * the user's SAF tree URI. iOS impl writes to `NSTemporaryDirectory()`,
 * then moves into the security-scoped Documents folder.
 */
interface FileSink {
    /** Returns a platform-specific locator for an empty temp file. */
    suspend fun openTemp(suggestedName: String): String

    /** Append one chunk at [offset]. */
    suspend fun writeChunk(locator: String, offset: Long, data: ByteArray, length: Int)

    /**
     * Move the temp file to its final destination. Returns the final
     * platform locator. The receiver UI gets to choose [targetDirectory]
     * (also platform-opaque; on Android it's an SAF tree URI).
     */
    suspend fun finalize(tempLocator: String, targetDirectory: String?, fileName: String): String

    /** Delete the temp file (transfer cancelled / failed). */
    suspend fun discard(locator: String)
}