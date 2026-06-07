package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.DirectoryPicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * iOS `DirectoryPicker` (T3.2).
 *
 * Mirrors [IosFilePicker]'s deferred pattern. The Swift glue
 * (`KFileSyncBridge.swift`, Phase 5) drains [requests] and presents
 * `UIDocumentPickerViewController(forOpeningContentTypes: [.folder])`
 * with `allowsMultipleSelection = false`. On selection it should:
 *
 * 1. Call `startAccessingSecurityScopedResource` on the URL.
 * 2. Create a security-scoped bookmark via `bookmarkData(options:)` and
 * base64-encode it.
 * 3. Call [completeRequest] with the bookmark string (or `null` on
 * cancel).
 *
 * The receive-side [IosFileSink] accepts that bookmark string as the
 * `targetDirectory` parameter and decodes it back to a URL on each write.
 */
class IosDirectoryPicker : DirectoryPicker {

    data object PickRequest

    val requests: MutableSharedFlow<PickRequest> = MutableSharedFlow(extraBufferCapacity = 8)

    private var pending: CompletableDeferred<String?>? = null

    override suspend fun pickDirectory(): String? {
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        requests.tryEmit(PickRequest)
        return deferred.await()
    }

    /** Called by the Swift bridge once the user picks a folder. `null` on cancel. */
    fun completeRequest(bookmark: String?) {
        pending?.complete(bookmark)
        pending = null
    }
}