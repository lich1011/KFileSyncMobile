package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.PlatformFile
import com.kfilesync.mobile.domain.port.FilePicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Android `FilePicker` (T2.2).
 *
 * The actual SAF intent has to be launched from an Activity (it needs a
 * `registerForActivityResult` callback installed during Activity creation).
 * We can't suspend across the Activity lifecycle from `commonMain`, so this
 * adapter is split:
 *
 * - The `pickFiles()` call enqueues a request via [requests]; the
 * Activity observes that flow and launches the SAF picker.
 * - The Activity reports results via [completeRequest] which resolves the
 * pending [CompletableDeferred].
 *
 * MainActivity wires this up in `onCreate`:
 * ```kotlin
 * val picker = (application as KFileSyncApplication).get<FilePicker>() as AndroidFilePicker
 * val launcher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
 * picker.completeRequest(uris.map { /* PlatformFile from uri */ })
 * }
 * lifecycleScope.launch {
 * picker.requests.collect { req ->
 * launcher.launch(req.mimeTypes.toTypedArray())
 * }
 * }
 * ```
 *
 * If no Activity is wired up, `pickFiles()` returns an empty list after
 * [waitTimeoutMs] - keeps tests + the headless service path from blocking
 * forever.
 */
class AndroidFilePicker : FilePicker {

    data class PickRequest(
        val allowMultiple: Boolean,
        val mimeTypes: List<String> = listOf("*/*")
    )

    /** Activity collects this; emits one [PickRequest] per `pickFiles` call. */
    val requests: MutableSharedFlow<PickRequest> = MutableSharedFlow(extraBufferCapacity = 8)

    private var pending: CompletableDeferred<List<PlatformFile>>? = null

    override suspend fun pickFiles(allowMultiple: Boolean): List<PlatformFile> {
        val deferred = CompletableDeferred<List<PlatformFile>>()
        pending = deferred
        requests.tryEmit(PickRequest(allowMultiple = allowMultiple))
        return deferred.await()
    }

    /** Called by MainActivity from the SAF result callback. */
    fun completeRequest(files: List<PlatformFile>) {
        pending?.complete(files)
        pending = null
    }
}