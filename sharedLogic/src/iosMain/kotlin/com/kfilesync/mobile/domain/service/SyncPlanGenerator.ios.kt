package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.SyncConflict
import com.kfilesync.mobile.domain.model.SyncPlan

/**
 * iOS keeps the original hand-written diff algorithm (mirrors the desktop
 * client's `SyncPlanGenerator` exactly so two peers reach the same decision)
 * until the iOS UniFFI toolchain is wired up (Sprint 3 UniFFI migration;
 * see the Android `actual`, which delegates to `uniffi.kfilesync_core.generate`).
 *
 * Algorithm:
 * For each `path` that appears on either side:
 * case 1 - only local:                   push (local exists, remote doesn't)
 * case 2 - only remote:                  pull (remote exists, local doesn't)
 * case 3 - both, local == remote:        unchanged (vectors equal)
 * case 4 - both, local ancestor-of remote: pull (remote is newer)
 * case 5 - both, remote ancestor-of local: push (local is newer)
 * case 6 - both, concurrent:             conflict
 *
 * Tombstones are first-class citizens:
 * - if the *winning* side is a tombstone, we still emit toPull/toPush so the
 * other side learns to delete (the receiver applies the tombstone via
 * [FileEntry.markDeleted]'s side: when the inbound `FileEntry.deleted = true`
 * lands in the index, the on-disk file gets removed).
 * - if both sides are tombstones with matching vectors, classify as unchanged.
 *
 * Determinism: the entries inside each output list are sorted by `path` so
 * the same input always yields the same SyncPlan.
 */
actual class SyncPlanGenerator actual constructor() {

    actual fun generate(local: List<FileEntry>, remote: List<FileEntry>): SyncPlan {
        val localByPath = local.associateBy { it.path }
        val remoteByPath = remote.associateBy { it.path }
        val allPaths = (localByPath.keys + remoteByPath.keys).sorted()

        val toPull = mutableListOf<FileEntry>()
        val toPush = mutableListOf<FileEntry>()
        val conflicts = mutableListOf<SyncConflict>()
        val unchanged = mutableListOf<FileEntry>()

        for (path in allPaths) {
            val l = localByPath[path]
            val r = remoteByPath[path]
            when {
                l != null && r == null -> toPush += l                     // case 1
                l == null && r != null -> toPull += r                     // case 2
                l != null && r != null -> classifyBothSides(l, r, toPull, toPush, conflicts, unchanged)
            }
        }

        return SyncPlan(toPull = toPull, toPush = toPush, conflicts = conflicts, unchanged = unchanged)
    }

    private fun classifyBothSides(
        local: FileEntry,
        remote: FileEntry,
        toPull: MutableList<FileEntry>,
        toPush: MutableList<FileEntry>,
        conflicts: MutableList<SyncConflict>,
        unchanged: MutableList<FileEntry>
    ) {
        val localVec = local.versionVector
        val remoteVec = remote.versionVector

        when {
            // Case 3: identical vectors - same logical version.
            localVec == remoteVec -> unchanged += local

            // Case 4: local strict-ancestor-of remote -> remote is newer, pull.
            localVec.isAncestorOf(remoteVec) -> toPull += remote

            // Case 5: remote strict-ancestor-of local -> local is newer, push.
            remoteVec.isAncestorOf(localVec) -> toPush += local

            // Case 6: concurrent edits - conflict.
            else -> conflicts += SyncConflict(
                shareId = local.shareId,
                path = local.path,
                local = local,
                remote = remote
            )
        }
    }
}