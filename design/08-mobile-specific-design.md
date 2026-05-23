# 8. Mobile-Specific Design

💡
[< Back to Overview](00-overview.md)

---

## 8.1 Background Task Management

### Android Background Strategy

```kotlin
// WorkManager periodic sync
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val syncService: SyncAppService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            syncService.syncAllShares()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context, intervalMinutes: Long = 15) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

// Foreground service to keep transfers alive
class TransferForegroundService : Service() {
    // Creates a persistent notification showing transfer progress
    // Automatically stops the service when the transfer completes
}

```

### iOS Background Strategy

```kotlin
// iosMain
class IosBackgroundSync {
    fun registerBackgroundTasks() {
        // Register BGAppRefreshTask for periodic sync
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier = "com.kfilesync.sync",
            using = null
        ) { task ->
            handleSyncTask(task as BGAppRefreshTask)
        }

        // Register BGProcessingTask for large file transfers
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier = "com.kfilesync.transfer",
            using = null
        ) { task ->
            handleTransferTask(task as BGProcessingTask)
        }
    }

    fun scheduleSync() {
        val request = BGAppRefreshTaskRequest(identifier = "com.kfilesync.sync")
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(15 * 60.0)
        BGTaskScheduler.shared.submit(request)
    }
}

```

## 8.2 Battery and Network Awareness

```kotlin
// commonMain - sync policy interface
interface SyncPolicy {
    fun shouldSync(context: SyncContext): Boolean
    fun syncInterval(): Duration
}

data class SyncContext(
    val isWifi: Boolean,
    val isCharging: Boolean,
    val batteryLevel: Float,    // 0.0 - 1.0
    val isAppInForeground: Boolean
)

class DefaultSyncPolicy : SyncPolicy {
    override fun shouldSync(context: SyncContext): Boolean =
        context.isWifi && (context.isCharging || context.batteryLevel > 0.2f)

    override fun syncInterval(): Duration = 15.minutes
}

class AggressiveSyncPolicy : SyncPolicy {
    override fun shouldSync(context: SyncContext): Boolean =
        context.isWifi

    override fun syncInterval(): Duration = 5.minutes
}

class ChargingOnlySyncPolicy : SyncPolicy {
    override fun shouldSync(context: SyncContext): Boolean =
        context.isWifi && context.isCharging

    override fun syncInterval(): Duration = 30.minutes
}

```

## 8.3 Storage Permissions and File Access

### Android

```kotlin
// androidMain
class AndroidFileAccess {
    // Uses SAF (Storage Access Framework) to let users select sync directories
    fun requestDirectoryAccess(activity: Activity): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    // Persists directory access permissions across app restarts
    fun persistPermission(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    // Uses MediaStore or SAF to pick files for sending
    fun pickFiles(activity: Activity): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }
}

```

### iOS

```kotlin
// iosMain
class IosFileAccess {
    // Uses UIDocumentPickerViewController for file selection
    fun presentDocumentPicker(
        viewController: UIViewController,
        types: List<UTType> = listOf(UTType.item)
    ) {
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = types,
            asCopy = true // Obtains a copy of the file
        )
        viewController.presentViewController(picker, animated = true, completion = null)
    }

    // Uses Security-Scoped Bookmarks to persist directory access permissions
    fun bookmarkDirectory(url: NSURL): NSData? {
        return url.bookmarkDataWithOptions(
            NSURLBookmarkCreationWithSecurityScope,
            includingResourceValuesForKeys = null,
            relativeToURL = null,
            error = null
        )
    }
}

```

## 8.4 Notification System

```kotlin
// commonMain - notification abstraction
interface NotificationService {
    fun showTransferRequest(from: Device, files: List<FileManifest>)
    fun showTransferProgress(jobId: JobId, progress: Float)
    fun showTransferComplete(jobId: JobId, fileCount: Int)
    fun showSyncConflict(shareId: ShareId, conflictCount: Int)
    fun showPairingRequest(from: Device, fingerprint: String)
}

// androidMain - Android notification implementation
class AndroidNotificationService(private val context: Context) : NotificationService {
    // Uses NotificationCompat to build notifications
    // Transfer progress uses foreground service notification
    // Pairing requests use heads-up notifications
}

// iosMain - iOS notification implementation
class IosNotificationService : NotificationService {
    // Uses UNUserNotificationCenter
    // Pairing requests use Critical Alerts (requires special entitlement)
}

```