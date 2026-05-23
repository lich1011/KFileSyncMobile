package com.kfilesync.mobile.domain.model

/**
 * Transfer job state machine.
 *
 * Transitions:
 * Pending --start--> InProgress
 * InProgress --pause--> Paused
 * InProgress --fail--> Failed
 * InProgress --complete--> Completed
 * Paused --resume--> InProgress
 */
sealed class TransferState {
    object Pending : TransferState()
    object InProgress : TransferState()
    object Paused : TransferState()
    object Failed : TransferState()
    object Completed : TransferState()
}