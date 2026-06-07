package com.kfilesync.mobile

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.kfilesync.mobile.domain.port.PlatformFile
import com.kfilesync.mobile.platform.AndroidDirectoryPicker
import com.kfilesync.mobile.platform.AndroidFilePicker
import com.kfilesync.mobile.ui.App
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Android entry-point Activity.
 *
 * Phase 0 scaffold hosted the shared Compose `App()` for smoke-testing.
 * Phase 1 (T1.8) wired the full nav host. Phase 2 (T2.2) adds the SAF
 * launcher plumbing for the file picker:
 *
 * - The 'AndroidFilePicker' is a long-lived singleton bound in Koin's
 * androidModule. It exposes a `SharedFlow<PickRequest>` that fires
 * whenever sharedLogic's TransferService calls `pickFiles()`.
 * - We `registerForActivityResult(OpenMultipleDocuments)` at Activity
 * creation (mandatory - the API forbids registering later) and bridge
 * the result back into the picker via `completeRequest()`.
 * - On result we hydrate each URI into a [PlatformFile] by querying
 * OpenableColumns for the display name and size - both fields are
 * needed by the chunk planner before the chunk loop starts.
 *
 * We also call `takePersistableUriPermission` on each chosen URI so a
 * future "resume after restart" path can re-open the same source URI.
 * Without this, SAF revokes permission as soon as our process is killed.
 */
class MainActivity : ComponentActivity() {

    private val filePicker: AndroidFilePicker by inject()
    private val directoryPicker: AndroidDirectoryPicker by inject()

    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val files = uris.mapNotNull { uri -> uri.toPlatformFile() }
        // Persist permission for resume - best-effort; some content providers
        // (e.g. Google Drive) refuse persistable grants and throw.
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure { Napier.w("takePersistableUriPermission failed for uri: ${it.message}") }
        }
        filePicker.completeRequest(files)
    }

    /**
     * SAF 'OPEN_DOCUMENT_TREE' launcher (T3.2). Used by the share-accept
     * flow to let the user pick a destination directory for an incoming
     * share. We immediately persist the tree URI's permission so the share's
     * local mapping survives a process restart.
     */
    private val treeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure { Napier.w("takePersistableUriPermission for tree URI failed: ${it.message}") }
        }
        directoryPicker.completeRequest(uri?.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Drain the picker's request flow - every TransferService.sendFiles()
        // call ultimately reaches us here. The contract takes a String[] of
        // MIME type filters; "*/*" lets the user pick any document type.
        lifecycleScope.launch {
            filePicker.requests.collectLatest { req ->
                runCatching { safLauncher.launch(req.mimeTypes.toTypedArray()) }
                    .onFailure {
                        Napier.w("safLauncher.launch failed", it)
                        filePicker.completeRequest(emptyList())
                    }
            }
        }

        // Drain directory-picker requests (T3.2). The contract takes a hint URI
        // for the initial directory; passing null defaults to the system picker root.
        lifecycleScope.launch {
            directoryPicker.requests.collectLatest {
                runCatching { treeLauncher.launch(null) }
                    .onFailure {
                        Napier.w("treeLauncher.launch failed", it)
                        directoryPicker.completeRequest(null)
                    }
            }
        }

        setContent { App() }
    }

    /** Resolve a SAF content URI into the cross-platform [PlatformFile] descriptor. */
    private fun Uri.toPlatformFile(): PlatformFile? = runCatching {
        var name = "file"
        var size = 0L
        contentResolver.query(this, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
            }
        }
        PlatformFile(
            displayName = name,
            sizeBytes = size,
            locator = this.toString(),
            mimeType = contentResolver.getType(this)
        )
    }.getOrNull()
}