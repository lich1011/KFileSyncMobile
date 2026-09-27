package com.kfilesync.mobile.domain.model

import kotlin.time.Instant

/** Transfer job state machine (design doc §6.5.1). */
enum class TransferState {
    Pending, Requested, Active, Paused, Verifying, Completed, Failed, Cancelled
}