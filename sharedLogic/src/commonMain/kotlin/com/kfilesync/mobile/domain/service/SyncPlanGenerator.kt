package com.kfilesync.mobile.domain.service

import com.kfilesync.mobile.domain.model.FileEntry
import com.kfilesync.mobile.domain.model.SyncPlan

/**
 * Pure function: compare local and remote indexes to produce a SyncPlan.
 * Phase 4 (T4.3) – output must cover: toPull, toPush, conflicts, unchanged.
 * Unit-tested in commonTest with Fake repositories.
 */
class SyncPlanGenerator {
    fun generate(local: List<FileEntry>, remote: List<FileEntry>): SyncPlan {
        TODO("Phase 4 – diff both indexes by VersionVector causality.")
    }
}