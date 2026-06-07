package com.kfilesync.mobile.platform

import com.kfilesync.mobile.domain.port.PlatformFile
import com.kfilesync.mobile.domain.port.FilePicker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * iOS 'FilePicker' (T2.2).
 *
 * Like the Android adapter, presenting 'UIDocumentPickerViewController'
 * requires a UIViewController, which we can't reach from 'commonMain'. The
 * iOS bootstrap ('IosBootstrap.kt') observes [requests] and presents the
 * picker on the key window's root view controller, then reports back via
 * [completeRequest].
 *
 * The Swift glue ('KFileSyncBridge.swift') drains [requests], translates
 * each one into a `UIDocumentPickerViewController(forOpeningContentTypes:)`
 * presentation, persists security-scoped bookmarks for the chosen URLs,
 * and calls [completeRequest] with [PlatformFile] entries where
 * `locator = url.absoluteString`.
 */
class IosFilePicker : FilePicker {

    data class PickRequest(val allowMultiple: Boolean, val utTypes: List<String> = listOf("public.data"))

    val requests: MutableSharedFlow<PickRequest> = MutableSharedFlow(extraBufferCapacity = 8)

    private var pending: CompletableDeferred<List<PlatformFile>>? = null

    override suspend fun pickFiles(allowMultiple: Boolean): List<PlatformFile> {
        val deferred = CompletableDeferred<List<PlatformFile>>()
        pending = deferred
        requests.tryEmit(PickRequest(allowMultiple = allowMultiple))
        return deferred.await()
    }

    /** Called by the Swift bridge when the user picks files (or cancels -> empty list). */
    fun completeRequest(files: List<PlatformFile>) {
        pending?.complete(files)
        pending = null
    }
}