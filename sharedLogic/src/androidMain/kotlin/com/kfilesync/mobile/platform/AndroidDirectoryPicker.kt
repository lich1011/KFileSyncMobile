package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.DirectoryPicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Android `DirectoryPicker` (T3.2).
 *
 * Same deferred / Activity-injected pattern as [AndroidFilePicker]: the
 * common code suspends on `pickDirectory()`; the Activity drains [requests]
 * and launches the SAF `ACTION_OPEN_DOCUMENT_TREE` intent, then calls
 * [completeRequest] with the chosen tree URI (or `null` on cancel).
 *
 * The tree URI is exactly what the receive-side [AndroidFileSink] already
 * accepts as `targetDirectory` - `DocumentFile.fromTreeUri(...).createFile`.
 * The Activity also calls `takePersistableUriPermission` so the share's
 * local mapping survives process death.
 *
 * MainActivity wires this up in `onCreate`:
 * ```kotlin
 * val dirPicker by inject<AndroidDirectoryPicker>()
 * val launcher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
 * uri?.let { contentResolver.takePersistableUriPermission(it, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION) }
 * dirPicker.completeRequest(uri?.toString())
 * }
 * lifecycleScope.launch { dirPicker.requests.collect { launcher.launch(null) } }
 * ```
 */
class AndroidDirectoryPicker : DirectoryPicker {

    /** Sentinel - payload is irrelevant; presence on the flow means "open the picker". */
    data object PickRequest

    /** Activity collects this; emits one [PickRequest] per `pickDirectory()` call. */
    val requests: MutableSharedFlow<PickRequest> = MutableSharedFlow(extraBufferCapacity = 8)

    private var pending: CompletableDeferred<String?>? = null

    override suspend fun pickDirectory(): String? {
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        requests.tryEmit(PickRequest)
        return deferred.await()
    }

    /** Called by MainActivity from the SAF result callback. `null` on cancel. */
    fun completeRequest(locator: String?) {
        pending?.complete(locator)
        pending = null
    }
}