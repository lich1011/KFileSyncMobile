package com.kfilesync.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.view.View
import androidx.core.app.NotificationCompat
import com.kfilesync.mobile.MainActivity
import com.kfilesync.mobile.application.service.TransferAppService
import com.kfilesync.mobile.application.service.TransferRow
import com.kfilesync.mobile.domain.event.DomainEvent
import com.kfilesync.mobile.domain.event.TransferCompleted
import com.kfilesync.mobile.domain.event.TransferFailed
import com.kfilesync.mobile.domain.event.TransferProgressAdvanced
import com.kfilesync.mobile.domain.event.TransferRequested
import com.kfilesync.mobile.domain.model.TransferDirection
import com.kfilesync.mobile.domain.model.TransferState
import com.kfilesync.mobile.domain.port.EventBus
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground Service that surfaces transfer progress as a sticky notification
 * (T2.5).
 *
 * Lifecycle:
 * - Started by [KFileSyncApplication] when the first TransferRequested
 * event fires (we don't keep the service up when idle - saves battery).
 * - While running, subscribes to [TransferAppService.observeTransfers] and
 * rebuilds the notification on every emission.
 * - Stops itself when no active transfers remain.
 *
 * Notification design (matches mobile-design §11.4):
 * - Title:    "Sending Photo.jpg (+2 more) to Alice"
 * - Body:     "32% • 4.2 MB / 13.0 MB"
 * - Progress: linear, determinate (or indeterminate during Verifying)
 * - Tap:      opens MainActivity -> Transfers tab
 *
 * We register a dedicated notification channel at first start (idempotent).
 * Channel importance is LOW so the system doesn't pop a heads-up for every
 * progress tick - only completion/failure events flip to DEFAULT.
 */
class SyncForegroundService : Service() {

    private val transferService: TransferAppService by inject()
    private val eventBus: EventBus by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Initial foreground notification - the system requires startForeground()
        // within 5 s of startForegroundService(), so we post an empty placeholder
        // *before* the first transfer row arrives.
        startForeground(NOTIFICATION_ID_PROGRESS, buildPlaceholderNotification())

        if (observerJob == null) {
            observerJob = transferService.observeTransfers()
                .onEach { rows -> onTransfersUpdated(rows) }
                .launchIn(scope)

            // Completion + failure toasts - these get a separate notification
            // ID so the sticky progress one isn't replaced.
            eventBus.events()
                .filter { it is TransferCompleted || it is TransferFailed }
                .onEach { ev -> publishTerminalNotification(ev) }
                .launchIn(scope)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------- channel + notification builders -------------------------

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    "Transfer progress",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Sticky notification for in-flight transfers." }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_RESULT) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESULT,
                    "Transfer results",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Completion and failure notifications." }
            )
        }
    }

    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle("KFileSync")
            .setContentText("Starting transfer...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppPendingIntent())
            .build()

    private fun onTransfersUpdated(rows: List<TransferRow>) {
        val active = rows.filter { isActive(it) }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID_PROGRESS, buildProgressNotification(active))
    }

    private fun buildProgressNotification(active: List<TransferRow>): Notification {
        // We pick the row most recently updated as the headline; everything
        // else gets a one-line summary in the expanded body.
        val headline = active.first()
        val title = headlineTitle(headline)
        val percent = (headline.ratio * 100).toInt()
        val body = if (active.size == 1) {
            "$percent% • ${formatBytes(headline.transferredBytes)} / ${formatBytes(headline.totalBytes)}"
        } else {
            "$percent% • ${active.size} active transfers"
        }

        val indeterminate = headline.state is TransferState.Verifying
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(
                if (headline.direction == TransferDirection.Outgoing)
                    android.R.drawable.stat_sys_upload
                else
                    android.R.drawable.stat_sys_download
            )
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setContentIntent(openAppPendingIntent())
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    active.joinToString("\n") { row ->
                        val rowPct = (row.ratio * 100).toInt()
                        "${headlineTitle(row)} - $rowPct%"
                    }
                )
            )
            .build()
    }

    private fun publishTerminalNotification(ev: DomainEvent) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())

        when (ev) {
            is TransferCompleted -> {
                builder.setContentTitle("Transfer complete")
                    .setContentText("${formatBytes(ev.totalBytes)} transferred")
            }
            is TransferFailed -> {
                builder.setContentTitle("Transfer failed")
                    .setContentText(ev.reason)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
            }
            else -> return
        }

        // Unique notification id per terminal event so completed transfers
        // don't overwrite each other.
        val id = NOTIFICATION_ID_RESULT_BASE + (System.currentTimeMillis() % 10_000).toInt()
        mgr.notify(id, builder.build())
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun headlineTitle(row: TransferRow): String {
        val verb = if (row.direction == TransferDirection.Outgoing) "Sending" else "Receiving"
        val name = row.firstFileName.substringAfterLast('/').ifBlank { row.firstFileName }
        val suffix = if (row.totalFiles > 1) " (+${row.totalFiles - 1} more)" else ""
        return "$verb $name$suffix to ${row.peerAlias}"
    }

    private fun isActive(row: TransferRow): Boolean = when (row.state) {
        is TransferState.Active,
        is TransferState.Paused,
        TransferState.Pending,
        TransferState.Verifying -> true
        else -> false
    }

    companion object {
        private const val CHANNEL_PROGRESS = "kfilesync_progress"
        private const val CHANNEL_RESULT = "kfilesync_result"
        private const val NOTIFICATION_ID_PROGRESS = 1001
        private const val NOTIFICATION_ID_RESULT_BASE = 2000

        /** Starts the service from anywhere; safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "$bytes B" else "%.1f %s".format(value, units[unit])
    }
}