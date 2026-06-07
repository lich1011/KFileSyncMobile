package com.kfilesync.mobile.domain.model

import kotlin.time.Instant

/** Transfer job state machine (design doc §6.5.1). */
sealed class TransferState {
    data object Pending : TransferState()

    data class Active(val startedAt: Instant, val chunksDone: Int) : TransferState()

    data class Paused(val checkpoint: Checkpoint) : TransferState()
    data object Verifying : TransferState()

    data class Completed(val completedAt: Instant) : TransferState()

    data class Failed(val errorMessage: String, val retries: Int) : TransferState()
    data object Cancelled : TransferState()
}