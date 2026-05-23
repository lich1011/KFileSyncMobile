package com.kfilesync.mobile.application.handler

import com.kfilesync.mobile.domain.port.EventBus
import io.github.aakira.napier.Napier

/**
 * Cross-cutting handler for cascade effects when an aggregate changes state
 * (e.g. share removed -> cancel its in-flight transfers, purge file index).
 *
 * Phase 1 (T1.5) wires the registration plumbing only; subscription bodies
 * land in Phase 3 (T3.3, share lifecycle) and Phase 4 (T4.6, sync cascade).
 */
class CascadeHandler {

    fun register(eventBus: EventBus) {
        // Intentional no-op for Phase 1 - keeping the entry point so
        // Bootstrap.kt doesn't need to special-case this handler in later
        // phases. See class KDoc.
        Napier.d("CascadeHandler registered (Phase 1 no-op)")
    }
}